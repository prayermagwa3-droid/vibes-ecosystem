#!/usr/bin/env python3
"""Optional real LoRA/QLoRA supervised fine-tuning worker for compatible causal LMs.

Requires separately installed transformers, peft, accelerate and (for --qlora) bitsandbytes.
The base model must be local unless --allow-download is explicitly supplied. The script accepts
only ALIS-approved JSONL rows and masks prompt tokens so the loss is applied to target tokens.
"""
from __future__ import annotations
import argparse
import hashlib
import inspect
import json
import os
from pathlib import Path

try:
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
except ImportError as exc:  # pragma: no cover - optional dependency path
    raise SystemExit("Install compatible torch, transformers, peft and accelerate packages first. For QLoRA also install bitsandbytes. Missing dependency: " + str(exc)) from exc


def content_hash(task: str, prompt: str, target: str) -> str:
    normalized = "\0".join(" ".join(value.lower().split()) for value in (task, prompt, target))
    return hashlib.sha256(normalized.encode()).hexdigest()


def load_rows(path: Path, max_examples: int) -> list[dict[str, str]]:
    rows, ids, hashes = [], set(), set()
    with path.open(encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            for key in ("id", "task_type", "input", "target", "content_hash", "approved_by"):
                if not isinstance(row.get(key), str) or not row[key].strip():
                    raise ValueError(f"Row {line_no} lacks required field/approval: {key}")
            expected = content_hash(row["task_type"], row["input"], row["target"])
            if expected != row["content_hash"].lower():
                raise ValueError(f"Row {line_no} has a mismatched content hash")
            if row["id"] in ids or expected in hashes:
                raise ValueError(f"Row {line_no} duplicates an ID or normalized training example")
            ids.add(row["id"]); hashes.add(expected); rows.append(row)
            if len(rows) > max_examples:
                raise ValueError("Dataset exceeds --max-examples")
    if len(rows) < 5:
        raise ValueError("At least five approved examples are required for train/validation/test splits")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--base-model", required=True)
    parser.add_argument("--resume-dir", default="")
    parser.add_argument("--allow-download", action="store_true", help="Explicitly allow the model/tokenizer to be downloaded")
    parser.add_argument("--qlora", action="store_true", help="Use 4-bit quantization; requires CUDA and bitsandbytes")
    parser.add_argument("--max-examples", type=int, default=50000)
    parser.add_argument("--max-length", type=int, default=1024)
    parser.add_argument("--epochs", type=float, default=1.0)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--gradient-accumulation", type=int, default=8)
    parser.add_argument("--lora-rank", type=int, default=8)
    parser.add_argument("--lora-alpha", type=int, default=16)
    parser.add_argument("--target-modules", default="q_proj,v_proj")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    if args.max_length not in range(64, 8193) or args.max_examples not in range(5, 1_000_001):
        parser.error("max length or max examples is outside the allowed range")
    if not (0.000001 <= args.learning_rate <= 0.01 and 0.1 <= args.epochs <= 100):
        parser.error("learning rate or epoch count is outside the allowed range")
    if args.qlora and not torch.cuda.is_available():
        raise SystemExit("QLoRA requires a supported CUDA device in this trainer. Use LoRA on CPU or a compatible backend instead.")

    rows = load_rows(args.dataset, args.max_examples)
    # Deterministic split by stable row ID; each split is kept separate for evaluation.
    rows.sort(key=lambda row: hashlib.sha256(f"{args.seed}:{row['id']}".encode()).hexdigest())
    test_n = max(1, round(len(rows) * 0.1)); val_n = max(1, round(len(rows) * 0.1))
    while len(rows) - test_n - val_n < 1:
        if test_n > 1: test_n -= 1
        elif val_n > 1: val_n -= 1
        else: break
    train_rows = rows[:len(rows)-test_n-val_n]
    val_rows = rows[len(rows)-test_n-val_n:len(rows)-test_n]
    test_rows = rows[len(rows)-test_n:]

    local_only = not args.allow_download
    tokenizer = AutoTokenizer.from_pretrained(args.base_model, local_files_only=local_only, use_fast=True)
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    load_kwargs = {"local_files_only": local_only}
    if args.qlora:
        from transformers import BitsAndBytesConfig
        load_kwargs.update({
            "quantization_config": BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                bnb_4bit_use_double_quant=True, bnb_4bit_compute_dtype=torch.float16),
            "device_map": "auto",
        })
    model = AutoModelForCausalLM.from_pretrained(args.base_model, **load_kwargs)
    if args.qlora:
        model = prepare_model_for_kbit_training(model)
    targets = [value.strip() for value in args.target_modules.split(",") if value.strip()]
    if not targets:
        parser.error("At least one --target-modules name is required")
    model = get_peft_model(model, LoraConfig(r=args.lora_rank, lora_alpha=args.lora_alpha, lora_dropout=0.05,
        bias="none", task_type="CAUSAL_LM", target_modules=targets))
    model.print_trainable_parameters()

    def tokenize(row: dict[str, str]) -> dict[str, list[int]]:
        prompt = f"Task: {row['task_type']}\nInstruction: {row['input']}\nAnswer:"
        prompt_ids = tokenizer(prompt, add_special_tokens=True, truncation=True, max_length=args.max_length).input_ids
        target_ids = tokenizer(row["target"], add_special_tokens=False).input_ids
        if tokenizer.eos_token_id is not None:
            target_ids.append(tokenizer.eos_token_id)
        target_ids = target_ids[:max(1, args.max_length - 1)]
        prompt_ids = prompt_ids[:max(1, args.max_length - len(target_ids))]
        input_ids = prompt_ids + target_ids
        labels = [-100] * len(prompt_ids) + target_ids.copy()
        pad = args.max_length - len(input_ids)
        return {"input_ids": input_ids + [tokenizer.pad_token_id] * pad,
                "attention_mask": [1] * len(input_ids) + [0] * pad,
                "labels": labels + [-100] * pad}

    class SimpleDataset(torch.utils.data.Dataset):
        def __init__(self, source: list[dict[str, str]]): self.rows = [tokenize(row) for row in source]
        def __len__(self): return len(self.rows)
        def __getitem__(self, index): return {key: torch.tensor(value, dtype=torch.long) for key, value in self.rows[index].items()}

    args.output_dir.mkdir(parents=True, exist_ok=True)
    train_args = TrainingArguments(
        output_dir=str(args.output_dir), num_train_epochs=args.epochs, learning_rate=args.learning_rate,
        per_device_train_batch_size=args.batch_size, per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.gradient_accumulation, logging_strategy="epoch", save_strategy="epoch",
        save_total_limit=2, report_to=[], seed=args.seed, data_seed=args.seed,
        remove_unused_columns=False, dataloader_pin_memory=torch.cuda.is_available(),
        fp16=bool(torch.cuda.is_available() and not torch.cuda.is_bf16_supported()),
        bf16=bool(torch.cuda.is_available() and torch.cuda.is_bf16_supported()),
    )
    trainer_kwargs = {"model": model, "args": train_args, "train_dataset": SimpleDataset(train_rows),
                      "eval_dataset": SimpleDataset(val_rows)}
    # Transformers renamed Trainer(tokenizer=...) to processing_class in newer releases.
    if "processing_class" in inspect.signature(Trainer.__init__).parameters:
        trainer_kwargs["processing_class"] = tokenizer
    else:
        trainer_kwargs["tokenizer"] = tokenizer
    trainer = Trainer(**trainer_kwargs)
    train_result = trainer.train(resume_from_checkpoint=args.resume_dir or None)
    eval_metrics = trainer.evaluate(eval_dataset=SimpleDataset(test_rows), metric_key_prefix="heldout_test")
    trainer.save_model(str(args.output_dir / "adapter"))
    tokenizer.save_pretrained(args.output_dir / "adapter")
    report = {
        "status": "COMPLETED", "method": "LoRA" if not args.qlora else "QLoRA",
        "base_model": args.base_model, "dataset_sha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
        "counts": {"total": len(rows), "train": len(train_rows), "validation": len(val_rows), "heldout_test": len(test_rows)},
        "train_metrics": train_result.metrics, "heldout_test_metrics": eval_metrics,
        "trainable_parameters": sum(p.numel() for p in model.parameters() if p.requires_grad),
        "total_parameters": sum(p.numel() for p in model.parameters()),
        "device": str(train_args.device), "torch_version": torch.__version__,
        "warning": "This is adapter fine-tuning, not foundation-model pretraining. Held-out metrics are task/dataset specific and require review before promotion.",
    }
    (args.output_dir / "alis-training-report.json").write_text(json.dumps(report, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, default=str))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
