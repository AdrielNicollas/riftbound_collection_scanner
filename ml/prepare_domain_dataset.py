import argparse
import colorsys
import csv
import json
import shutil
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps


LABELS = ("body", "calm", "chaos", "fury", "mind", "order")
DEFAULT_CROP = (0.86, 0.82, 0.985, 0.965)
LABEL_HUES = {
    "fury": ((345, 360), (0, 15)),
    "body": ((16, 35),),
    "order": ((36, 65),),
    "calm": ((85, 155),),
    "mind": ((190, 240),),
    "chaos": ((255, 310),),
}


def parse_args():
    parser = argparse.ArgumentParser(
        description="Crop Riftbound domain symbols from full-card photos.",
    )
    parser.add_argument("input_dir", type=Path, help="Folder with label subfolders.")
    parser.add_argument("output_dir", type=Path, help="Where cropped symbols will be written.")
    parser.add_argument(
        "--size",
        type=int,
        default=160,
        help="Output square image size in pixels.",
    )
    parser.add_argument(
        "--crop",
        type=float,
        nargs=4,
        default=DEFAULT_CROP,
        metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"),
        help="Fallback crop box as image fractions.",
    )
    parser.add_argument(
        "--fixed-crop",
        action="store_true",
        help="Disable color-based symbol lookup and always use the fallback crop.",
    )
    parser.add_argument(
        "--review-dir",
        type=Path,
        default=None,
        help="Where uncertain detections will be copied for review.",
    )
    parser.add_argument(
        "--include-uncertain",
        action="store_true",
        help="Write uncertain crops into the training output instead of only review output.",
    )
    parser.add_argument(
        "--min-center-y",
        type=float,
        default=0.0,
        help="Mark detections above this image-height fraction as review items. Disabled by default.",
    )
    return parser.parse_args()


def crop_symbol(image: Image.Image, crop_box, size: int) -> Image.Image:
    image = ImageOps.exif_transpose(image).convert("RGB")
    width, height = image.size
    left, top, right, bottom = crop_box
    pixel_box = (
        round(width * left),
        round(height * top),
        round(width * right),
        round(height * bottom),
    )
    cropped = image.crop(pixel_box)
    cropped = ImageOps.pad(cropped, (size, size), color=(0, 0, 0), centering=(0.5, 0.5))
    return cropped


def crop_detected_symbol(image: Image.Image, label: str, output_size: int, min_center_y: float):
    image = ImageOps.exif_transpose(image).convert("RGB")
    width, height = image.size
    small_size = 700
    small = ImageOps.contain(image, (small_size, small_size))
    scale_x = width / small.width
    scale_y = height / small.height

    roi = (
        round(small.width * 0.68),
        round(small.height * 0.72),
        small.width,
        small.height,
    )
    mask = build_label_mask(small, label, roi)
    component = best_component(mask, roi)
    if component is None:
        return None

    left, top, right, bottom, area, score, quality = component
    center_x_small = (left + right) / 2
    center_y_small = (top + bottom) / 2
    if min_center_y > 0 and center_y_small < small.height * min_center_y:
        quality = "review"
    center_x = center_x_small * scale_x
    center_y = center_y_small * scale_y
    crop_size = max(width, height) * 0.06
    box = square_box_inside_image(center_x, center_y, crop_size, width, height)
    cropped = image.crop(box)
    cropped = ImageOps.pad(cropped, (output_size, output_size), color=(0, 0, 0), centering=(0.5, 0.5))
    return cropped, {
        "method": "color",
        "center": [round(center_x), round(center_y)],
        "box": list(box),
        "area": area,
        "score": round(score, 4),
        "quality": quality,
    }


def square_box_inside_image(center_x, center_y, crop_size, image_width, image_height):
    crop_size = min(round(crop_size), image_width, image_height)
    left = round(center_x - crop_size / 2)
    top = round(center_y - crop_size / 2)
    left = min(max(left, 0), image_width - crop_size)
    top = min(max(top, 0), image_height - crop_size)
    return (left, top, left + crop_size, top + crop_size)


def build_label_mask(image: Image.Image, label: str, roi):
    left, top, right, bottom = roi
    ranges = LABEL_HUES[label]
    pixels = image.load()
    mask = set()

    for y in range(top, bottom):
        for x in range(left, right):
            red, green, blue = pixels[x, y]
            hue, saturation, value = colorsys.rgb_to_hsv(red / 255, green / 255, blue / 255)
            hue *= 360
            if saturation < 0.28 or value < 0.22:
                continue
            if any(start <= hue <= end for start, end in ranges):
                mask.add((x, y))

    return mask


def best_component(mask, roi):
    if not mask:
        return None

    visited = set()
    candidates = []
    roi_left, roi_top, roi_right, roi_bottom = roi
    roi_width = roi_right - roi_left
    roi_height = roi_bottom - roi_top

    for point in list(mask):
        if point in visited:
            continue
        queue = deque([point])
        visited.add(point)
        xs = []
        ys = []

        while queue:
            x, y = queue.popleft()
            xs.append(x)
            ys.append(y)
            for neighbor in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if neighbor in mask and neighbor not in visited:
                    visited.add(neighbor)
                    queue.append(neighbor)

        area = len(xs)
        if area < 8:
            continue

        left = min(xs)
        right = max(xs)
        top = min(ys)
        bottom = max(ys)
        box_width = right - left + 1
        box_height = bottom - top + 1
        aspect = box_width / max(box_height, 1)
        if aspect < 0.35 or aspect > 2.8:
            continue
        if box_width > roi_width * 0.38 or box_height > roi_height * 0.50:
            continue

        center_x = (left + right) / 2
        center_y = (top + bottom) / 2
        bottom_right_bias = (center_x - roi_left) / roi_width + (center_y - roi_top) / roi_height
        fill_ratio = area / max(box_width * box_height, 1)
        circle_score = 1 - abs(1 - aspect)
        score = area * (1 + bottom_right_bias * 3) * max(circle_score, 0.2) * max(fill_ratio, 0.2)
        quality = classify_quality(
            area=area,
            aspect=aspect,
            fill_ratio=fill_ratio,
            box_width=box_width,
            box_height=box_height,
            roi_width=roi_width,
            roi_height=roi_height,
        )
        candidates.append((score, left, top, right, bottom, area, quality))

    if not candidates:
        return None

    score, left, top, right, bottom, area, quality = max(candidates, key=lambda item: item[0])
    return left, top, right, bottom, area, score, quality


