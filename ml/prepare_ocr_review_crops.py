import argparse
import csv
import html
import json
import shutil
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageOps, ImageStat


@dataclass(frozen=True)
class CropZone:
    name: str
    box: tuple[float, float, float, float]
    square: bool = False


ZONES = [
    CropZone("cost", (0.035, 0.025, 0.205, 0.155), square=True),
    CropZone("card_power_cost", (0.015, 0.095, 0.155, 0.335)),
    CropZone("power_cost", (0.035, 0.125, 0.170, 0.245), square=True),
    CropZone("might", (0.760, 0.025, 0.975, 0.160)),
    CropZone("title_band", (0.085, 0.455, 0.850, 0.630)),
    CropZone("type_region", (0.090, 0.455, 0.825, 0.535)),
    CropZone("name", (0.100, 0.500, 0.825, 0.620)),
    CropZone("name_tight", (0.115, 0.515, 0.805, 0.575)),
    CropZone("subtitle", (0.115, 0.555, 0.805, 0.630)),
    CropZone("effect", (0.100, 0.585, 0.900, 0.835)),
    CropZone("effect_core", (0.100, 0.625, 0.900, 0.790)),
    CropZone("lore_box", (0.055, 0.765, 0.930, 0.910)),
    CropZone("lore_marker", (0.055, 0.765, 0.145, 0.845), square=True),
    CropZone("lore", (0.100, 0.800, 0.900, 0.915)),
    CropZone("set_number", (0.035, 0.900, 0.345, 0.985)),
    CropZone("artist", (0.500, 0.900, 0.880, 0.985)),
    CropZone("domain", (0.870, 0.870, 0.985, 0.985), square=True),
]

TEXT_ZONE_NAMES = {
    "title_band",
    "type_region",
    "name",
    "name_tight",
    "subtitle",
    "effect",
    "effect_core",
    "lore_box",
    "lore",
    "set_number",
    "artist",
}

NAME_METADATA_WORDS = (
    "unit",
    "spell",
    "gear",
    "champion",
    "signature",
    "equipment",
    "eouipment",
    "1onia",
    "10nia",
    "tonia",
    "nokus",
    "freljordporo",
)

FOOTER_NOISE_WORDS = (
    "studio",
    "productions",
    "copyright",
    "2025",
    "2026",
    "20p6",
    "2925",
    "kudos",
    "splash",
    "paindart",
    "polar engine",
    "dark glow",
)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Create review crops from a Riftbound OCR dataset export.",
    )
    parser.add_argument("dataset_dir", type=Path, help="Folder containing scans.json and images/.")
    parser.add_argument("output_dir", type=Path, help="Where crops and review sheets will be written.")
    parser.add_argument("--sheet-samples", type=int, default=40)
    parser.add_argument("--enhanced", action="store_true", help="Also write OCR preprocessing variants for text crops.")
    parser.add_argument("--symbol-candidates", action="store_true", help="Extract likely inline symbol crops from effect text.")
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.output_dir.exists():
        if not args.overwrite:
            raise SystemExit(f"{args.output_dir} already exists. Use --overwrite to replace it.")
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True)

    items = load_items(args.dataset_dir)
    rows = []
    review_groups: dict[str, list[tuple[dict, Path]]] = {
        "name_suspects": [],
        "might_suspects": [],
        "footer_noise": [],
        "lore_detected": [],
        "rotation_suspects": [],
    }

    for index, item in enumerate(items, start=1):
        fields = item["fields"]
        image_path = args.dataset_dir / item["image"]
        if not image_path.exists():
            continue

        with Image.open(image_path) as raw_image:
            image = ImageOps.exif_transpose(raw_image).convert("RGB")
            crop_paths = write_zone_crops(args.output_dir, index, item, image)
            if args.enhanced:
                write_enhanced_text_crops(args.output_dir, index, item)
            if args.symbol_candidates:
                write_symbol_candidates(args.output_dir, index, item, image)
            write_pseudo_label_crops(args.output_dir, index, item, image)
            rotation_score = score_rotation(image)
            lore_score = score_lore_marker(image)
            row = manifest_row(index, item, image_path, crop_paths, rotation_score, lore_score)
            rows.append(row)

            if is_name_suspect(fields):
                review_groups["name_suspects"].append((item, image_path))
            if is_might_suspect(fields):
                review_groups["might_suspects"].append((item, image_path))
            if has_footer_noise(fields):
                review_groups["footer_noise"].append((item, image_path))
            if lore_score["has_lore_marker"]:
                review_groups["lore_detected"].append((item, image_path))
            if rotation_score["suspect"]:
                review_groups["rotation_suspects"].append((item, image_path))

    write_manifest(args.output_dir, rows)
    write_correction_templates(args.output_dir, rows)
    write_review_instructions(args.output_dir)
    write_summary(args.output_dir, items, rows, review_groups)
    write_zone_contact_sheets(args.output_dir, args.sheet_samples)
    if args.enhanced:
        write_enhanced_contact_sheets(args.output_dir, args.sheet_samples)
    if args.symbol_candidates:
        write_symbol_candidate_sheet(args.output_dir, args.sheet_samples * 3)
    write_review_panels(args.output_dir, review_groups)
    write_html_report(args.output_dir, rows, review_groups)

    print(f"items: {len(items)}")
    print(f"manifest rows: {len(rows)}")
    for name, group in review_groups.items():
        print(f"{name}: {len(group)}")
    print(args.output_dir.resolve())


