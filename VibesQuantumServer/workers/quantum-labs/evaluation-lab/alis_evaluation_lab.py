#!/usr/bin/env python3
"""Repeatable held-out comparison for local Ollama models (deterministic lexical metrics only).

Input JSONL rows: id, task_type, input, expected. Keep this file independent of training data.
The tool checks for exact normalized duplicates against an optional training JSONL, measures
normalized exact match, token F1, empty/error rate and latency, then emits a promotion recommendation.
It does not claim factual truth from lexical overlap and never deploys a model.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


def normalize(text: str) -> str:
    return " ".join(re.findall(r"[\w]+", text.casefold(), flags=re.UNICODE))


def token_f1(prediction: str, reference: str) -> float:
    pred, ref = normalize(prediction).split(), normalize(reference).split()
    if not pred or not ref: return 1.0 if pred == ref else 0.0
    from collections import Counter
    overlap = sum((Counter(pred) & Counter(ref)).values())
    if overlap == 0: return 0.0
    precision, recall = overlap / len(pred), overlap / len(ref)
    return 2 * precision * recall / (precision + recall)


def read_holdout(path: Path, training_path: Path | None = None) -> list[dict[str, str]]:
    rows, ids, normalized_inputs = [], set(), set()
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip(): continue
        row = json.loads(line)
        for key in ("id", "task_type", "input", "expected"):
            if not isinstance(row.get(key), str) or not row[key].strip():
                raise ValueError(f"Holdout row {line_no} is missing '{key}'")
        if row["id"] in ids: raise ValueError(f"Duplicate holdout ID on row {line_no}")
        normalized = normalize(row["task_type"] + " " + row["input"] + " " + row["expected"])
        if normalized in normalized_inputs: raise ValueError(f"Duplicate holdout content on row {line_no}")
        ids.add(row["id"]); normalized_inputs.add(normalized); rows.append(row)
    if len(rows) < 3: raise ValueError("At least three held-out cases are required")
    if training_path:
        training_hashes = set()
        for line in training_path.read_text(encoding="utf-8").splitlines():
            if not line.strip(): continue
            row = json.loads(line)
            training_hashes.add(normalize(str(row.get("task_type", "")) + " " + str(row.get("input", "")) + " " + str(row.get("target", ""))))
        overlap = normalized_inputs & training_hashes
        if overlap: raise ValueError(f"Holdout leakage detected: {len(overlap)} normalized cases overlap the training dataset")
    return rows


def validate_base_url(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Base URL must be an HTTP(S) URL without embedded credentials")
    if parsed.scheme == "http" and parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("Cleartext HTTP is allowed only for loopback Ollama; use HTTPS for remote endpoints")
    return base_url.rstrip("/")


def call_ollama(base_url: str, model: str, prompt: str, timeout: float, api_key: str | None = None) -> str:
    payload = json.dumps({"model": model, "prompt": prompt, "stream": False, "options": {"temperature": 0}}).encode()
    headers = {"Content-Type": "application/json"}
    if api_key: headers["Authorization"] = "Bearer " + api_key
    request = urllib.request.Request(base_url + "/api/generate", data=payload, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read(4_000_001)
    if len(raw) > 4_000_000:
        raise ValueError("Ollama response exceeded the 4 MB evaluation response limit")
    data = json.loads(raw)
    text = data.get("response")
    if not isinstance(text, str): raise ValueError("Ollama response is missing the response text field")
    return text.strip()


def summarize(results: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(results)
    return {
        "cases": count,
        "normalized_exact_match": sum(item["exact_match"] for item in results) / max(count, 1),
        "token_f1": sum(item["token_f1"] for item in results) / max(count, 1),
        "mean_output_tokens": sum(item["output_token_count"] for item in results) / max(count, 1),
        "mean_expected_tokens": sum(item["expected_token_count"] for item in results) / max(count, 1),
        "mean_length_ratio": sum(item["length_ratio"] for item in results) / max(count, 1),
        "empty_or_failed_rate": sum(item["error"] is not None or not item["output"].strip() for item in results) / max(count, 1),
        "mean_latency_ms": sum(item["latency_ms"] for item in results) / max(count, 1),
        "by_task": {
            task: {
                "cases": len(group),
                "normalized_exact_match": sum(item["exact_match"] for item in group) / len(group),
                "token_f1": sum(item["token_f1"] for item in group) / len(group),
                "empty_or_failed_rate": sum(item["error"] is not None or not item["output"].strip() for item in group) / len(group),
            }
            for task in sorted({item["task_type"] for item in results})
            for group in [[item for item in results if item["task_type"] == task]]
        },
    }


def compare(baseline: dict[str, Any], candidate: dict[str, Any], min_f1_delta: float, max_task_regression: float, max_length_ratio: float = 3.0) -> dict[str, Any]:
    delta = candidate["token_f1"] - baseline["token_f1"]
    task_regressions = []
    for task, base_metrics in baseline["by_task"].items():
        if task not in candidate["by_task"]: task_regressions.append({"task": task, "reason": "candidate missing task category"}); continue
        regression = base_metrics["token_f1"] - candidate["by_task"][task]["token_f1"]
        if regression > max_task_regression:
            task_regressions.append({"task": task, "regression": regression})
    length_guard_passed = candidate.get("mean_length_ratio", 0.0) <= max_length_ratio
    passed = delta >= min_f1_delta and not task_regressions and candidate["empty_or_failed_rate"] <= baseline["empty_or_failed_rate"] and length_guard_passed
    return {"candidate_f1_delta": delta, "task_regressions": task_regressions, "mean_length_ratio": candidate.get("mean_length_ratio", 0.0),
            "max_length_ratio": max_length_ratio, "length_guard_passed": length_guard_passed, "passed_configured_gates": passed,
            "decision": "REQUEST_HUMAN_REVIEW" if passed else "REJECT_CANDIDATE",
            "note": "Lexical benchmark metrics are not proof of factual accuracy, safety, or general intelligence. No model was deployed."}


def evaluate_model(base_url: str, model: str, rows: list[dict[str, str]], timeout: float, api_key: str | None) -> list[dict[str, Any]]:
    results = []
    for row in rows:
        started = time.monotonic(); output = ""; error = None
        prompt = f"Task type: {row['task_type']}\nUser input: {row['input']}\nAnswer directly and concisely."
        try: output = call_ollama(base_url, model, prompt, timeout, api_key)
        except Exception as exc: error = f"{type(exc).__name__}: {str(exc)[:300]}"
        results.append({"id": row["id"], "task_type": row["task_type"], "input": row["input"], "expected": row["expected"],
                        "output": output, "error": error, "latency_ms": round((time.monotonic() - started) * 1000, 2),
                        "exact_match": float(normalize(output) == normalize(row["expected"]) and bool(output)),
                        "token_f1": token_f1(output, row["expected"]),
                        "output_token_count": len(normalize(output).split()),
                        "expected_token_count": len(normalize(row["expected"]).split()),
                        "length_ratio": len(normalize(output).split()) / max(1, len(normalize(row["expected"]).split()))})
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:11434")
    parser.add_argument("--baseline-model", required=True)
    parser.add_argument("--candidate-model", required=True)
    parser.add_argument("--holdout", required=True, type=Path)
    parser.add_argument("--training-dataset", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--min-f1-delta", type=float, default=0.02)
    parser.add_argument("--max-task-regression", type=float, default=0.05)
    parser.add_argument("--max-length-ratio", type=float, default=3.0, help="Reject outputs averaging more than this many tokens per expected token")
    args = parser.parse_args()
    if not (1 <= args.timeout <= 600 and -1 <= args.min_f1_delta <= 1 and 0 <= args.max_task_regression <= 1 and 1 <= args.max_length_ratio <= 20): parser.error("Invalid evaluation thresholds")
    base_url = validate_base_url(args.base_url)
    rows = read_holdout(args.holdout, args.training_dataset)
    import os
    api_key = os.environ.get("ALIS_EVAL_API_KEY")
    baseline_rows = evaluate_model(base_url, args.baseline_model, rows, args.timeout, api_key)
    candidate_rows = evaluate_model(base_url, args.candidate_model, rows, args.timeout, api_key)
    baseline_summary, candidate_summary = summarize(baseline_rows), summarize(candidate_rows)
    report = {"created_at": time.time(), "base_url": base_url, "baseline_model": args.baseline_model, "candidate_model": args.candidate_model,
              "holdout_sha256": hashlib.sha256(args.holdout.read_bytes()).hexdigest(), "baseline": baseline_summary,
              "candidate": candidate_summary, "comparison": compare(baseline_summary, candidate_summary, args.min_f1_delta, args.max_task_regression, args.max_length_ratio),
              "per_case": {"baseline": baseline_rows, "candidate": candidate_rows}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("baseline_model", "candidate_model", "baseline", "candidate", "comparison")}, indent=2))
    return 0 if report["comparison"]["passed_configured_gates"] else 2

if __name__ == "__main__": raise SystemExit(main())