def classify_quality(area, aspect, fill_ratio, box_width, box_height, roi_width, roi_height):
    if area < 35:
        return "review"
    if aspect < 0.55 or aspect > 1.85:
        return "review"
    if fill_ratio < 0.24:
        return "review"
    if box_width > roi_width * 0.22 or box_height > roi_height * 0.28:
        return "review"
    return "ok"


def save_review_image(source_image, label, source_file, output_dir, detection, size):
    output_label_dir = output_dir / label
    output_label_dir.mkdir(parents=True, exist_ok=True)

    image = ImageOps.exif_transpose(source_image).convert("RGB")
    annotated = ImageOps.contain(image, (900, 900))
    draw = ImageDraw.Draw(annotated)
    scale_x = annotated.width / image.width
    scale_y = annotated.height / image.height
    if "box" in detection:
        left, top, right, bottom = detection["box"]
        draw.rectangle(
            (
                round(left * scale_x),
                round(top * scale_y),
                round(right * scale_x),
                round(bottom * scale_y),
            ),
            outline=(255, 0, 0),
            width=4,
        )
    draw.text((8, 8), f"{label} {detection.get('quality', 'review')}", fill=(255, 0, 0))

    base_name = source_file.stem
    annotated.save(output_label_dir / f"{base_name}_annotated.jpg", quality=90)

    if "box" in detection:
        left, top, right, bottom = detection["box"]
        crop = image.crop((left, top, right, bottom))
        crop = ImageOps.pad(crop, (size, size), color=(0, 0, 0), centering=(0.5, 0.5))
        crop.save(output_label_dir / f"{base_name}_crop.jpg", quality=95)


def main():
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    review_dir = args.review_dir or args.output_dir.parent / f"{args.output_dir.name}_needs_review"
    reset_output_dirs(args.output_dir, review_dir)

    manifest = {
        "source": str(args.input_dir),
        "labels": list(LABELS),
        "crop": args.crop,
        "size": args.size,
        "items": [],
        "review_items": [],
    }

    for label in LABELS:
        source_label_dir = args.input_dir / label
        output_label_dir = args.output_dir / label
        output_label_dir.mkdir(parents=True, exist_ok=True)

        if not source_label_dir.exists():
            print(f"missing label folder: {source_label_dir}")
            continue

        for index, source_file in enumerate(sorted(source_label_dir.iterdir()), start=1):
            if not source_file.is_file():
                continue
            try:
                with Image.open(source_file) as image:
                    detected = None if args.fixed_crop else crop_detected_symbol(
                        image,
                        label,
                        args.size,
                        args.min_center_y,
                    )
                    if detected is None:
                        cropped = crop_symbol(image, args.crop, args.size)
                        detection = {"method": "fixed", "quality": "review"}
                    else:
                        cropped, detection = detected

                    if detection.get("quality") != "ok":
                        save_review_image(
                            source_image=image,
                            label=label,
                            source_file=source_file,
                            output_dir=review_dir,
                            detection=detection,
                            size=args.size,
                        )
                        manifest["review_items"].append(
                            {
                                "label": label,
                                "source": str(source_file),
                                "detection": detection,
                            },
                        )
                        if not args.include_uncertain:
                            continue

                    output_file = output_label_dir / f"{label}_{index:04d}.jpg"
                    cropped.save(output_file, quality=95)
                    manifest["items"].append(
                        {
                            "label": label,
                            "source": str(source_file),
                            "output": str(output_file),
                            "detection": detection,
                        },
                    )
            except Exception as exc:
                print(f"skipped {source_file}: {exc}")

    manifest_file = args.output_dir / "manifest.json"
    manifest_file.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    report_file = args.output_dir / "report.csv"
    with report_file.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["label", "source", "output", "quality", "method", "area", "score"])
        writer.writeheader()
        for item in manifest["items"]:
            detection = item["detection"]
            writer.writerow(
                {
                    "label": item["label"],
                    "source": item["source"],
                    "output": item["output"],
                    "quality": detection.get("quality"),
                    "method": detection.get("method"),
                    "area": detection.get("area"),
                    "score": detection.get("score"),
                },
            )
    print(f"wrote {len(manifest['items'])} training crops to {args.output_dir}")
    print(f"wrote {len(manifest['review_items'])} review items to {review_dir}")


def reset_output_dirs(output_dir, review_dir):
    for base_dir in (output_dir, review_dir):
        base_dir.mkdir(parents=True, exist_ok=True)
        for label in LABELS:
            label_dir = base_dir / label
            if label_dir.exists():
                shutil.rmtree(label_dir)
        for file_name in ("manifest.json", "report.csv"):
            file_path = base_dir / file_name
            if file_path.exists():
                file_path.unlink()


if __name__ == "__main__":
    main()