def load_items(dataset_dir: Path):
    data = json.loads((dataset_dir / "scans.json").read_text(encoding="utf-8"))
    return data["items"]


def write_zone_crops(output_dir: Path, index: int, item: dict, image: Image.Image):
    crop_paths = {}
    for zone in ZONES:
        crop = crop_fraction(image, zone.box)
        if zone.square:
            crop = ImageOps.pad(crop, (240, 240), color=(0, 0, 0), centering=(0.5, 0.5))
        file_name = f"{index:04d}_{item['id']}_{zone.name}.jpg"
        output_path = output_dir / "zones" / zone.name / file_name
        output_path.parent.mkdir(parents=True, exist_ok=True)
        crop.save(output_path, quality=95)
        crop_paths[zone.name] = output_path.relative_to(output_dir).as_posix()
    return crop_paths


def write_enhanced_text_crops(output_dir: Path, index: int, item: dict):
    for zone_name in TEXT_ZONE_NAMES:
        source_path = output_dir / "zones" / zone_name / f"{index:04d}_{item['id']}_{zone_name}.jpg"
        if not source_path.exists():
            continue
        with Image.open(source_path) as raw_crop:
            crop = raw_crop.convert("RGB")
            for variant_name, variant in enhanced_variants(crop).items():
                output_path = (
                    output_dir
                    / "ocr_enhanced"
                    / zone_name
                    / variant_name
                    / f"{index:04d}_{item['id']}_{zone_name}_{variant_name}.jpg"
                )
                output_path.parent.mkdir(parents=True, exist_ok=True)
                variant.save(output_path, quality=95)


def enhanced_variants(image: Image.Image):
    gray = ImageOps.grayscale(image)
    autocontrast = ImageOps.autocontrast(gray, cutoff=1)
    high_contrast = ImageEnhance.Contrast(autocontrast).enhance(1.8)
    sharp = ImageEnhance.Sharpness(high_contrast).enhance(2.0)
    binary = high_contrast.point(lambda value: 255 if value > 150 else 0)
    inverted_binary = ImageOps.invert(binary)
    return {
        "gray": gray.convert("RGB"),
        "contrast": high_contrast.convert("RGB"),
        "sharp": sharp.convert("RGB"),
        "binary": binary.convert("RGB"),
        "binary_inverted": inverted_binary.convert("RGB"),
    }


def write_pseudo_label_crops(output_dir: Path, index: int, item: dict, image: Image.Image):
    fields = item["fields"]
    label_specs = [
        ("domain", fields.get("domain"), "domain"),
        ("type", fields.get("type"), "type_region"),
        ("power_cost", fields.get("powerCost"), "card_power_cost"),
        ("set", fields.get("set"), "set_number"),
    ]
    zones_by_name = {zone.name: zone for zone in ZONES}
    for group, raw_label, zone_name in label_specs:
        label = safe_label(raw_label)
        if not label:
            continue
        zone = zones_by_name[zone_name]
        crop = crop_fraction(image, zone.box)
        if zone.square:
            crop = ImageOps.pad(crop, (240, 240), color=(0, 0, 0), centering=(0.5, 0.5))
        output_path = output_dir / "pseudo_labels" / group / label / f"{index:04d}_{item['id']}_{zone_name}.jpg"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        crop.save(output_path, quality=95)


