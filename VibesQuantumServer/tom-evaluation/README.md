# Theory of Mind evaluation lab

This folder provides a dependency-free evaluation harness for the optional `/api/v1/theory-of-mind` capability.

## Current runtime boundary

The first implementation accepts user-supplied text and user-supplied speech transcripts. It does not accept raw audio, analyze faces/video, identify people, or persist the submitted text. Cloud processing is disabled by default and requires a separate `allowCloudProcessing=true` request choice. Provider retention and processing policies still apply when the user explicitly allows cloud processing.

The output is a set of possible interpretations, not a claim that the system knows what someone actually feels or intends. Evidence quotes are checked against the submitted text; unsupported quotes and associated emotion/intent hypotheses are discarded. No medical diagnosis, sensitive-trait inference, manipulation, or high-stakes decision support is allowed.

## Evaluation metrics

The harness reports:

- Emotion accuracy and macro-F1.
- Intent top-label accuracy.
- Evidence-quote grounding rate.
- Abstention rate.
- Accuracy by language and explicitly supplied cultural-context slices, plus an observed slice gap when enough examples are present.

Numeric confidence calibration is intentionally not reported because the API does not expose an uncalibrated probability. Metrics are regression measures, not proof of human-like understanding.

## Dataset protocol

Do not scrape faces, voices, private messages, or social profiles. Use licensed or explicitly consented text/transcript examples, strip direct identifiers, minimize retention, document language and annotation guidance, and keep a holdout split that is not used for prompt tuning. A culture label must be self-described or an explicit scenario attribute; never infer it from names or writing style. Recruit annotators with varied language/cultural backgrounds and report disagreement rather than hiding it. Keep sensitive or high-stakes conversations out of the dataset.

Each JSONL line supplied to the evaluator should be an object with fields such as:

```json
{"source_text":"I may need some time","expected_emotion":"uncertain","predicted_emotion":"uncertain","expected_intent":"asking_for_time","predicted_intent":"asking_for_time","language":"en","culture_context":"","predicted_evidence_quotes":["I may need some time"],"abstained":false}
```

Run locally:

```bash
python tom-evaluation/tom_metrics.py ./private-holdout-results.jsonl --minimum-slice-size 10 --output ./tom-report.json
python -m unittest discover -s tom-evaluation/tests -v
```

Keep the private holdout file and generated report outside public source control unless its contributors have consented to publication.
