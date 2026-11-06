# ALIS Next-Generation Upgrade — Verification Record

Date: 2026-10-14 (UTC environment date)

## Python checks

- `py_compile` passed for `alis_training_service.py`, `alis_tiny_sft_trainer.py`, `alis_hf_lora_trainer.py`, `alis_research_pipeline.py`, and `alis_evaluation_lab.py`.
- Training-service test suite: **7 passed** (5 reference worker tests + 2 real tiny-trainer tests).
- Research pipeline test suite: **6 passed**.
- Evaluation lab test suite: **5 passed**.
- Total Python tests: **18 passed**.

## Kotlin routing checks

- `ModelRouter`, `ProviderCapability`, `ModelProvider`, and `ModelTrafficRouter` compiled with `kotlinc` and the installed coroutine library.
- `OllamaProvider` compiled against a lightweight `OllamaRuntimeAdapter` dependency stub. This validates changed Kotlin syntax/type wiring but does not test HTTP integration.
- Six routing tests were invoked manually using lightweight JUnit assertion stubs: provider fallback, offline-only filtering, empty response fallback/health, preferred-provider order, circuit breaker, and canary fallback callback. **6 passed**.
- This is not the Android app's Gradle/JUnit run.

## Real training smoke experiment

- Runtime: PyTorch 2.10.0+cpu, CPU only.
- Dataset: 12 synthetic test-only rows; deterministic split 8 train / 2 validation / 2 held-out test.
- First run: 3 epochs. Training loss **5.6223 → 4.2985**; validation loss **5.5481 → 4.2822**; held-out loss **4.2711**.
- Parameter fingerprint changed. A checkpoint containing model state, optimizer state, epoch, PyTorch RNG state and Python RNG state was saved.
- Resume run restored epoch 3 and trained one additional epoch; training loss **4.2985 → 3.9093**; parameter fingerprint changed again.
- This proves the small training/checkpoint pipeline can update parameters and resume. It does **not** prove a useful language model, foundation-model fine-tuning, or broad generalization. The fixture must never be used as production training data.
- Exact hashes and fingerprints: `REAL_TRAINING_SMOKE_REPORT.json`.

## Not tested here

- No local Ollama server was available, so no live model inference/comparison or model import was run.
- `transformers` and `peft` were not installed, so the optional pretrained LoRA/QLoRA script was syntax-checked but not executed.
- No Android Gradle files/wrapper were included in this source archive; the project owner notes they remain in the existing successful-build project. Reinsert the modified `src/` into that project and run its existing Android build/tests.
- The research pipeline tests use pure/local unit tests; no external website was crawled during verification.
