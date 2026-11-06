# ALIS Evaluation Lab

`alis_evaluation_lab.py` compares two models served by a reachable Ollama API against a fixed JSONL holdout set. It records per-case output, normalized exact match, token F1, empty/error rate, latency and output-length ratio. It rejects obvious normalized overlap between the holdout and an optional training JSONL, applies configurable minimum score improvement / per-task regression / answer-length gates, writes a machine-readable report, and never deploys a model.

## Holdout schema

Each JSONL row must contain:

```json
{"id":"math-001","task_type":"mathematics","input":"What is 2 + 2? Answer with the number only.","expected":"4"}
```

Keep holdout cases separate from training and validation. Prefer a larger, human-reviewed set across multiple categories, with unseen variants and adversarial/regression cases. The lexical metrics are useful for a narrow repeatable comparison but are not proof of factual accuracy, safety, reasoning or general intelligence.

## Run

```bash
python3 alis_evaluation_lab.py \
  --base-url http://127.0.0.1:11434 \
  --baseline-model my-vibes-baseline \
  --candidate-model my-vibes-candidate \
  --holdout /path/to/heldout.jsonl \
  --training-dataset /path/to/approved-training.jsonl \
  --output ./evaluation-report.json
```

Cleartext HTTP is permitted only for loopback addresses. For remote Ollama-compatible services use HTTPS and supply `ALIS_EVAL_API_KEY` in the environment; do not put secrets in command-line arguments or source files. A passed configured gate means `REQUEST_HUMAN_REVIEW`, not deployment approval. This tool was unit-tested locally, but no live Ollama model comparison was possible in the verification environment because no Ollama server was listening.
