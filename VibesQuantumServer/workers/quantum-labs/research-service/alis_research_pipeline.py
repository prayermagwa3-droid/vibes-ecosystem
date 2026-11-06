#!/usr/bin/env python3
"""Conservative, provenance-preserving research collector for explicitly authorized public sources.

This pipeline discovers nothing by crawling the whole web: an operator supplies exact HTTPS URLs
and an allowlist of domains. It checks robots.txt, refuses off-host redirects and non-public IP
literals, enforces byte/time/URL limits, extracts text, deduplicates content, and writes only to a
quarantine corpus. It never marks collected content as approved training data.
"""
from __future__ import annotations
import argparse
import hashlib
import html
import ipaddress
import json
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
from dataclasses import asdict, dataclass
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable

USER_AGENT = "VibesALISResearchBot/1.0 (+operator-configured; respects robots.txt)"


@dataclass(frozen=True)
class ResearchPolicy:
    allowed_domains: tuple[str, ...]
    allowed_licenses: tuple[str, ...]
    max_bytes_per_document: int = 2_000_000
    max_documents_per_run: int = 25
    timeout_seconds: float = 12.0
    min_text_characters: int = 300
    min_unique_token_ratio: float = 0.08
    require_robots_txt: bool = True

    def __post_init__(self) -> None:
        if not self.allowed_domains:
            raise ValueError("At least one explicitly authorized domain is required")
        if not self.allowed_licenses:
            raise ValueError("Configure permitted licenses before collection")
        if not (1_024 <= self.max_bytes_per_document <= 20_000_000):
            raise ValueError("Per-document byte limit must be between 1 KiB and 20 MB")
        if not (1 <= self.max_documents_per_run <= 500):
            raise ValueError("Document limit must be between 1 and 500")
        if not (1 <= self.timeout_seconds <= 60):
            raise ValueError("Timeout must be between 1 and 60 seconds")
        normalized = tuple(domain.lower().strip().rstrip(".") for domain in self.allowed_domains)
        if any(not domain or "/" in domain or ":" in domain for domain in normalized):
            raise ValueError("Allowed domains must be hostnames, not URLs or host:port strings")
        licenses = tuple(value.strip() for value in self.allowed_licenses if value.strip())
        if not licenses:
            raise ValueError("At least one non-empty permitted license is required")
        object.__setattr__(self, "allowed_domains", normalized)
        object.__setattr__(self, "allowed_licenses", licenses)


@dataclass(frozen=True)
class ResearchSource:
    url: str
    license_name: str
    attribution: str
    training_use_allowed: bool
    source_type: str = "educational"


@dataclass(frozen=True)
class ResearchDocument:
    id: str
    canonical_url: str
    title: str
    text: str
    sha256: str
    license_name: str
    attribution: str
    collected_at: float
    byte_count: int
    quality_score: float
    quality_flags: tuple[str, ...]
    training_use_allowed: bool = False
    approval_status: str = "PENDING_REVIEW"
    training_eligible: bool = False
    approved_by: str | None = None
    approval_notes: str = ""
    cleaned_by: str | None = None
    cleaning_notes: str = ""


class _TextExtractor(HTMLParser):
    SKIP = {"script", "style", "noscript", "svg", "canvas", "nav", "footer", "header", "aside", "form"}
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.title_parts: list[str] = []
        self._skip = 0
        self._in_title = False
    def handle_starttag(self, tag: str, attrs) -> None:
        if tag in self.SKIP: self._skip += 1
        if tag == "title": self._in_title = True
        if tag in {"p", "div", "li", "h1", "h2", "h3", "br", "tr"}: self.parts.append("\n")
    def handle_endtag(self, tag: str) -> None:
        if tag in self.SKIP and self._skip: self._skip -= 1
        if tag == "title": self._in_title = False
        if tag in {"p", "div", "li", "h1", "h2", "h3", "br", "tr"}: self.parts.append("\n")
    def handle_data(self, data: str) -> None:
        if self._skip: return
        value = data.strip()
        if not value: return
        if self._in_title: self.title_parts.append(value)
        self.parts.append(value + " ")


