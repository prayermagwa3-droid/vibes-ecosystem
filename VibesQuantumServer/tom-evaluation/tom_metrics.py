#!/usr/bin/env python3
"""Small, dependency-free evaluation metrics for Vibes Theory of Mind outputs."""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


def _ratio(numerator: float, denominator: float) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def _macro_f1(rows: list[dict[str, Any]], expected_key: str, predicted_key: str) -> float | None:
    labels = sorted({str(row.get(expected_key, "")).strip() for row in rows if row.get(expected_key)})
    if not labels:
        return None
    scores: list[float] = []
    for label in labels:
        tp = sum(row.get(expected_key) == label and row.get(predicted_key) == label for row in rows)
        fp = sum(row.get(expected_key) != label and row.get(predicted_key) == label for row in rows)
        fn = sum(row.get(expected_key) == label and row.get(predicted_key) != label for row in rows)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        scores.append(2 * precision * recall / (precision + recall) if precision + recall else 0.0)
    return round(sum(scores) / len(scores), 4)


def _accuracy(rows: list[dict[str, Any]], expected_key: str, predicted_key: str) -> float | None:
    valid = [row for row in rows if row.get(expected_key) not in (None, "")]
    return _ratio(sum(row.get(expected_key) == row.get(predicted_key) for row in valid), len(valid))


def _slice_accuracy(rows: list[dict[str, Any]], slice_key: str, expected_key: str, predicted_key: str, minimum: int) -> dict[str, float]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        group = str(row.get(slice_key, "")).strip()
        if group and row.get(expected_key) not in (None, ""):
            groups[group].append(row)
    return {
        group: value
        for group, group_rows in sorted(groups.items())
        if len(group_rows) >= minimum
        for value in [_accuracy(group_rows, expected_key, predicted_key)]
        if value is not None
    }


def evaluate(rows: Iterable[dict[str, Any]], minimum_slice_size: int = 5) -> dict[str, Any]:
    materialized = list(rows)
    emotion_rows = [row for row in materialized if row.get("expected_emotion") not in (None, "")]
    intent_rows = [row for row in materialized if row.get("expected_intent") not in (None, "")]

    quoted = 0
    grounded = 0
    for row in materialized:
        source_text = str(row.get("source_text", ""))
        quotes = row.get("predicted_evidence_quotes", [])
        if not isinstance(quotes, list):
            continue
        for quote in quotes:
            if not isinstance(quote, str) or not quote.strip():
                continue
            quoted += 1
            if quote.strip().casefold() in source_text.casefold():
                grounded += 1

    abstained = sum(bool(row.get("abstained", False)) for row in materialized)
    language_slices = _slice_accuracy(materialized, "language", "expected_emotion", "predicted_emotion", minimum_slice_size)
    culture_slices = _slice_accuracy(materialized, "culture_context", "expected_emotion", "predicted_emotion", minimum_slice_size)

    slice_values = list(language_slices.values()) + list(culture_slices.values())
    return {
        "sample_count": len(materialized),
        "emotion_accuracy": _accuracy(emotion_rows, "expected_emotion", "predicted_emotion"),
        "emotion_macro_f1": _macro_f1(emotion_rows, "expected_emotion", "predicted_emotion"),
        "intent_accuracy": _accuracy(intent_rows, "expected_intent", "predicted_intent"),
        "evidence_quote_grounding_rate": _ratio(grounded, quoted),
        "abstention_rate": _ratio(abstained, len(materialized)),
        "language_slice_accuracy": language_slices,
        "culture_slice_accuracy": culture_slices,
        "observed_slice_accuracy_gap": round(max(slice_values) - min(slice_values), 4) if len(slice_values) >= 2 else None,
        "minimum_slice_size": minimum_slice_size,
        "limitations": [
            "These metrics do not prove that a system knows a person's actual feelings or intentions.",
            "Use independently annotated, consented holdout data; report each language/cultural slice separately.",
            "Do not interpret slice gaps from groups smaller than minimum_slice_size.",
            "No numeric confidence calibration is reported because the runtime intentionally avoids uncalibrated confidence scores.",
        ],
    }


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, raw in enumerate(stream, 1):
            if not raw.strip():
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as error:
                raise ValueError(f"Invalid JSON on line {line_number}: {error.msg}") from error
            if not isinstance(row, dict):
                raise ValueError(f"Line {line_number} must contain a JSON object")
            rows.append(row)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="Local JSONL file containing expected and predicted labels")
    parser.add_argument("--minimum-slice-size", type=int, default=5)
    parser.add_argument("--output", type=Path, help="Optional JSON report output path")
    args = parser.parse_args()
    if args.minimum_slice_size < 2:
        parser.error("--minimum-slice-size must be at least 2")
    report = evaluate(read_jsonl(args.dataset), args.minimum_slice_size)
    rendered = json.dumps(report, indent=2, ensure_ascii=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
