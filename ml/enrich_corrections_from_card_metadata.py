import argparse
import csv
import json
import os
import re
import urllib.request
from pathlib import Path


NAME_KEYS = ("name", "card_name", "title")
NUMBER_KEYS = ("card_number", "cardNumber", "number", "collector_number", "collectorNumber")
TYPE_KEYS = ("card_type", "cardType", "type")
TYPE_LABEL_KEYS = ("card_type_labels", "cardTypeLabels", "type_labels", "typeLabels")
TAG_KEYS = ("tags", "tag", "subtypes", "labels")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Fill OCR correction CSV fields from an external card metadata export.",
    )
    parser.add_argument("corrections_csv", type=Path)
    parser.add_argument(
        "metadata_source",
        help="CSV, JSON, JSONL metadata file, URL, or Hugging Face source such as hf:Wysme/riftbound-cards.",
    )
    parser.add_argument("output_csv", type=Path)
    return parser.parse_args()


def main():
    args = parse_args()
    corrections = read_csv(args.corrections_csv)
    metadata_rows = read_metadata(args.metadata_source)
    by_name, by_number = index_metadata(metadata_rows)

    matched = 0
    fieldnames = list(corrections[0].keys()) if corrections else []
    for field in ("parsed_card_type_labels", "correct_card_type_labels", "parsed_tags", "correct_tags"):
        if field not in fieldnames:
            fieldnames.insert(fieldnames.index("parsed_domain") if "parsed_domain" in fieldnames else len(fieldnames), field)

    for row in corrections:
        metadata = find_metadata(row, by_name, by_number)
        if metadata is None:
            continue
        matched += 1
        fill_if_empty(row, "correct_type", first_value(metadata, TYPE_KEYS))
        fill_if_empty(row, "correct_card_type_labels", first_value(metadata, TYPE_LABEL_KEYS))
        fill_if_empty(row, "correct_tags", first_value(metadata, TAG_KEYS))

    args.output_csv.parent.mkdir(parents=True, exist_ok=True)
    with args.output_csv.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(corrections)

    print(f"metadata rows: {len(metadata_rows)}")
    print(f"correction rows: {len(corrections)}")
    print(f"matched rows: {matched}")
    print(args.output_csv.resolve())


def read_csv(path: Path):
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def read_metadata(source: str):
    if source.startswith("hf:"):
        return read_hugging_face_dataset(source.removeprefix("hf:"))
    if source.startswith("http://") or source.startswith("https://"):
        text = read_url_text(source)
        return read_metadata_text(text, source)

    path = Path(source)
    suffix = path.suffix.lower()
    if suffix == ".csv":
        return read_csv(path)
    if suffix == ".jsonl":
        return read_jsonl_text(path.read_text(encoding="utf-8"))
    if suffix == ".json":
        return rows_from_json(json.loads(path.read_text(encoding="utf-8")))
    raise ValueError(f"Unsupported metadata format: {path}")


def read_hugging_face_dataset(dataset_id: str):
    url = f"https://huggingface.co/datasets/{dataset_id}/resolve/main/cards.json"
    return rows_from_json(json.loads(read_url_text(url)))


def read_url_text(url: str):
    request = urllib.request.Request(url)
    token = os.environ.get("HF_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8")


def read_metadata_text(text: str, source_name: str):
    if source_name.lower().endswith(".jsonl"):
        return read_jsonl_text(text)
    if source_name.lower().endswith(".json"):
        return rows_from_json(json.loads(text))
    raise ValueError(f"Unsupported remote metadata format: {source_name}")


def read_jsonl_text(text: str):
    return [
        json.loads(line)
        for line in text.splitlines()
        if line.strip()
    ]


def rows_from_json(data):
    if isinstance(data, list):
        return data
    for key in ("data", "items", "cards", "rows"):
        value = data.get(key) if isinstance(data, dict) else None
        if isinstance(value, list):
            return value
    raise ValueError("JSON metadata must be a list or contain a list under data/items/cards/rows.")


def index_metadata(rows: list[dict]):
    by_name = {}
    by_number = {}
    for row in rows:
        name = normalize_name(first_value(row, NAME_KEYS))
        number = normalize_card_number(first_value(row, NUMBER_KEYS))
        if name:
            by_name.setdefault(name, []).append(row)
        if number:
            by_number.setdefault(number, []).append(row)
    return by_name, by_number


def find_metadata(row: dict, by_name: dict, by_number: dict):
    number = normalize_card_number(row.get("correct_card_number") or row.get("parsed_card_number"))
    name = normalize_name(row.get("correct_name") or row.get("parsed_name"))

    if number and number in by_number:
        matches = by_number[number]
        if name:
            named = [candidate for candidate in matches if normalize_name(first_value(candidate, NAME_KEYS)) == name]
            if named:
                return named[0]
        return matches[0]

    if name and name in by_name:
        return by_name[name][0]

    return None


def first_value(row: dict, keys: tuple[str, ...]):
    for key in keys:
        if key in row and row[key] not in (None, ""):
            return normalize_scalar(row[key])
    return ""


def normalize_scalar(value):
    if isinstance(value, list):
        return " | ".join(str(item).strip() for item in value if str(item).strip())
    if isinstance(value, dict):
        return " | ".join(f"{key}:{value[key]}" for key in sorted(value))
    return str(value).strip()


def fill_if_empty(row: dict, key: str, value: str):
    if value and not str(row.get(key) or "").strip():
        row[key] = value


def normalize_name(value: str):
    return re.sub(r"[^a-z0-9]+", " ", str(value or "").lower()).strip()


def normalize_card_number(value: str):
    value = str(value or "").strip().lower()
    value = value.replace("\\", "/")
    match = re.search(r"\d{1,4}[a-z]?\s*/\s*\d{1,4}[a-z]?", value)
    if match:
        return re.sub(r"\s*/\s*", "/", match.group(0))
    return re.sub(r"[^a-z0-9/]+", "", value)


if __name__ == "__main__":
    main()