def _is_allowed_host(host: str, allowed_domains: Iterable[str]) -> bool:
    host = host.lower().rstrip(".")
    return any(host == domain or host.endswith("." + domain) for domain in allowed_domains)


def validate_url(url: str, policy: ResearchPolicy) -> urllib.parse.SplitResult:
    parsed = urllib.parse.urlsplit(url.strip())
    if parsed.scheme.lower() != "https":
        raise ValueError("Only HTTPS research URLs are permitted")
    if not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("URL must contain a hostname and must not contain embedded credentials")
    if parsed.port not in (None, 443):
        raise ValueError("Non-standard HTTPS ports are not allowed")
    host = parsed.hostname.lower().rstrip(".")
    try:
        ip = ipaddress.ip_address(host)
        if not ip.is_global:
            raise ValueError("Private, loopback, link-local and reserved IP addresses are forbidden")
        raise ValueError("IP-literal URLs are not allowed; use an allowlisted public hostname")
    except ValueError as exc:
        if "is not allowed" in str(exc) or "forbidden" in str(exc):
            raise
    if not _is_allowed_host(host, policy.allowed_domains):
        raise ValueError(f"Host '{host}' is not in the configured research allowlist")
    if parsed.fragment:
        parsed = parsed._replace(fragment="")
    return parsed


def _resolve_public_host(host: str) -> None:
    # Reject DNS names resolving to any non-public address to reduce SSRF risk.
    try:
        results = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise ValueError(f"Could not resolve research host '{host}'") from exc
    if not results:
        raise ValueError(f"Research host '{host}' resolved to no addresses")
    for result in results:
        address = ipaddress.ip_address(result[4][0].split("%", 1)[0])
        if not address.is_global:
            raise ValueError(f"Research host '{host}' resolved to a non-public IP address")


