import argparse
import random
from pathlib import Path

import tensorflow as tf


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
VALIDATION_SPLIT = 0.2


def parse_args():
    parser = argparse.ArgumentParser(
        description="Train a small TensorFlow classifier for cropped Riftbound symbols.",
    )
    parser.add_argument("dataset_dir", type=Path, help="Prepared dataset folder.")
    parser.add_argument("output_dir", type=Path, help="Where model artifacts will be written.")
    parser.add_argument("--image-size", type=int, default=160)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--epochs", type=int, default=45)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--augment",
        action="store_true",
        help="Enable light image augmentation during training.",
    )
    parser.add_argument(
        "--optimize-tflite",
        action="store_true",
        help="Enable default TFLite optimizations. Leave disabled for older Android runtimes.",
    )
    return parser.parse_args()


def build_datasets(
    dataset_dir: Path,
    image_size: int,
    batch_size: int,
    seed: int,
) -> tuple[tf.data.Dataset, tf.data.Dataset, list[str]]:
    labels = sorted(path.name for path in dataset_dir.iterdir() if path.is_dir())
    if not labels:
        raise ValueError(f"No class folders found in {dataset_dir}")

    rng = random.Random(seed)
    train_samples: list[tuple[str, int]] = []
    val_samples: list[tuple[str, int]] = []

    for label_index, label in enumerate(labels):
        class_dir = dataset_dir / label
        image_paths = sorted(
            path
            for path in class_dir.iterdir()
            if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
        )
        if not image_paths:
            continue

        rng.shuffle(image_paths)
        validation_count = 0
        if len(image_paths) > 1:
            validation_count = max(1, round(len(image_paths) * VALIDATION_SPLIT))
            validation_count = min(validation_count, len(image_paths) - 1)

        val_paths = image_paths[:validation_count]
        train_paths = image_paths[validation_count:]
        train_samples.extend((str(path), label_index) for path in train_paths)
        val_samples.extend((str(path), label_index) for path in val_paths)

        print(f"{label}: {len(train_paths)} train, {len(val_paths)} validation")

    rng.shuffle(train_samples)
    rng.shuffle(val_samples)
    class_count = len(labels)
    train_ds = make_dataset(train_samples, image_size, batch_size, class_count, shuffle=True, seed=seed)
    val_ds = make_dataset(val_samples, image_size, batch_size, class_count, shuffle=False, seed=seed)
    return train_ds, val_ds, labels


def make_dataset(
    samples: list[tuple[str, int]],
    image_size: int,
    batch_size: int,
    class_count: int,
    shuffle: bool,
    seed: int,
) -> tf.data.Dataset:
    if not samples:
        raise ValueError("Dataset split is empty")

    paths, labels = zip(*samples)
    ds = tf.data.Dataset.from_tensor_slices((list(paths), list(labels)))
    if shuffle:
        ds = ds.shuffle(buffer_size=len(samples), seed=seed, reshuffle_each_iteration=True)

    ds = ds.map(
        lambda path, label: load_image(path, label, image_size, class_count),
        num_parallel_calls=tf.data.AUTOTUNE,
    )
    return ds.batch(batch_size).prefetch(tf.data.AUTOTUNE)


def load_image(
    path: tf.Tensor,
    label: tf.Tensor,
    image_size: int,
    class_count: int,
) -> tuple[tf.Tensor, tf.Tensor]:
    image = tf.io.read_file(path)
    image = tf.io.decode_image(image, channels=3, expand_animations=False)
    image.set_shape([None, None, 3])
    image = tf.image.resize(image, [image_size, image_size])
    return image, tf.one_hot(label, class_count)


def build_model(image_size: int, class_count: int, augment: bool) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(image_size, image_size, 3))
    x = tf.keras.layers.Rescaling(1.0 / 255)(inputs)
    if augment:
        x = tf.keras.layers.RandomRotation(0.04)(x)
        x = tf.keras.layers.RandomZoom(0.08)(x)
        x = tf.keras.layers.RandomContrast(0.15)(x)
    x = tf.keras.layers.Conv2D(16, 3, padding="same", activation="relu")(x)
    x = tf.keras.layers.MaxPooling2D()(x)
    x = tf.keras.layers.Conv2D(32, 3, padding="same", activation="relu")(x)
    x = tf.keras.layers.MaxPooling2D()(x)
    x = tf.keras.layers.Conv2D(64, 3, padding="same", activation="relu")(x)
    x = tf.keras.layers.MaxPooling2D()(x)
    x = tf.keras.layers.Flatten()(x)
    x = tf.keras.layers.Dense(96, activation="relu")(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(class_count, activation="softmax")(x)
    return tf.keras.Model(inputs, outputs)


def main():
    args = parse_args()
    tf.keras.utils.set_random_seed(args.seed)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    train_ds, val_ds, labels = build_datasets(
        args.dataset_dir,
        args.image_size,
        args.batch_size,
        args.seed,
    )
    (args.output_dir / "labels.txt").write_text("\n".join(labels) + "\n", encoding="utf-8")

    model = build_model(args.image_size, len(labels), args.augment)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_accuracy",
            patience=10,
            restore_best_weights=True,
        ),
    ]
    history = model.fit(train_ds, validation_data=val_ds, epochs=args.epochs, callbacks=callbacks)

    keras_path = args.output_dir / "riftbound_symbol_classifier.keras"
    model.save(keras_path)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    if args.optimize_tflite:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    tflite_path = args.output_dir / "riftbound_symbol_classifier.tflite"
    tflite_path.write_bytes(tflite_model)

    best_accuracy = max(history.history.get("val_accuracy", [0.0]))
    print(f"labels: {labels}")
    print(f"best validation accuracy: {best_accuracy:.4f}")
    print(f"wrote {keras_path}")
    print(f"wrote {tflite_path}")


if __name__ == "__main__":
    main()
