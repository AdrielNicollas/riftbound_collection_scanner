import argparse
from pathlib import Path

import tensorflow as tf


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
    return parser.parse_args()


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

    train_ds = tf.keras.utils.image_dataset_from_directory(
        args.dataset_dir,
        validation_split=0.2,
        subset="training",
        seed=args.seed,
        image_size=(args.image_size, args.image_size),
        batch_size=args.batch_size,
        label_mode="categorical",
    )
    val_ds = tf.keras.utils.image_dataset_from_directory(
        args.dataset_dir,
        validation_split=0.2,
        subset="validation",
        seed=args.seed,
        image_size=(args.image_size, args.image_size),
        batch_size=args.batch_size,
        label_mode="categorical",
    )

    labels = train_ds.class_names
    (args.output_dir / "labels.txt").write_text("\n".join(labels) + "\n", encoding="utf-8")

    train_ds = train_ds.prefetch(tf.data.AUTOTUNE)
    val_ds = val_ds.prefetch(tf.data.AUTOTUNE)

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