class _AllowlistedRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, policy: ResearchPolicy, initial_host: str):
        super().__init__(); self.policy = policy; self.initial_host = initial_host
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = validate_url(newurl, self.policy)
        if parsed.hostname.lower().rstrip(".") != self.initial_host:
            raise urllib.error.HTTPError(newurl, code, "Cross-host redirect refused", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _read_limited(response, limit: int) -> bytes:
    payload = response.read(limit + 1)
    if len(payload) > limit:
        raise ValueError(f"Document exceeds configured byte limit ({limit})")
    return payload


def extract_text(payload: bytes, content_type: str) -> tuple[str, str]:
    encoding_match = re.search(r"charset=([A-Za-z0-9._-]+)", content_type, re.I)
    encoding = encoding_match.group(1) if encoding_match else "utf-8"
    source = payload.decode(encoding, errors="replace")
    if "html" in content_type.lower() or re.search(r"<\s*(html|body|article|main)\b", source[:2000], re.I):
        parser = _TextExtractor(); parser.feed(source); parser.close()
        title = re.sub(r"\s+", " ", " ".join(parser.title_parts)).strip()
        text = html.unescape(re.sub(r"[\t\r\f\v ]+", " ", "".join(parser.parts)))
    else:
        title, text = "", source
    text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
    text = re.sub(r"[ \t]+", " ", text).strip()
    return title[:500], text


def quality_assessment(text: str, policy: ResearchPolicy) -> tuple[float, tuple[str, ...]]:
    flags: list[str] = []
    if len(text) < policy.min_text_characters: flags.append("TOO_SHORT")
    tokens = re.findall(r"[\w'-]+", text.lower())
    unique_ratio = len(set(tokens)) / max(1, len(tokens))
    if unique_ratio < policy.min_unique_token_ratio: flags.append("LOW_TOKEN_DIVERSITY")
    if len(text) and len(re.findall(r"(.)\1{15,}", text)) > 0: flags.append("REPETITIVE_CHARACTER_RUN")
    if text.count("\ufffd") / max(1, len(text)) > 0.01: flags.append("ENCODING_REPLACEMENTS")
    length_score = min(1.0, len(text) / max(policy.min_text_characters * 3, 1))
    score = max(0.0, min(1.0, 0.55 * length_score + 0.45 * unique_ratio - 0.2 * len(flags)))
    return score, tuple(flags)


class ResearchPipeline:
    def __init__(self, policy: ResearchPolicy, quarantine_directory: Path):
        self.policy = policy
        self.root = quarantine_directory
        self.root.mkdir(parents=True, exist_ok=True)
        self.index_path = self.root / "research-index.jsonl"

    def collect(self, sources: list[ResearchSource]) -> list[ResearchDocument]:
        if len(sources) > self.policy.max_documents_per_run:
            raise ValueError("Source list exceeds configured per-run document limit")
        seen_urls: set[str] = set()
        seen_hashes = self._existing_hashes()
        documents: list[ResearchDocument] = []
        for source in sources:
            if source.license_name not in self.policy.allowed_licenses:
                raise ValueError(f"License '{source.license_name}' is not in the configured allowed-license list")
            if not source.attribution.strip():
                raise ValueError("Source attribution is required")
            parsed = validate_url(source.url, self.policy)
            canonical = urllib.parse.urlunsplit(("https", parsed.netloc.lower(), parsed.path or "/", parsed.query, ""))
            if canonical in seen_urls: continue
            seen_urls.add(canonical)
            _resolve_public_host(parsed.hostname)
            if self.policy.require_robots_txt and not self._robots_allows(canonical):
                raise PermissionError(f"robots.txt disallows collection of {canonical} or could not be verified")
            opener = urllib.request.build_opener(_AllowlistedRedirect(self.policy, parsed.hostname.lower().rstrip(".")))
            request = urllib.request.Request(canonical, headers={"User-Agent": USER_AGENT, "Accept": "text/html,text/plain,application/xhtml+xml"})
            with opener.open(request, timeout=self.policy.timeout_seconds) as response:
                content_type = response.headers.get("Content-Type", "")
                if not any(kind in content_type.lower() for kind in ("text/html", "text/plain", "application/xhtml+xml")):
                    raise ValueError(f"Unsupported content type: {content_type}")
                raw = _read_limited(response, self.policy.max_bytes_per_document)
            title, text = extract_text(raw, content_type)
            if not text: raise ValueError(f"No readable text extracted from {canonical}")
            digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
            if digest in seen_hashes: continue
            score, flags = quality_assessment(text, self.policy)
            doc = ResearchDocument(
                id=hashlib.sha256((canonical + digest).encode()).hexdigest()[:32], canonical_url=canonical,
                title=title, text=text, sha256=digest, license_name=source.license_name,
                attribution=source.attribution.strip()[:1000], collected_at=time.time(), byte_count=len(raw),
                quality_score=score, quality_flags=flags, training_use_allowed=source.training_use_allowed,
                approval_status="PENDING_REVIEW", training_eligible=False,
            )
            self._persist(doc)
            documents.append(doc); seen_hashes.add(digest)
        return documents

    def clean_and_reassess(self, document_id: str, cleaned_text: str, editor: str, notes: str = "") -> ResearchDocument:
        if not re.fullmatch(r"[a-f0-9]{32}", document_id): raise ValueError("Malformed research document ID")
        if not editor.strip(): raise ValueError("A named editor is required")
        if not cleaned_text.strip(): raise ValueError("Cleaned document text cannot be empty")
        if len(cleaned_text.encode("utf-8")) > self.policy.max_bytes_per_document:
            raise ValueError("Cleaned document exceeds the configured document-size limit")
        records = self._load_records()
        found = next((record for record in records if record["id"] == document_id), None)
        if found is None: raise KeyError(document_id)
        text = re.sub(r"\n\s*\n\s*\n+", "\n\n", cleaned_text)
        text = re.sub(r"[ \t]+", " ", text).strip()
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        score, flags = quality_assessment(text, self.policy)
        found.update({"sha256": digest, "byte_count": len(text.encode("utf-8")),
                      "quality_score": score, "quality_flags": list(flags), "approval_status": "PENDING_REVIEW",
                      "training_eligible": False, "approved_by": None, "approval_notes": "",
                      "cleaned_by": editor.strip()[:200], "cleaning_notes": notes.strip()[:2000]})
        text_path = self.root / f"{document_id}.txt"
        temporary = text_path.with_suffix(".txt.tmp")
        temporary.write_text(text, encoding="utf-8"); temporary.replace(text_path)
        self._rewrite_index(records)
        found["quality_flags"] = tuple(found.get("quality_flags", ()))
        found["text"] = text
        return ResearchDocument(**{key: found[key] for key in ResearchDocument.__dataclass_fields__})

    def approve_for_training(self, document_id: str, approver: str, notes: str = "") -> ResearchDocument:
        if not approver.strip(): raise ValueError("A named human approver is required")
        records = self._load_records()
        found = next((record for record in records if record["id"] == document_id), None)
        if found is None: raise KeyError(document_id)
        if found.get("quality_flags"):
            raise ValueError("Documents with quality flags must be reviewed/cleaned before approval")
        if found.get("training_use_allowed") is not True:
            raise ValueError("Source metadata does not explicitly permit training use")
        found["approval_status"] = "APPROVED"
        found["training_eligible"] = True
        found["approved_by"] = approver.strip()[:200]
        found["approval_notes"] = notes.strip()[:2000]
        self._rewrite_index(records)
        found["quality_flags"] = tuple(found.get("quality_flags", ()))
        found["text"] = (self.root / f"{document_id}.txt").read_text(encoding="utf-8")
        return ResearchDocument(**{key: found[key] for key in ResearchDocument.__dataclass_fields__})

    def _robots_allows(self, url: str) -> bool:
        parsed = urllib.parse.urlsplit(url)
        robots_url = urllib.parse.urlunsplit(("https", parsed.netloc, "/robots.txt", "", ""))
        try:
            _resolve_public_host(parsed.hostname)
            opener = urllib.request.build_opener(_AllowlistedRedirect(self.policy, parsed.hostname.lower().rstrip(".")))
            request = urllib.request.Request(robots_url, headers={"User-Agent": USER_AGENT})
            with opener.open(request, timeout=self.policy.timeout_seconds) as response:
                content = _read_limited(response, 256_000).decode("utf-8", errors="replace")
            parser = urllib.robotparser.RobotFileParser(); parser.set_url(robots_url); parser.parse(content.splitlines())
            return parser.can_fetch(USER_AGENT, url)
        except Exception:
            return False if self.policy.require_robots_txt else True

    def _existing_hashes(self) -> set[str]:
        return {row.get("sha256", "") for row in self._load_records() if row.get("sha256")}
    def _load_records(self) -> list[dict]:
        if not self.index_path.exists(): return []
        records = []
        for line in self.index_path.read_text(encoding="utf-8").splitlines():
            if line.strip(): records.append(json.loads(line))
        return records
    def _persist(self, document: ResearchDocument) -> None:
        record = asdict(document)
        record.pop("text", None)
        record["text_path"] = f"{document.id}.txt"
        record["quality_flags"] = list(document.quality_flags)
        text_path = self.root / f"{document.id}.txt"
        temp_path = text_path.with_suffix(".txt.tmp")
        temp_path.write_text(document.text, encoding="utf-8")
        temp_path.replace(text_path)
        with self.index_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    def _rewrite_index(self, records: list[dict]) -> None:
        temp = self.index_path.with_suffix(".jsonl.tmp")
        with temp.open("w", encoding="utf-8") as handle:
            for row in records: handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
        temp.replace(self.index_path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path, help="JSON with allowed_domains, allowed_licenses and sources")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    policy = ResearchPolicy(**config["policy"])
    sources = [ResearchSource(**item) for item in config["sources"]]
    results = ResearchPipeline(policy, args.output).collect(sources)
    print(json.dumps({"collected": len(results), "quarantined": len(results), "training_approved": 0,
                      "documents": [{"id": item.id, "url": item.canonical_url, "sha256": item.sha256,
                                     "quality_score": item.quality_score, "quality_flags": item.quality_flags} for item in results]}, indent=2))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
