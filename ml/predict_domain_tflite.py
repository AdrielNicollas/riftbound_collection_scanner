import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
import tensorflow as tf


def parse_args():
    parser = argparse.ArgumentParser(description="Run a Riftbound domain TFLite model on images.")
    parser.add_argument("model", type=Path)
    parser.add_argument("labels", type=Path)
    parser.add_argument("images", type=Path, nargs="+")
    parser.add_argument("--image-size", type=int, default=160)
    return parser.parse_args()


def load_image(path: Path, image_size: int) -> np.ndarray:
    with Image.open(path) as image:
        image = ImageOps.exif_transpose(image).convert("RGB")
        image = ImageOps.pad(image, (image_size, image_size), color=(0, 0, 0), centering=(0.5, 0.5))
        array = np.asarray(image, dtype=np.float32)
    return np.expand_dims(array, axis=0)


def main():
    args = parse_args()
    labels = [line.strip() for line in args.labels.read_text(encoding="utf-8").splitlines() if line.strip()]

    interpreter = tf.lite.Interpreter(model_path=str(args.model))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]

    for image_path in args.images:
        image = load_image(image_path, args.image_size)
        if input_details["dtype"] == np.uint8:
            image = image.astype(np.uint8)

        interpreter.set_tensor(input_details["index"], image)
        interpreter.invoke()
        scores = interpreter.get_tensor(output_details["index"])[0]
        best_index = int(np.argmax(scores))
        print(f"{image_path}: {labels[best_index]} ({scores[best_index]:.3f})")


if __name__ == "__main__":
    main()
