import argparse
import csv
import json
import os
import shutil
from pathlib import Path


TEXT_FIELDS = {
    "name": "crop_name_tight",
    "effect": "crop_effect_core",
    "card_number": "crop_set_number",
}

CLASSIFICATION_FIELDS = {
    "type": "crop_type_region",
    "domain": "crop_domain",
    "power_cost": "crop_card_power_cost",
    "set": "crop_set_number",
}

NUMERIC_FIELDS = {
    "cost": "crop_cost",
    "might": "crop_might",
}

FIELD_TO_PARSED_COLUMN = {
    "name": "parsed_name",
    "effect": "parsed_effect",
    "card_number": "parsed_card_number",
    "card_type_labels": "parsed_card_type_labels",
    "tags": "parsed_tags",
    "type": "parsed_type",
    "domain": "parsed_domain",
    "power_cost": "parsed_power_cost",
    "set": "parsed_set",
    "cost": "parsed_cost",
    "might": "parsed_might",
}


def parse_args():
    parser = argparse.ArgumentParser(
        description="Build training-ready OCR datasets from review crops and optional correction CSVs.",
    )
    parser.add_argument("review_dir", type=Path, help="Folder created by prepare_ocr_review_crops.py.")
    parser.add_argument("output_dir", type=Path, help="Where the training dataset should be written.")
    parser.add_argument(
        "--corrections",
        action="append",
        type=Path,
        default=[],
        help="Correction CSV. Can be provided multiple times. correct_* values override parsed values.",
    )
    parser.add_argument(
        "--only-corrected",
        action="store_true",
        help="Only export fields that have a non-empty correct_* value in a correction CSV.",
    )
    parser.add_argument(
        "--link-mode",
        choices=("hardlink", "copy"),
        default="hardlink",
        help="Hardlink saves disk space when source and output are on the same drive.",
    )
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.output_dir.exists():
        if not args.overwrite:
            raise SystemExit(f"{args.output_dir} already exists. Use --overwrite to replace it.")
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True)

    manifest_rows = read_csv(args.review_dir / "manifest.csv")
    corrections = load_corrections(args.corrections)

    text_writers = {}
    labels_jsonl = args.output_dir / "labels.jsonl"
    counts = {
        "cards": len(manifest_rows),
        "corrected_values": 0,
        "pseudo_values": 0,
        "skipped_empty": 0,
        "text": {},
        "classification": {},
        "numeric": {},
    }

    with labels_jsonl.open("w", encoding="utf-8") as jsonl:
        for row in manifest_rows:
            card_id = row["id"]
            correction = corrections.get(card_id, {})
            record = build_record(row, correction)
            jsonl.write(json.dumps(record, ensure_ascii=False) + "\n")

            for field, crop_column in TEXT_FIELDS.items():
                export_field(
                    args.review_dir,
                    args.output_dir,
                    row,
                    correction,
                    field,
                    crop_column,
                    "text",
                    text_writers,
                    counts,
                    args.only_corrected,
                    args.link_mode,
                )
            for field, crop_column in CLASSIFICATION_FIELDS.items():
                export_field(
                    args.review_dir,
                    args.output_dir,
                    row,
                    correction,
                    field,
                    crop_column,
                    "classification",
                    text_writers,
                    counts,
                    args.only_corrected,
                    args.link_mode,
                )
            for field, crop_column in NUMERIC_FIELDS.items():
                export_field(
                    args.review_dir,
                    args.output_dir,
                    row,
                    correction,
                    field,
                    crop_column,
                    "numeric",
                    text_writers,
                    counts,
                    args.only_corrected,
                    args.link_mode,
                )

    for handle, _writer in text_writers.values():
        handle.close()

    write_summary(args.output_dir, counts, args.review_dir, args.corrections, args.only_corrected)
    print(json.dumps(counts, indent=2, ensure_ascii=True))
    print(args.output_dir.resolve())


def read_csv(path: Path):
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def load_corrections(paths: list[Path]):
    corrections = {}
    for path in paths:
        if not path.exists():
            raise FileNotFoundError(path)
        for row in read_csv(path):
            card_id = row.get("id")
            if not card_id:
                continue
            current = corrections.setdefault(card_id, {})
            for key, value in row.items():
                if key.startswith("correct_") and normalize_value(value):
                    current[key] = normalize_value(value)
    return corrections


