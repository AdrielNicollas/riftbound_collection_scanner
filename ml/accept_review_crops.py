import argparse
import csv
import shutil
from pathlib import Path

from PIL import Image, ImageOps


LABELS = ("body", "calm", "chaos", "fury", "mind", "order")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Copy accepted review crops into a prepared Riftbound domain dataset.",
    )
    parser.add_argument("review_dir", type=Path, help="Folder created by prepare_domain_dataset.py.")
    parser.add_argument("output_dir", type=Path, help="Where accepted crops or the merged dataset will be written.")
    parser.add_argument(
        "--base-dataset",
        type=Path,
        default=None,
        help="Optional prepared dataset to copy before adding accepted review crops.",
    )
    parser.add_argument(
        "--clear",
        action="store_true",
        help="Delete output_dir before writing.",
    )
    parser.add_argument(
        "--include-empty",
        action="store_true",
        help="Also copy crops that look almost empty.",
    )
    return parser.parse_args()


def has_visible_content(image_path: Path) -> bool:
    with Image.open(image_path) as image:
        image = ImageOps.exif_transpose(image).convert("RGB").resize((80, 80))
        pixels = list(image.getdata())

    visible_pixels = 0
    for red, green, blue in pixels:
        brightness = max(red, green, blue)
        contrast = brightness - min(red, green, blue)
        if brightness > 30 or contrast > 18:
            visible_pixels += 1

    return visible_pixels / len(pixels) >= 0.02


def copy_base_dataset(base_dataset: Path, output_dir: Path, rows: list[dict]):
    for label in LABELS:
        source_label_dir = base_dataset / label
        output_label_dir = output_dir / label
        output_label_dir.mkdir(parents=True, exist_ok=True)
        if not source_label_dir.exists():
            continue

        for source_file in sorted(source_label_dir.glob("*.jpg")):
            output_file = output_label_dir / source_file.name
            shutil.copy2(source_file, output_file)
            rows.append(
                {
                    "label": label,
                    "kind": "base",
                    "source": str(source_file),
                    "output": str(output_file),
                    "status": "copied",
                },
            )


def copy_review_crops(review_dir: Path, output_dir: Path, include_empty: bool, rows: list[dict]):
    accepted = 0
    skipped = 0

    for label in LABELS:
        source_label_dir = review_dir / label
        output_label_dir = output_dir / label
        output_label_dir.mkdir(parents=True, exist_ok=True)
        if not source_label_dir.exists():
            continue

        for index, source_file in enumerate(sorted(source_label_dir.glob("*_crop.jpg")), start=1):
            is_visible = has_visible_content(source_file)
            if not include_empty and not is_visible:
                skipped += 1
                rows.append(
                    {
                        "label": label,
                        "kind": "review",
                        "source": str(source_file),
                        "output": "",
                        "status": "skipped_empty",
                    },
                )
                continue

            output_file = output_label_dir / f"{label}_review_{index:04d}.jpg"
            shutil.copy2(source_file, output_file)
            accepted += 1
            rows.append(
                {
                    "label": label,
                    "kind": "review",
                    "source": str(source_file),
                    "output": str(output_file),
                    "status": "copied",
                },
            )

    return accepted, skipped


def write_report(output_dir: Path, rows: list[dict]):
    report_file = output_dir / "accepted_review_report.csv"
    with report_file.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["label", "kind", "source", "output", "status"])
        writer.writeheader()
        writer.writerows(rows)
    return report_file


def main():
    args = parse_args()
    if args.clear and args.output_dir.exists():
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    rows = []
    if args.base_dataset is not None:
        copy_base_dataset(args.base_dataset, args.output_dir, rows)

    accepted, skipped = copy_review_crops(args.review_dir, args.output_dir, args.include_empty, rows)
    report_file = write_report(args.output_dir, rows)

    print(f"accepted review crops: {accepted}")
    print(f"skipped empty crops: {skipped}")
    if args.base_dataset is not None:
        base_count = sum(1 for row in rows if row["kind"] == "base")
        print(f"copied base crops: {base_count}")
    print(f"wrote {report_file}")


if __name__ == "__main__":
    main()