def write_symbol_candidates(output_dir: Path, index: int, item: dict, image: Image.Image):
    effect_zone = next(zone for zone in ZONES if zone.name == "effect_core")
    effect = crop_fraction(image, effect_zone.box)
    for group, candidates in {
        "colored": find_symbol_candidate_boxes(effect, mode="colored"),
        "dark_round": find_symbol_candidate_boxes(effect, mode="dark_round"),
    }.items():
        for candidate_index, box in enumerate(candidates[:12], start=1):
            crop = crop_square_around(effect, box, padding=20)
            crop = ImageOps.pad(crop, (160, 160), color=(0, 0, 0), centering=(0.5, 0.5))
            output_path = (
                output_dir
                / "symbol_candidates"
                / group
                / f"{index:04d}_{item['id']}_{group}_{candidate_index:02d}.jpg"
            )
            output_path.parent.mkdir(parents=True, exist_ok=True)
            crop.save(output_path, quality=95)


def find_symbol_candidate_boxes(image: Image.Image, mode: str):
    small = ImageOps.contain(image.convert("RGB"), (900, 320))
    scale_x = image.width / small.width
    scale_y = image.height / small.height
    arr = np.asarray(small)
    rgb = arr.astype(np.int16)
    max_channel = rgb.max(axis=2)
    min_channel = rgb.min(axis=2)
    saturation = np.divide(
        max_channel - min_channel,
        np.maximum(max_channel, 1),
        dtype=np.float32,
    )
    gray = (rgb[:, :, 0] * 0.299 + rgb[:, :, 1] * 0.587 + rgb[:, :, 2] * 0.114)

    if mode == "colored":
        mask = (saturation > 0.30) & (max_channel > 85)
    elif mode == "dark_round":
        mask = gray < 58
    else:
        raise ValueError(f"Unknown symbol candidate mode: {mode}")
    boxes = connected_component_boxes(mask)
    filtered = []
    for left, top, right, bottom, area in boxes:
        width = right - left
        height = bottom - top
        if mode == "colored":
            if width < 7 or height < 7 or width > 75 or height > 75:
                continue
            min_fill = 0.07
            min_area = 35
            aspect_min = 0.40
            aspect_max = 2.40
        else:
            if width < 12 or height < 12 or width > 70 or height > 70:
                continue
            min_fill = 0.18
            min_area = 130
            aspect_min = 0.55
            aspect_max = 1.80
        if area < min_area:
            continue
        aspect = width / max(height, 1)
        if aspect < aspect_min or aspect > aspect_max:
            continue
        fill = area / max(width * height, 1)
        if fill < min_fill:
            continue
        scaled_box = (
            round(left * scale_x),
            round(top * scale_y),
            round(right * scale_x),
            round(bottom * scale_y),
            area,
        )
        filtered.append(scaled_box)

    return merge_nearby_boxes(filtered)


def connected_component_boxes(mask):
    height, width = mask.shape
    visited = np.zeros_like(mask, dtype=bool)
    boxes = []
    for start_y in range(height):
        for start_x in range(width):
            if visited[start_y, start_x] or not mask[start_y, start_x]:
                continue
            stack = [(start_x, start_y)]
            visited[start_y, start_x] = True
            left = right = start_x
            top = bottom = start_y
            area = 0
            while stack:
                x, y = stack.pop()
                area += 1
                left = min(left, x)
                right = max(right, x + 1)
                top = min(top, y)
                bottom = max(bottom, y + 1)
                for nx in (x - 1, x, x + 1):
                    for ny in (y - 1, y, y + 1):
                        if nx < 0 or ny < 0 or nx >= width or ny >= height:
                            continue
                        if visited[ny, nx] or not mask[ny, nx]:
                            continue
                        visited[ny, nx] = True
                        stack.append((nx, ny))
            boxes.append((left, top, right, bottom, area))
    return boxes


