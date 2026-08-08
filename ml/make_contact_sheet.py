import argparse
from pathlib import Path

from PIL import Image, ImageDraw


def parse_args():
    parser = argparse.ArgumentParser(description="Create a quick visual contact sheet for a prepared dataset.")
    parser.add_argument("dataset_dir", type=Path)
    parser.add_argument("output_file", type=Path)
    parser.add_argument("--samples", type=int, default=6)
    parser.add_argument("--thumb-size", type=int, default=160)
    return parser.parse_args()


def main():
    args = parse_args()
    labels = sorted(path.name for path in args.dataset_dir.iterdir() if path.is_dir())
    if not labels:
        raise SystemExit(f"No label folders found in {args.dataset_dir}")

    header_height = 24
    sheet = Image.new(
        "RGB",
        (args.thumb_size * len(labels), args.thumb_size * args.samples + header_height),
        (255, 255, 255),
    )
    draw = ImageDraw.Draw(sheet)

    for column, label in enumerate(labels):
        x = column * args.thumb_size
        draw.text((x + 6, 4), label, fill=(0, 0, 0))
        for row, image_path in enumerate(sorted((args.dataset_dir / label).glob("*.jpg"))[: args.samples]):
            with Image.open(image_path) as image:
                image = image.convert("RGB").resize((args.thumb_size, args.thumb_size))
                sheet.paste(image, (x, row * args.thumb_size + header_height))

    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(args.output_file, quality=95)
    print(args.output_file.resolve())


if __name__ == "__main__":
    main()
