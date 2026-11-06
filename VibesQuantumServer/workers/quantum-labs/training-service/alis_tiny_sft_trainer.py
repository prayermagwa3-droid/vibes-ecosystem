#!/usr/bin/env python3
"""Small, reproducible PyTorch supervised fine-tuning demonstration for ALIS-approved JSONL.

This trains a tiny byte-level GRU language model from scratch. It is a real gradient-based
neural training experiment and an integration test for dataset validation, metrics, checkpoint
save/resume, and artifact reporting. It is NOT fine-tuning of an existing foundation model and
is not intended to compete with or replace a production LLM. For LoRA/QLoRA on a compatible
pretrained model, use the optional Transformers/PEFT trainer described in README.md.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import random
import shutil
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

try:
    import torch
    from torch import nn
except ImportError as exc:  # pragma: no cover - environment-specific
    raise SystemExit("PyTorch is required. Install a compatible torch build first.") from exc

PAD_ID = 0
BOS_ID = 1
EOS_ID = 2
BYTE_OFFSET = 3
IGNORE_INDEX = -100
FORMAT_VERSION = 1


@dataclass(frozen=True)
class Example:
    id: str
    task_type: str
    input: str
    target: str
    content_hash: str
    approved_by: str


def normalized_hash(task_type: str, prompt: str, target: str) -> str:
    normalized = "\0".join(" ".join(value.lower().split()) for value in (task_type, prompt, target))
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def read_approved_dataset(path: Path, max_examples: int) -> list[Example]:
    if not path.is_file() or not path.stat().st_size:
        raise ValueError("Dataset file is missing or empty.")
    examples: list[Example] = []
    ids: set[str] = set()
    hashes: set[str] = set()
    with path.open("r", encoding="utf-8") as stream:
        for line_number, raw in enumerate(stream, start=1):
            if not raw.strip():
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Dataset row {line_number} is invalid JSON.") from exc
            required = ("id", "task_type", "input", "target", "content_hash", "approved_by")
            if any(not isinstance(row.get(key), str) or not row[key].strip() for key in required):
                raise ValueError(f"Dataset row {line_number} is missing required fields or human approval.")
            expected = normalized_hash(row["task_type"], row["input"], row["target"])
            if row["content_hash"].lower() != expected:
                raise ValueError(f"Dataset row {line_number} content hash does not match its content.")
            if row["id"] in ids:
                raise ValueError(f"Dataset row {line_number} repeats an example ID.")
            if expected in hashes:
                raise ValueError(f"Dataset row {line_number} duplicates normalized training content.")
            ids.add(row["id"])
            hashes.add(expected)
            examples.append(Example(row["id"], row["task_type"], row["input"], row["target"], expected, row["approved_by"]))
            if len(examples) > max_examples:
                raise ValueError(f"Dataset exceeds configured maximum of {max_examples} examples.")
    if len(examples) < 3:
        raise ValueError("At least three distinct approved examples are required for train/validation/test splits.")
    return examples


def split_dataset(examples: list[Example], seed: int) -> tuple[list[Example], list[Example], list[Example]]:
    ordered = sorted(examples, key=lambda item: hashlib.sha256(f"{seed}:{item.id}".encode()).hexdigest())
    count = len(ordered)
    test_count = max(1, round(count * 0.15))
    validation_count = max(1, round(count * 0.15))
    while count - test_count - validation_count < 1:
        if test_count >= validation_count and test_count > 1:
            test_count -= 1
        elif validation_count > 1:
            validation_count -= 1
        else:
            break
    train = ordered[: count - validation_count - test_count]
    validation = ordered[count - validation_count - test_count : count - test_count]
    test = ordered[count - test_count :]
    assert train and validation and test
    return train, validation, test


def encode_example(example: Example, max_sequence_length: int) -> tuple[list[int], list[int]]:
    prompt = f"Task: {example.task_type}\nInstruction: {example.input}\nAnswer:"
    prompt_bytes = prompt.encode("utf-8")
    target_bytes = example.target.encode("utf-8")
    # reserve room for BOS/EOS; truncate prompt first, never silently remove all target tokens
    max_content = max_sequence_length - 2
    if len(target_bytes) > max_content:
        target_bytes = target_bytes[:max_content]
    prompt_room = max(0, max_content - len(target_bytes))
    prompt_bytes = prompt_bytes[-prompt_room:] if prompt_room else b""
    token_ids = [BOS_ID] + [b + BYTE_OFFSET for b in prompt_bytes] + [b + BYTE_OFFSET for b in target_bytes] + [EOS_ID]
    inputs = token_ids[:-1]
    labels = token_ids[1:].copy()
    # Predict only the answer/EOS tokens; prompt tokens provide context but not training targets.
    first_target_label_index = len(prompt_bytes)
    for index in range(first_target_label_index):
        labels[index] = IGNORE_INDEX
    return inputs, labels


def batchify(rows: list[tuple[list[int], list[int]]], device: torch.device) -> tuple[torch.Tensor, torch.Tensor]:
    width = max(len(inputs) for inputs, _ in rows)
    inputs = torch.full((len(rows), width), PAD_ID, dtype=torch.long)
    labels = torch.full((len(rows), width), IGNORE_INDEX, dtype=torch.long)
    for index, (input_row, label_row) in enumerate(rows):
        inputs[index, : len(input_row)] = torch.tensor(input_row, dtype=torch.long)
        labels[index, : len(label_row)] = torch.tensor(label_row, dtype=torch.long)
    return inputs.to(device), labels.to(device)


class TinyByteLanguageModel(nn.Module):
    def __init__(self, hidden_size: int = 48):
        super().__init__()
        self.embedding = nn.Embedding(259, hidden_size, padding_idx=PAD_ID)
        self.recurrent = nn.GRU(hidden_size, hidden_size, batch_first=True)
        self.norm = nn.LayerNorm(hidden_size)
        self.output = nn.Linear(hidden_size, 259)

    def forward(self, tokens: torch.Tensor) -> torch.Tensor:
        embedded = self.embedding(tokens)
        hidden, _ = self.recurrent(embedded)
        return self.output(self.norm(hidden))


def evaluate(model: nn.Module, examples: list[Example], max_sequence_length: int, device: torch.device, batch_size: int) -> float:
    model.eval()
    rows = [encode_example(example, max_sequence_length) for example in examples]
    losses: list[tuple[float, int]] = []
    with torch.no_grad():
        for offset in range(0, len(rows), batch_size):
            inputs, labels = batchify(rows[offset : offset + batch_size], device)
            logits = model(inputs)
            loss = nn.functional.cross_entropy(logits.reshape(-1, logits.size(-1)), labels.reshape(-1), ignore_index=IGNORE_INDEX, reduction="sum")
            count = int((labels != IGNORE_INDEX).sum().item())
            if count:
                losses.append((float(loss.item()), count))
    total_tokens = sum(count for _, count in losses)
    return sum(loss for loss, _ in losses) / max(total_tokens, 1)


def state_fingerprint(model: nn.Module) -> str:
    digest = hashlib.sha256()
    for name, tensor in sorted(model.state_dict().items()):
        digest.update(name.encode("utf-8"))
        digest.update(tensor.detach().cpu().contiguous().numpy().tobytes())
    return digest.hexdigest()


def atomic_torch_save(value: Any, path: Path) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    torch.save(value, temporary)
    os.replace(temporary, path)


def load_checkpoint(path: Path, model: nn.Module, optimizer: torch.optim.Optimizer, device: torch.device) -> int:
    # weights_only=True avoids arbitrary Python object unpickling from a checkpoint.
    checkpoint = torch.load(path, map_location=device, weights_only=True)
    if checkpoint.get("format_version") != FORMAT_VERSION:
        raise ValueError("Unsupported checkpoint format version.")
    model.load_state_dict(checkpoint["model_state_dict"], strict=True)
    optimizer.load_state_dict(checkpoint["optimizer_state_dict"])
    if isinstance(checkpoint.get("torch_rng_state"), torch.Tensor):
        torch.set_rng_state(checkpoint["torch_rng_state"].cpu())
    python_state = checkpoint.get("python_rng_state")
    if isinstance(python_state, list) and checkpoint.get("python_rng_version") is not None:
        random.setstate((int(checkpoint["python_rng_version"]), tuple(int(value) for value in python_state), checkpoint.get("python_rng_gauss")))
    cuda_state = checkpoint.get("cuda_rng_state_all")
    if torch.cuda.is_available() and isinstance(cuda_state, list) and cuda_state:
        torch.cuda.set_rng_state_all(cuda_state)
    return int(checkpoint["epoch"])


def available_ram_bytes() -> int | None:
    try:
        pages = os.sysconf("SC_AVPHYS_PAGES")
        page_size = os.sysconf("SC_PAGE_SIZE")
        return int(pages * page_size)
    except (AttributeError, OSError, ValueError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--resume-dir", default="", type=str)
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--hidden-size", type=int, default=48)
    parser.add_argument("--max-sequence-length", type=int, default=256)
    parser.add_argument("--max-examples", type=int, default=5000)
    parser.add_argument("--learning-rate", type=float, default=0.003)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--min-available-ram-mb", type=int, default=64)
    parser.add_argument("--min-free-disk-mb", type=int, default=128)
    parser.add_argument("--max-runtime-seconds", type=int, default=3600)
    args = parser.parse_args()
    if not (1 <= args.epochs <= 100 and 1 <= args.batch_size <= 64 and 16 <= args.hidden_size <= 512):
        parser.error("epochs, batch size, or hidden size is outside the safe configured range")
    if not (32 <= args.max_sequence_length <= 4096 and 1 <= args.max_examples <= 100_000 and 1e-6 <= args.learning_rate <= 0.1):
        parser.error("sequence length, dataset limit, or learning rate is outside the safe configured range")
    if not (16 <= args.min_available_ram_mb <= 1_000_000 and 16 <= args.min_free_disk_mb <= 1_000_000 and 1 <= args.max_runtime_seconds <= 7 * 24 * 3600):
        parser.error("resource limits are outside the safe configured range")

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
    if args.device == "cuda" and not torch.cuda.is_available():
        raise SystemExit("CUDA was requested but no CUDA-capable PyTorch device is available.")
    device = torch.device("cuda" if args.device == "cuda" or (args.device == "auto" and torch.cuda.is_available()) else "cpu")
    examples = read_approved_dataset(args.dataset, args.max_examples)
    train_examples, validation_examples, test_examples = split_dataset(examples, args.seed)
    train_rows = [encode_example(example, args.max_sequence_length) for example in train_examples]

    args.output_dir.mkdir(parents=True, exist_ok=True)
    free_disk = shutil.disk_usage(args.output_dir).free
    if free_disk < args.min_free_disk_mb * 1024 * 1024:
        raise SystemExit(f"Insufficient free disk space: {free_disk} bytes available")
    available_ram = available_ram_bytes()
    if available_ram is not None and available_ram < args.min_available_ram_mb * 1024 * 1024:
        raise SystemExit(f"Insufficient available RAM: {available_ram} bytes available")
    model = TinyByteLanguageModel(args.hidden_size).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    start_epoch = 0
    resume_path = Path(args.resume_dir) / "checkpoint-last.pt" if args.resume_dir else None
    if resume_path:
        if not resume_path.is_file():
            raise SystemExit(f"Resume requested but checkpoint is missing: {resume_path}")
        start_epoch = load_checkpoint(resume_path, model, optimizer, device)
    before_fingerprint = state_fingerprint(model)
    initial_train_loss = evaluate(model, train_examples, args.max_sequence_length, device, args.batch_size)
    initial_validation_loss = evaluate(model, validation_examples, args.max_sequence_length, device, args.batch_size)
    history: list[dict[str, float | int]] = []
    started = time.time()
    deadline = started + args.max_runtime_seconds
    if device.type == "cuda": torch.cuda.reset_peak_memory_stats(device)

    for epoch in range(start_epoch, start_epoch + args.epochs):
        model.train()
        order = list(range(len(train_rows)))
        random.shuffle(order)
        epoch_loss_sum = 0.0
        epoch_token_count = 0
        for offset in range(0, len(order), args.batch_size):
            if time.time() > deadline:
                raise TimeoutError("Configured trainer runtime limit reached; latest completed-epoch checkpoint remains available.")
            chosen = [train_rows[index] for index in order[offset : offset + args.batch_size]]
            inputs, labels = batchify(chosen, device)
            optimizer.zero_grad(set_to_none=True)
            logits = model(inputs)
            loss = nn.functional.cross_entropy(logits.reshape(-1, logits.size(-1)), labels.reshape(-1), ignore_index=IGNORE_INDEX)
            if not torch.isfinite(loss):
                raise RuntimeError("Non-finite training loss; stopping to avoid corrupting the checkpoint.")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
            optimizer.step()
            target_tokens = int((labels != IGNORE_INDEX).sum().item())
            epoch_loss_sum += float(loss.detach().item()) * target_tokens
            epoch_token_count += target_tokens
        train_loss = evaluate(model, train_examples, args.max_sequence_length, device, args.batch_size)
        validation_loss = evaluate(model, validation_examples, args.max_sequence_length, device, args.batch_size)
        record = {"epoch": epoch + 1, "train_loss": train_loss, "validation_loss": validation_loss,
                  "optimizer_train_loss": epoch_loss_sum / max(epoch_token_count, 1)}
        history.append(record)
        checkpoint = {
            "format_version": FORMAT_VERSION,
            "epoch": epoch + 1,
            "model_config": {"architecture": "TinyByteLanguageModel-GRU", "hidden_size": args.hidden_size, "vocab_size": 259},
            "model_state_dict": model.state_dict(),
            "optimizer_state_dict": optimizer.state_dict(),
            "torch_rng_state": torch.get_rng_state(),
            "python_rng_version": random.getstate()[0],
            "python_rng_state": list(random.getstate()[1]),
            "python_rng_gauss": random.getstate()[2],
            "cuda_rng_state_all": torch.cuda.get_rng_state_all() if torch.cuda.is_available() else [],
            "metrics": record,
            "dataset_sha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
            "seed": args.seed,
        }
        atomic_torch_save(checkpoint, args.output_dir / "checkpoint-last.pt")

    final_train_loss = evaluate(model, train_examples, args.max_sequence_length, device, args.batch_size)
    final_validation_loss = evaluate(model, validation_examples, args.max_sequence_length, device, args.batch_size)
    heldout_test_loss = evaluate(model, test_examples, args.max_sequence_length, device, args.batch_size)
    after_fingerprint = state_fingerprint(model)
    if before_fingerprint == after_fingerprint:
        raise RuntimeError("No model parameter changed; refusing to report a successful training run.")

    model_path = args.output_dir / "model-state.pt"
    atomic_torch_save({"format_version": FORMAT_VERSION, "model_config": {"architecture": "TinyByteLanguageModel-GRU", "hidden_size": args.hidden_size, "vocab_size": 259},
                       "model_state_dict": model.state_dict(), "dataset_sha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest()}, model_path)
    metrics = {
        "status": "COMPLETED",
        "experiment_type": "tiny-supervised-byte-language-model-from-scratch",
        "warning": "This is a small integration experiment, not fine-tuning of a foundation model and not evidence of broad intelligence improvement.",
        "device": str(device), "device_name": torch.cuda.get_device_name(device) if device.type == "cuda" else "CPU",
        "cuda_peak_memory_bytes": int(torch.cuda.max_memory_allocated(device)) if device.type == "cuda" else 0,
        "torch_version": torch.__version__, "python_version": sys.version.split()[0],
        "platform": platform.platform(), "available_ram_bytes_at_start": available_ram, "free_disk_bytes_at_start": free_disk,
        "max_runtime_seconds": args.max_runtime_seconds,
        "dataset_file_name": args.dataset.name, "dataset_sha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
        "example_counts": {"total": len(examples), "train": len(train_examples), "validation": len(validation_examples), "test": len(test_examples)},
        "epochs_requested": args.epochs, "epochs_completed": len(history), "resumed_from_epoch": start_epoch,
        "initial_train_loss": initial_train_loss, "final_train_loss": final_train_loss,
        "initial_validation_loss": initial_validation_loss, "final_validation_loss": final_validation_loss,
        "heldout_test_loss": heldout_test_loss, "train_loss_decreased": final_train_loss < initial_train_loss,
        "parameter_fingerprint_before": before_fingerprint, "parameter_fingerprint_after": after_fingerprint,
        "parameters_changed": before_fingerprint != after_fingerprint,
        "elapsed_seconds": round(time.time() - started, 3), "history": history,
        "artifacts": ["model-state.pt", "checkpoint-last.pt", "metrics.json"],
    }
    (args.output_dir / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metrics, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
