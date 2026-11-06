# ALIS training runtime and real training experiments

The service/orchestrator and the trainer are separate responsibilities. The HTTP worker executes the configured trainer only inside its digest-pinned, network-isolated container sandbox. If the image, dependency lock, resource limits, artifact allow-list or redacted log policy is incomplete, the worker health and job APIs fail closed with HTTP 503. See `SANDBOX_DEPLOYMENT.md` before enabling a real worker.

## 1. Tiny reproducible PyTorch trainer (smoke experiment)

`alis_tiny_sft_trainer.py` trains a tiny byte-level GRU language model from scratch on approved JSONL rows. It uses cross-entropy loss and AdamW gradient updates, deterministic train/validation/held-out-test splits, checks that parameter fingerprints change, records loss/latency/device/resource data, and writes both an inference state artifact and a resumable model+optimizer checkpoint. The script verifies the row content hashes and human-approval field before training.

This tiny model is for integration proof only. It is **not** a fine-tuned foundation model, not a useful general-purpose assistant, and not proof of broad intelligence improvement. The bundled `examples/tiny-smoke-approved.jsonl` is a synthetic test fixture with a test-only approver; never use it as a production dataset.

Example:

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install 'torch>=2.2,<3'
python alis_tiny_sft_trainer.py \
  --dataset examples/tiny-smoke-approved.jsonl \
  --output-dir ./run-output \
  --epochs 4 --batch-size 2 --hidden-size 32 --device cpu
```

Resume from a completed epoch checkpoint:

```bash
python alis_tiny_sft_trainer.py \
  --dataset examples/tiny-smoke-approved.jsonl \
  --output-dir ./run-output \
  --resume-dir ./run-output \
  --epochs 1 --device cpu
```

Trainer-level guardrails include maximum examples, sequence length, runtime, minimum available RAM and free disk space. The reference worker additionally enforces a container wall-clock timeout, memory/CPU/PID/tmpfs limits, no network, a read-only container root and a writable output-only mount. A hard kill can lose the current partial epoch; the last completed-epoch checkpoint remains intact.

## 2. LoRA / QLoRA for a compatible pretrained causal LM

`alis_hf_lora_trainer.py` is the optional real adapter fine-tuning path. It uses Hugging Face Transformers + PEFT, masks prompt tokens from the supervised loss, keeps a validation split and a held-out test split, saves adapter/tokenizer files and a report, and rejects unapproved, duplicate or hash-mismatched examples. The base model is local-only by default; `--allow-download` is an explicit opt-in to download model files.

Install the optional stack in a dedicated environment:

```bash
python -m pip install -r requirements-training.txt
# For QLoRA only, install a bitsandbytes wheel compatible with your CUDA/PyTorch combination.
```

Example using a local base model directory:

```bash
python alis_hf_lora_trainer.py \
  --dataset /absolute/path/approved-dataset.jsonl \
  --output-dir /absolute/path/run-output \
  --base-model /absolute/path/to/local-causal-lm \
  --epochs 1 --max-length 1024 --target-modules q_proj,v_proj
```

Add `--qlora` only on a compatible CUDA setup with bitsandbytes installed. Target-module names vary by architecture and must be checked against the chosen model. This path was syntax-checked in the ALIS source environment, but its runtime dependencies were not installed there, so a pretrained-model LoRA run is **not claimed as tested**. QLoRA is not assumed to work on CPU, Android, or arbitrary GPU hardware.

## 3. Connecting the trainer to the reference HTTP worker

Configure the worker with an in-image absolute Python path, JSON argument array, dataset-host allow-list, artifact suffix allow-list, strong bearer key, local bind address and external TLS termination. The trainer path is interpreted inside the pinned image, never on the worker host. Example argument array for the tiny trainer:

```json
["/opt/vibes/alis_tiny_sft_trainer.py", "--dataset", "{dataset}", "--output-dir", "{outputDir}", "--device", "cpu"]
```

The reference worker currently does not implement remote checkpoint download/resume. Use the local runtime for the tested local resume path, or implement and test remote checkpoint transfer before enabling remote resume. Never expose the worker directly to the public Internet without HTTPS, authentication, firewalling and monitoring. Host-process fallback is prohibited.

## 4. Dataset contract and trust boundary

JSONL must contain at least `id`, `task_type`, `input`, `target`, `content_hash`, and `approved_by`; the HTTP service additionally requires `source_correction_id`. Hashes are SHA-256 over normalized task type, input and target separated by NUL characters. Each row must be independently approved. The trainer is not a source-verification tool: provenance, license and approval checks must happen upstream.

## 5. Artifacts and honest status

- `model-state.pt`: tiny model weights and architecture metadata, for the smoke trainer only.
- `checkpoint-last.pt`: model + optimizer + epoch + RNG state; resume is tested by the included test suite.
- `metrics.json`: train/validation/held-out loss, parameter fingerprints, dataset hash, device/resource metadata.
- HF trainer: adapter files, tokenizer files and `alis-training-report.json`.

A process exit code of zero is not enough for production promotion. Run independent evaluation on held-out cases, inspect regressions and safety, bind the report to the artifact hash, then require named human approval and use the existing deployment controller.

## Mandatory sandbox gate

Training execution requires a digest-pinned container image whose `org.vibes.dependency-lock-sha256` label matches a fully pinned, SHA-256-hashed one-line-per-package lock file. The worker enforces Docker/Podman resource limits and mounts only the approved dataset (read-only) and model output (read/write). Artifact metadata includes only explicitly allow-listed suffixes, and logs are redacted before persistence. The worker publishes an authenticated `GET /v1/training/health` attestation; the Quantum gateway refuses all job operations unless the attestation is complete. Follow `SANDBOX_DEPLOYMENT.md`; the API remains unavailable until the policy is operational.