def merge_nearby_boxes(boxes):
    merged = []
    for box in sorted(boxes, key=lambda item: (item[1], item[0])):
        left, top, right, bottom, area = box
        matched_index = None
        for index, existing in enumerate(merged):
            if boxes_overlap_or_touch((left, top, right, bottom), existing[:4], margin=8):
                matched_index = index
                break
        if matched_index is None:
            merged.append(box)
        else:
            old = merged[matched_index]
            merged[matched_index] = (
                min(old[0], left),
                min(old[1], top),
                max(old[2], right),
                max(old[3], bottom),
                old[4] + area,
            )
    return [
        box for box in merged
        if (box[2] - box[0]) <= 140 and (box[3] - box[1]) <= 140
    ]


def boxes_overlap_or_touch(first, second, margin):
    return not (
        first[2] + margin < second[0]
        or second[2] + margin < first[0]
        or first[3] + margin < second[1]
        or second[3] + margin < first[1]
    )


def crop_square_around(image: Image.Image, box, padding: int):
    left, top, right, bottom, _ = box
    center_x = (left + right) / 2
    center_y = (top + bottom) / 2
    size = max(right - left, bottom - top) + padding * 2
    left = round(center_x - size / 2)
    top = round(center_y - size / 2)
    right = left + round(size)
    bottom = top + round(size)
    left = max(0, left)
    top = max(0, top)
    right = min(image.width, right)
    bottom = min(image.height, bottom)
    return image.crop((left, top, right, bottom))


def safe_label(value):
    if value is None:
        return ""
    text = str(value).strip().lower()
    if not text:
        return ""
    return "".join(char if char.isalnum() else "_" for char in text).strip("_")


def crop_fraction(image: Image.Image, box: tuple[float, float, float, float]) -> Image.Image:
    width, height = image.size
    left, top, right, bottom = box
    pixel_box = (
        round(width * left),
        round(height * top),
        round(width * right),
        round(height * bottom),
    )
    return image.crop(pixel_box)


def score_rotation(image: Image.Image):
    top_score = white_ratio(crop_fraction(image, (0.035, 0.025, 0.205, 0.155)))
    top_score += white_ratio(crop_fraction(image, (0.760, 0.025, 0.975, 0.160)))
    bottom_score = white_ratio(crop_fraction(image, (0.795, 0.845, 0.965, 0.975)))
    bottom_score += white_ratio(crop_fraction(image, (0.025, 0.845, 0.240, 0.975)))
    return {
        "top_score": round(top_score, 4),
        "bottom_score": round(bottom_score, 4),
        "suspect": bottom_score > max(0.08, top_score * 1.25),
    }


def white_ratio(image: Image.Image):
    gray = image.convert("L")
    stat = ImageStat.Stat(gray)
    if stat.mean[0] < 35:
        return 0.0
    pixels = np.asarray(gray.resize((80, 80)))
    return float(np.count_nonzero(pixels >= 190) / pixels.size)


def score_lore_marker(image: Image.Image):
    marker_zone = next(zone for zone in ZONES if zone.name == "lore_marker")
    marker = crop_fraction(image, marker_zone.box).convert("RGB")
    arr = np.asarray(marker).astype(np.float32) / 255.0
    max_channel = arr.max(axis=2)
    min_channel = arr.min(axis=2)
    saturation = np.divide(
        max_channel - min_channel,
        max_channel,
        out=np.zeros_like(max_channel),
        where=max_channel != 0,
    )
    value = max_channel
    colored = (saturation >= 0.28) & (value >= 0.25)
    bright_colored_ratio = float(np.count_nonzero(colored) / colored.size)
    mean_saturation = float(saturation.mean())
    score = bright_colored_ratio * 0.75 + mean_saturation * 0.25
    return {
        "score": round(score, 4),
        "colored_ratio": round(bright_colored_ratio, 4),
        "mean_saturation": round(mean_saturation, 4),
        "has_lore_marker": score >= 0.12 and bright_colored_ratio >= 0.08,
    }


