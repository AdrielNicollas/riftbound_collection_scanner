import argparse
import shutil
from pathlib import Path

from PIL import Image, ImageOps


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}


def parse_args():
    parser = argparse.ArgumentParser(
        description="Prepare cropped Riftbound symbol images for classifier training.",
    )
    parser.add_argument("output_dir", type=Path, help="Where prepared class folders will be written.")
    parser.add_argument(
        "--label",
        nargs=2,
        action="append",
        metavar=("NAME", "PATH"),
        required=True,
        help="Class label and folder containing flat crop images. Can be repeated.",
    )
    parser.add_argument("--size", type=int, default=160, help="Output square image size.")
    parser.add_argument("--clear", action="store_true", help="Delete output_dir before writing.")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.clear and args.output_dir.exists():
        shutil.rmtree(args.output_dir)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    total = 0
    for label, source in args.label:
        label = normalize_label(label)
        source_dir = Path(source)
        output_label_dir = args.output_dir / label
        output_label_dir.mkdir(parents=True, exist_ok=True)

        count = 0
        for image_path in sorted(source_dir.iterdir()):
            if not image_path.is_file() or image_path.suffix.lower() not in IMAGE_EXTENSIONS:
                continue

            try:
                prepared = prepare_image(image_path, args.size)
            except Exception as exc:
                print(f"skipped {image_path}: {exc}")
                continue

            count += 1
            output_file = output_label_dir / f"{label}_{count:04d}.jpg"
            prepared.save(output_file, quality=95)
        total += count
        print(f"{label}: {count}")

    print(f"wrote {total} prepared crops to {args.output_dir}")


def normalize_label(label: str) -> str:
    return label.strip().lower().replace("-", "_").replace(" ", "_")


def prepare_image(path: Path, size: int) -> Image.Image:
    with Image.open(path) as image:
        image = ImageOps.exif_transpose(image)
        if image.mode == "RGBA":
            background = Image.new("RGBA", image.size, (255, 255, 255, 255))
            background.alpha_composite(image)
            image = background.convert("RGB")
        else:
            image = image.convert("RGB")

        background_color = border_average_color(image)
        return ImageOps.pad(image, (size, size), color=background_color, centering=(0.5, 0.5))


def border_average_color(image: Image.Image) -> tuple[int, int, int]:
    pixels = []
    width, height = image.size
    for x in range(width):
        pixels.append(image.getpixel((x, 0)))
        pixels.append(image.getpixel((x, height - 1)))
    for y in range(height):
        pixels.append(image.getpixel((0, y)))
        pixels.append(image.getpixel((width - 1, y)))

    return tuple(round(sum(channel) / len(pixels)) for channel in zip(*pixels))


if __name__ == "__main__":
    main()
