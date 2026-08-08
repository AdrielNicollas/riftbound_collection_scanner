import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
import tensorflow as tf


def parse_args():
    parser = argparse.ArgumentParser(description="Evaluate a Riftbound domain TFLite model.")
    parser.add_argument("model", type=Path)
    parser.add_argument("labels", type=Path)
    parser.add_argument("dataset_dir", type=Path)
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
    label_to_index = {label: index for index, label in enumerate(labels)}
    confusion = np.zeros((len(labels), len(labels)), dtype=int)

    interpreter = tf.lite.Interpreter(model_path=str(args.model))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]

    total = 0
    correct = 0
    for expected_label in labels:
        for image_path in sorted((args.dataset_dir / expected_label).glob("*.jpg")):
            image = load_image(image_path, args.image_size, input_details["dtype"])
            interpreter.set_tensor(input_details["index"], image)
            interpreter.invoke()
            scores = interpreter.get_tensor(output_details["index"])[0]
            predicted_index = int(np.argmax(scores))
            expected_index = label_to_index[expected_label]
            confusion[expected_index, predicted_index] += 1
            total += 1
            if predicted_index == expected_index:
                correct += 1

    print(f"accuracy: {correct / total:.4f} ({correct}/{total})")
    print("labels:", ", ".join(labels))
    print("confusion matrix: rows=expected, columns=predicted")
    print("".ljust(10) + "".join(label[:7].rjust(8) for label in labels))
    for index, label in enumerate(labels):
        row = "".join(str(value).rjust(8) for value in confusion[index])
        print(label[:9].ljust(10) + row)


if __name__ == "__main__":
    main()