def manifest_row(index: int, item: dict, image_path: Path, crop_paths: dict, rotation_score: dict, lore_score: dict):
    fields = item["fields"]
    return {
        "index": index,
        "id": item["id"],
        "image": item["image"],
        "image_file": image_path.name,
        "parsed_name": fields.get("name", ""),
        "parsed_type": fields.get("type", ""),
        "parsed_domain": fields.get("domain", ""),
        "parsed_cost": fields.get("cost"),
        "parsed_power_cost": fields.get("powerCost", ""),
        "parsed_might": fields.get("might"),
        "parsed_effect": fields.get("effect", ""),
        "parsed_card_number": fields.get("cardNumber", ""),
        "parsed_set": fields.get("set", ""),
        "name_suspect": is_name_suspect(fields),
        "might_suspect": is_might_suspect(fields),
        "footer_noise": has_footer_noise(fields),
        "has_lore_marker": lore_score["has_lore_marker"],
        "lore_marker_score": lore_score["score"],
        "lore_marker_colored_ratio": lore_score["colored_ratio"],
        "lore_marker_mean_saturation": lore_score["mean_saturation"],
        "rotation_suspect": rotation_score["suspect"],
        "rotation_top_score": rotation_score["top_score"],
        "rotation_bottom_score": rotation_score["bottom_score"],
        **{f"crop_{name}": path for name, path in crop_paths.items()},
    }


def is_name_suspect(fields: dict):
    name = str(fields.get("name") or "").lower()
    if not name:
        return True
    if not fields.get("type") and any(word in name for word in NAME_METADATA_WORDS):
        return True
    if any(word in name for word in ("champion unit", "signature spell", "gear equipment")):
        return True
    if len(name) > 42:
        return True
    return False


def is_might_suspect(fields: dict):
    might = fields.get("might")
    card_type = fields.get("type") or ""
    if might is None:
        return False
    if card_type not in ("Unit", "Champion Unit"):
        return True
    return not (0 < int(might) <= 12)


def has_footer_noise(fields: dict):
    text = f"{fields.get('effect') or ''} {fields.get('name') or ''}".lower()
    return any(word in text for word in FOOTER_NOISE_WORDS)


def write_manifest(output_dir: Path, rows: list[dict]):
    if not rows:
        return
    json_path = output_dir / "manifest.json"
    json_path.write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")
    csv_path = output_dir / "manifest.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def write_correction_templates(output_dir: Path, rows: list[dict]):
    columns = [
        "id",
        "image",
        "parsed_name",
        "correct_name",
        "parsed_type",
        "correct_type",
        "parsed_card_type_labels",
        "correct_card_type_labels",
        "parsed_tags",
        "correct_tags",
        "parsed_domain",
        "correct_domain",
        "parsed_cost",
        "correct_cost",
        "parsed_power_cost",
        "correct_power_cost",
        "parsed_might",
        "correct_might",
        "parsed_effect",
        "correct_effect",
        "parsed_set",
        "correct_set",
        "parsed_card_number",
        "correct_card_number",
        "notes",
    ]
    all_path = output_dir / "corrections_template_all.csv"
    suspicious_path = output_dir / "corrections_template_suspicious.csv"

    def write(path: Path, selected_rows: list[dict]):
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=columns)
            writer.writeheader()
            for row in selected_rows:
                writer.writerow({column: row.get(column, "") for column in columns})

    write(all_path, rows)
    suspicious = [
        row for row in rows
        if row["name_suspect"] or row["might_suspect"] or row["footer_noise"] or row["has_lore_marker"] or row["rotation_suspect"]
    ]
    write(suspicious_path, suspicious)


