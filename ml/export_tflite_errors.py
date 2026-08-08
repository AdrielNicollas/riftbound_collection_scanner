import argparse
import csv
import shutil
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
import tensorflow as tf


def parse_args():
    parser = argparse.ArgumentParser(
        description="Export misclassified Riftbound domain images for visual inspection.",
    )
    parser.add_argument("model", type=Path)
    parser.add_argument("labels", type=Path)
    parser.add_argument("dataset_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--image-size", type=int, default=160)
    return parser.parse_args()


def load_image(path: Path, image_size: int, dtype) -> np.ndarray:
    with Image.open(path) as image:
        image = ImageOps.exif_transpose(image).convert("RGB")
        image = ImageOps.pad(image, (image_size, image_size), color=(0, 0, 0), centering=(0.5, 0.5))
        array = np.asarray(image, dtype=np.float32)
    if dtype == np.uint8:
        array = array.astype(np.uint8)
    return np.expand_dims(array, axis=0)


def main():
    args = parse_args()
    labels = [line.strip() for line in args.labels.read_text(encoding="utf-8").splitlines() if line.strip()]
    args.output_dir.mkdir(parents=True, exist_ok=True)

    interpreter = tf.lite.Interpreter(model_path=str(args.model))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]

    rows = []
    total = 0
    errors = 0
    for expected_label in labels:
        for image_path in sorted((args.dataset_dir / expected_label).glob("*.jpg")):
            image = load_image(image_path, args.image_size, input_details["dtype"])
            interpreter.set_tensor(input_details["index"], image)
            interpreter.invoke()
            scores = interpreter.get_tensor(output_details["index"])[0]
            predicted_index = int(np.argmax(scores))
            predicted_label = labels[predicted_index]
            confidence = float(scores[predicted_index])
            total += 1

            if predicted_label == expected_label:
                continue

            errors += 1
            error_dir = args.output_dir / f"expected_{expected_label}_predicted_{predicted_label}"
            error_dir.mkdir(parents=True, exist_ok=True)
            target_file = error_dir / image_path.name
            shutil.copy2(image_path, target_file)
            rows.append(
                {
                    "source": str(image_path),
                    "exported": str(target_file),
                    "expected": expected_label,
                    "predicted": predicted_label,
                    "confidence": f"{confidence:.6f}",
                },
            )

    csv_file = args.output_dir / "errors.csv"
    with csv_file.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["source", "exported", "expected", "predicted", "confidence"])
        writer.writeheader()
        writer.writerows(rows)

    print(f"errors: {errors}/{total}")
    print(f"wrote {csv_file}")
    print(f"copied error images to {args.output_dir}")


if __name__ == "__main__":
    main()
