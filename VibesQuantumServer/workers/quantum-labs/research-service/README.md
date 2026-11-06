# ALIS research and data-intelligence pipeline

This is a conservative collector for operator-supplied, authorized HTTPS URLs. It does not crawl the whole internet or search for sources automatically. The operator must provide an explicit domain allowlist, a list of allowed licenses, source attribution and a per-source `training_use_allowed` decision. The collector checks `robots.txt`, same-host redirects, public DNS/IP resolution, content type, response size, timeout, text quality and content deduplication. Output goes to a quarantine corpus with URL, license, attribution, content hash, collection timestamp, quality score and review status.

## Configuration

```json
{
  "policy": {
    "allowed_domains": ["docs.example.org"],
    "allowed_licenses": ["CC-BY-4.0"],
    "max_bytes_per_document": 2000000,
    "max_documents_per_run": 10,
    "timeout_seconds": 12,
    "min_text_characters": 300,
    "min_unique_token_ratio": 0.08,
    "require_robots_txt": true
  },
  "sources": [
    {
      "url": "https://docs.example.org/guide",
      "license_name": "CC-BY-4.0",
      "attribution": "Example organization, Guide title, accessed YYYY-MM-DD",
      "training_use_allowed": false,
      "source_type": "technical-documentation"
    }
  ]
}
```

Run:

```bash
python3 alis_research_pipeline.py --config research-config.json --output ./quarantine-corpus
```

The default policy is fail-closed when `robots.txt` cannot be verified. If a page is collected, it remains `PENDING_REVIEW` and `training_eligible=false`. A human must verify license, source reliability and intended use, clean any quality-flagged text, and then explicitly approve it. The collector does not infer licenses from page content, determine legal status, or prove factual accuracy. Do not set `training_use_allowed=true` without checking the actual terms.

## Current limits

- Discovery is operator-seeded URL intake, not a search-engine crawler or scheduled discovery service.
- HTML extraction is deliberately basic; PDFs, office documents, code repository APIs, OCR and paywalled sources are not implemented here.
- Contradiction detection and claim verification require a configured independent verifier; lexical heuristics are not treated as truth.
- DNS is checked before requests and redirects are restricted to the original allowlisted host, but production deployment should still use an outbound network policy/firewall to prevent DNS-rebinding and metadata-service access.
- Store the corpus on a quota-limited disk and configure retention/backup separately.