def write_review_instructions(output_dir: Path):
    (output_dir / "REVIEW.md").write_text(
        "\n".join(
            [
                "# Riftbound OCR Crop Review",
                "",
                "Generated folders:",
                "- `zones/`: one folder per fixed card region.",
                "- `sheets/`: quick contact sheets for each crop region.",
                "- `review_panels/`: larger panels for suspected bad parses.",
                "- `ocr_enhanced/`: optional OCR preprocessing variants when `--enhanced` is used.",
                "- `symbol_candidates/`: optional noisy inline-symbol candidates when `--symbol-candidates` is used.",
                "- `zones/lore_marker/` and `zones/lore_box/`: crops for detecting/removing lore text.",
                "- `manifest.csv`: every image with parsed fields and crop paths.",
                "- `corrections_template_suspicious.csv`: fill only fields that need correction.",
                "- `corrections_template_all.csv`: same template for every card.",
                "",
                "Recommended review order:",
                "1. Open `review_panels/name_suspects_sheet.jpg` and correct names/types first.",
                "2. Open `review_panels/might_suspects_sheet.jpg` and clear wrong might values from spells/gears.",
                "3. Open `review_panels/lore_detected_sheet.jpg` and check if the lore marker is correctly detected.",
                "4. Open `review_panels/footer_noise_sheet.jpg` and mark effects that include lore/artist text.",
                "5. Check `sheets/name_tight.jpg`, `sheets/type_region.jpg`, `sheets/effect_core.jpg`, and `sheets/lore_marker.jpg`.",
                "6. If `ocr_enhanced/` exists, compare `gray`, `contrast`, `sharp`, `binary`, and `binary_inverted` sheets.",
                "7. If `symbol_candidates/` exists, manually split useful crops from `colored` and `dark_round`; these folders are intentionally noisy.",
                "",
                "For corrections, leave a `correct_*` field empty when the parsed value is already acceptable.",
                "",
                "Recreate the full review pack with:",
                "`python ml/prepare_ocr_review_crops.py <dataset_dir> <output_dir> --overwrite --enhanced --symbol-candidates`",
            ],
        ),
        encoding="utf-8",
    )