def build_record(row: dict, correction: dict):
    fields = {}
    sources = {}
    for field, parsed_column in FIELD_TO_PARSED_COLUMN.items():
        value, source = corrected_or_parsed(row, correction, field)
        fields[field] = value
        sources[field] = source
    return {
        "id": row["id"],
        "image": row["image"],
        "fields": fields,
        "sources": sources,
        "suspects": {
            "name": row.get("name_suspect") == "True",
            "might": row.get("might_suspect") == "True",
            "footer": row.get("footer_noise") == "True",
            "rotation": row.get("rotation_suspect") == "True",
        },
    }


def corrected_or_parsed(row: dict, correction: dict, field: str):
    corrected = normalize_value(correction.get(f"correct_{field}"))
    if corrected:
        return corrected, "corrected"
    parsed = normalize_value(row.get(FIELD_TO_PARSED_COLUMN[field]))
    return parsed, "parsed"


def export_field(
    review_dir: Path,
    output_dir: Path,
    row: dict,
    correction: dict,
    field: str,
    crop_column: str,
    group: str,
    text_writers: dict,
    counts: dict,
    only_corrected: bool,
    link_mode: str,
):
    value, source = corrected_or_parsed(row, correction, field)
    if only_corrected and source != "corrected":
        return
    if not value:
        counts["skipped_empty"] += 1
        return

    source_path = review_dir / row[crop_column]
    if not source_path.exists():
        return

    if source == "corrected":
        counts["corrected_values"] += 1
    else:
        counts["pseudo_values"] += 1

    if group == "text":
        destination = output_dir / "text" / field / "images" / f"{row['index'].zfill(4)}_{row['id']}_{field}.jpg"
        link_or_copy(source_path, destination, link_mode)
        labels_path = output_dir / "text" / field / "labels.csv"
        writer = get_writer(text_writers, labels_path, ["image", "label", "source", "id"])
        writer.writerow({
            "image": destination.relative_to(labels_path.parent).as_posix(),
            "label": value,
            "source": source,
            "id": row["id"],
        })
    else:
        label = safe_label(value)
        destination = output_dir / group / field / label / f"{row['index'].zfill(4)}_{row['id']}_{field}.jpg"
        link_or_copy(source_path, destination, link_mode)

    bucket = counts[group].setdefault(field, {})
    bucket[value] = bucket.get(value, 0) + 1


def get_writer(writers: dict, path: Path, fieldnames: list[str]):
    if path in writers:
        return writers[path][1]
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open("w", newline="", encoding="utf-8")
    writer = csv.DictWriter(handle, fieldnames=fieldnames)
    writer.writeheader()
    writers[path] = (handle, writer)
    return writer


def link_or_copy(source: Path, destination: Path, mode: str):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if mode == "copy":
        shutil.copy2(source, destination)
        return
    try:
        os.link(source, destination)
    except OSError:
        shutil.copy2(source, destination)


def normalize_value(value):
    if value is None:
        return ""
    return str(value).strip()


def safe_label(value: str):
    safe = normalize_value(value).lower()
    replacements = {
        "/": "_or_",
        "\\": "_",
        "|": "_",
        ":": "_",
        "*": "_",
        "?": "_",
        '"': "",
        "<": "_",
        ">": "_",
    }
    for old, new in replacements.items():
        safe = safe.replace(old, new)
    safe = "_".join(part for part in safe.replace("-", "_").replace(" ", "_").split("_") if part)
    return safe or "unknown"


def write_summary(output_dir: Path, counts: dict, review_dir: Path, corrections: list[Path], only_corrected: bool):
    summary = {
        "review_dir": str(review_dir),
        "corrections": [str(path) for path in corrections],
        "only_corrected": only_corrected,
        "counts": counts,
    }
    (output_dir / "dataset_summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    readme = f"""# Riftbound OCR Training Candidate

This folder was generated from `{review_dir}`.

It contains:

- `labels.jsonl`: one record per scanned card with parsed/corrected field values.
- `text/<field>/images` + `labels.csv`: image-to-text pairs for OCR-style training/evaluation.
- `classification/<field>/<label>`: classification datasets for type, domain, set, and power cost.
- `numeric/<field>/<label>`: simple visual classifier datasets for cost and might.

Label source rules:

- `correct_*` values from correction CSVs win.
- If a field has no correction, the parsed value is used as a pseudo-label.
- If this was generated with `--only-corrected`, pseudo-labels are excluded.

This dataset is useful for experiments, but pseudo-labels should not be treated as ground truth.
"""
    (output_dir / "README.md").write_text(readme, encoding="utf-8")


if __name__ == "__main__":
    main()