def write_summary(output_dir: Path, items: list[dict], rows: list[dict], review_groups):
    summary = {
        "item_count": len(items),
        "processed_count": len(rows),
        "review_counts": {name: len(group) for name, group in review_groups.items()},
        "zones": {zone.name: zone.box for zone in ZONES},
    }
    (output_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")


def write_zone_contact_sheets(output_dir: Path, samples: int):
    sheets_dir = output_dir / "sheets"
    sheets_dir.mkdir(parents=True, exist_ok=True)
    for zone in ZONES:
        files = sorted((output_dir / "zones" / zone.name).glob("*.jpg"))[:samples]
        if not files:
            continue
        write_contact_sheet(files, sheets_dir / f"{zone.name}.jpg", title=zone.name)


def write_enhanced_contact_sheets(output_dir: Path, samples: int):
    sheets_dir = output_dir / "sheets" / "ocr_enhanced"
    for zone_name in sorted(TEXT_ZONE_NAMES):
        zone_dir = output_dir / "ocr_enhanced" / zone_name
        if not zone_dir.exists():
            continue
        for variant_dir in sorted(path for path in zone_dir.iterdir() if path.is_dir()):
            files = sorted(variant_dir.glob("*.jpg"))[:samples]
            if files:
                write_contact_sheet(
                    files,
                    sheets_dir / f"{zone_name}_{variant_dir.name}.jpg",
                    title=f"{zone_name} / {variant_dir.name}",
                )


def write_symbol_candidate_sheet(output_dir: Path, samples: int):
    candidates_dir = output_dir / "symbol_candidates"
    if not candidates_dir.exists():
        return
    for group_dir in sorted(path for path in candidates_dir.iterdir() if path.is_dir()):
        files = sorted(group_dir.glob("*.jpg"))[:samples]
        if files:
            write_contact_sheet(
                files,
                output_dir / "sheets" / f"symbol_candidates_{group_dir.name}.jpg",
                title=f"symbol_candidates / {group_dir.name}",
            )


def write_review_panels(output_dir: Path, review_groups):
    panels_dir = output_dir / "review_panels"
    panels_dir.mkdir(parents=True, exist_ok=True)
    for name, group in review_groups.items():
        group_dir = panels_dir / name
        group_dir.mkdir(parents=True, exist_ok=True)
        panel_paths = []
        for index, (item, image_path) in enumerate(group, start=1):
            panel_path = group_dir / f"{index:04d}_{item['id']}.jpg"
            with Image.open(image_path) as raw_image:
                image = ImageOps.exif_transpose(raw_image).convert("RGB")
                make_review_panel(image, item).save(panel_path, quality=95)
            panel_paths.append(panel_path)
        if panel_paths:
            write_contact_sheet(panel_paths[:40], panels_dir / f"{name}_sheet.jpg", title=name, thumb_size=(360, 520))


def write_html_report(output_dir: Path, rows: list[dict], review_groups):
    sheets = sorted((output_dir / "sheets").glob("*.jpg"))
    enhanced_sheets = sorted((output_dir / "sheets" / "ocr_enhanced").glob("*.jpg"))
    review_sheets = sorted((output_dir / "review_panels").glob("*_sheet.jpg"))
    symbol_sheets = [path for path in sheets if path.name.startswith("symbol_candidates")]
    regular_sheets = [path for path in sheets if path not in symbol_sheets]
    total_size_mb = sum(path.stat().st_size for path in output_dir.rglob("*") if path.is_file()) / (1024 * 1024)

    html_text = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Riftbound OCR Review</title>
  <style>
    body {{ font-family: Arial, sans-serif; margin: 24px; background: #f6f7f9; color: #17202a; }}
    h1, h2 {{ margin-bottom: 8px; }}
    .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }}
    .card {{ background: white; border: 1px solid #d8dee7; border-radius: 8px; padding: 12px; }}
    .metric {{ font-size: 28px; font-weight: 700; }}
    a {{ color: #064b75; }}
    img {{ max-width: 100%; height: auto; border: 1px solid #d8dee7; background: white; }}
    .sheet {{ margin: 18px 0 32px; }}
    code {{ background: #e9edf2; padding: 2px 4px; border-radius: 4px; }}
    table {{ border-collapse: collapse; width: 100%; background: white; }}
    td, th {{ border: 1px solid #d8dee7; padding: 6px 8px; text-align: left; }}
    .muted {{ color: #5d6978; }}
  </style>
</head>
<body>
  <h1>Riftbound OCR Review</h1>
  <p class="muted">Generated from <code>prepare_ocr_review_crops.py</code>.</p>

  <div class="grid">
    <div class="card"><div class="metric">{len(rows)}</div><div>cards processed</div></div>
    <div class="card"><div class="metric">{len(review_groups["name_suspects"])}</div><div>name suspects</div></div>
    <div class="card"><div class="metric">{len(review_groups["might_suspects"])}</div><div>might suspects</div></div>
    <div class="card"><div class="metric">{len(review_groups["footer_noise"])}</div><div>footer/effect noise</div></div>
    <div class="card"><div class="metric">{len(review_groups["lore_detected"])}</div><div>lore markers</div></div>
    <div class="card"><div class="metric">{len(review_groups["rotation_suspects"])}</div><div>rotation suspects</div></div>
    <div class="card"><div class="metric">{total_size_mb:.1f} MB</div><div>review pack size</div></div>
  </div>

  <h2>Start Here</h2>
  <div class="card">
    <p>Review in this order:</p>
    <ol>
      <li><a href="review_panels/name_suspects_sheet.jpg">name suspects sheet</a></li>
      <li><a href="review_panels/might_suspects_sheet.jpg">might suspects sheet</a></li>
      <li><a href="review_panels/lore_detected_sheet.jpg">lore detected sheet</a></li>
      <li><a href="review_panels/footer_noise_sheet.jpg">footer noise sheet</a></li>
      <li><a href="corrections_template_suspicious.csv">corrections_template_suspicious.csv</a></li>
      <li><a href="manifest.csv">manifest.csv</a></li>
      <li><a href="REVIEW.md">REVIEW.md</a></li>
    </ol>
  </div>

  <h2>Review Sheets</h2>
  {image_sections(output_dir, review_sheets)}

  <h2>Core Crop Sheets</h2>
  {image_sections(output_dir, regular_sheets)}

  <h2>Symbol Candidate Sheets</h2>
  <p class="muted">These are intentionally noisy. Use them as a pool to manually split useful symbol crops.</p>
  {image_sections(output_dir, symbol_sheets)}

  <h2>OCR Enhanced Sheets</h2>
  <p class="muted">Compare these before choosing a preprocessing mode for app-side OCR crops.</p>
  {image_sections(output_dir, enhanced_sheets[:30])}

  <h2>Parsed Field Counts</h2>
  {field_count_table(rows)}
</body>
</html>
"""
    (output_dir / "index.html").write_text(html_text, encoding="utf-8")


def image_sections(output_dir: Path, paths: list[Path]):
    if not paths:
        return "<p class=\"muted\">No sheets generated.</p>"
    sections = []
    for path in paths:
        rel = path.relative_to(output_dir).as_posix()
        sections.append(
            f'<div class="sheet"><h3><a href="{html.escape(rel)}">{html.escape(path.stem)}</a></h3>'
            f'<a href="{html.escape(rel)}"><img src="{html.escape(rel)}" loading="lazy"></a></div>'
        )
    return "\n".join(sections)


def field_count_table(rows: list[dict]):
    fields = [
        ("type", "parsed_type"),
        ("domain", "parsed_domain"),
        ("set", "parsed_set"),
        ("power cost", "parsed_power_cost"),
    ]
    table_rows = []
    for label, key in fields:
        counts = {}
        for row in rows:
            value = str(row.get(key) or "<empty>")
            counts[value] = counts.get(value, 0) + 1
        values = ", ".join(
            f"{html.escape(name)}: {count}"
            for name, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))
        )
        table_rows.append(f"<tr><th>{html.escape(label)}</th><td>{values}</td></tr>")
    return "<table>" + "\n".join(table_rows) + "</table>"


def make_review_panel(image: Image.Image, item: dict):
    fields = item["fields"]
    full = ImageOps.contain(image, (320, 450))
    zones_by_name = {zone.name: zone for zone in ZONES}
    crops = [
        ("type", crop_fraction(image, zones_by_name["type_region"].box)),
        ("name", crop_fraction(image, zones_by_name["name"].box)),
        ("effect", crop_fraction(image, zones_by_name["effect_core"].box)),
        ("lore", crop_fraction(image, zones_by_name["lore_box"].box)),
        ("footer", crop_fraction(image, (0.035, 0.860, 0.985, 0.985))),
    ]
    crop_width = 520
    crop_total_height = 0
    resized_crops = []
    for label, crop in crops:
        ratio = crop.height / crop.width
        resized = crop.resize((crop_width, max(40, round(crop_width * ratio))))
        resized_crops.append((label, resized))
        crop_total_height += resized.height + 26

    text_height = 150
    height = max(full.height, crop_total_height) + text_height + 24
    panel = Image.new("RGB", (full.width + crop_width + 36, height), (245, 245, 245))
    draw = ImageDraw.Draw(panel)
    panel.paste(full, (12, 12))
    x = full.width + 24
    y = 12
    for label, crop in resized_crops:
        draw.text((x, y), label, fill=(0, 0, 0))
        y += 18
        panel.paste(crop, (x, y))
        y += crop.height + 8

    text_y = max(full.height, y) + 8
    lines = [
        f"id: {item['id']}",
        f"name: {fields.get('name')}",
        f"type/domain: {fields.get('type')} / {fields.get('domain')}",
        f"cost/might/power: {fields.get('cost')} / {fields.get('might')} / {fields.get('powerCost')}",
        f"set/number: {fields.get('set')} / {fields.get('cardNumber')}",
    ]
    for line in lines:
        draw.text((12, text_y), line, fill=(0, 0, 0))
        text_y += 22
    return panel


def write_contact_sheet(files: list[Path], output_file: Path, title: str, thumb_size=(220, 220)):
    columns = 5
    rows = (len(files) + columns - 1) // columns
    thumb_w, thumb_h = thumb_size
    header = 32
    sheet = Image.new("RGB", (columns * thumb_w, rows * thumb_h + header), (255, 255, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((8, 8), title, fill=(0, 0, 0))
    for index, path in enumerate(files):
        with Image.open(path) as raw_image:
            image = ImageOps.contain(raw_image.convert("RGB"), (thumb_w, thumb_h))
            x = (index % columns) * thumb_w
            y = (index // columns) * thumb_h + header
            sheet.paste(image, (x, y))
            draw.text((x + 4, y + 4), path.stem[:32], fill=(255, 0, 0))
    output_file.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output_file, quality=95)


if __name__ == "__main__":
    main()
