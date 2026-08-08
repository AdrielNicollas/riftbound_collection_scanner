# Riftbound Symbol Models

This folder contains the local training pipeline for visual Riftbound symbol recognition.

The app should use OCR for normal text and computer vision for game symbols such as domains, runes, power, and might.

## Domain Dataset

Raw domain photos should be organized by label:

```text
domain_dataset/
  body/
  calm/
  chaos/
  fury/
  mind/
  order/
```

The current scripts assume the raw images are full-card photos where the domain symbol is near the bottom-right corner.

## Setup

Use a virtual environment:

```powershell
python -m venv .venv-ml
.\.venv-ml\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r ml\requirements.txt
```

## Prepare Crops

```powershell
python ml\prepare_domain_dataset.py `
  "C:\Users\Adriel Nicolau\Downloads\domain_dataset" `
  ".ml-data\processed_domain_dataset"
```

This creates cropped domain-symbol images and a `manifest.json`.

If the crop is not centered on the symbol, tune the fractions:

```powershell
python ml\prepare_domain_dataset.py input output --crop 0.86 0.82 0.985 0.965
```

Uncertain detections are skipped from training and copied to a sibling `*_needs_review` folder. If a dataset has false positives above the real bottom-right symbol area, you can also require detections to appear lower in the image:

```powershell
python ml\prepare_domain_dataset.py input output --min-center-y 0.78
```

If the reviewed `_crop.jpg` files are good, copy them into a separate accepted folder:

```powershell
python ml\accept_review_crops.py `
  ".ml-data\processed_domain_dataset_needs_review" `
  ".ml-data\accepted_review_crops" `
  --clear
```

Or create a combined training dataset from the base crops plus accepted review crops:

```powershell
python ml\accept_review_crops.py `
  ".ml-data\processed_domain_dataset_needs_review" `
  ".ml-data\processed_domain_dataset_with_review" `
  --base-dataset ".ml-data\processed_domain_dataset" `
  --clear
```

Create a contact sheet to inspect crop quality:

```powershell
python ml\make_contact_sheet.py `
  ".ml-data\processed_domain_dataset" `
  ".ml-data\domain_contact_sheet.jpg"
```

## Train

```powershell
python ml\train_domain_classifier.py `
  ".ml-data\processed_domain_dataset" `
  ".ml-data\models\domain"
```

Outputs:

```text
.ml-data/models/domain/
  labels.txt
  riftbound_domain_classifier.keras
  riftbound_domain_classifier.tflite
```

## Test A TFLite Model

```powershell
python ml\predict_domain_tflite.py `
  ".ml-data\models\domain\riftbound_domain_classifier.tflite" `
  ".ml-data\models\domain\labels.txt" `
  ".ml-data\processed_domain_dataset\body\body_0001.jpg"
```

Evaluate the model against a prepared dataset:

```powershell
python ml\evaluate_domain_tflite.py `
  ".ml-data\models\domain\riftbound_domain_classifier.tflite" `
  ".ml-data\models\domain\labels.txt" `
  ".ml-data\processed_domain_dataset"
```

Export misclassified crops for visual inspection:

```powershell
python ml\export_tflite_errors.py `
  ".ml-data\models\domain\riftbound_domain_classifier.tflite" `
  ".ml-data\models\domain\labels.txt" `
  ".ml-data\processed_domain_dataset" `
  ".ml-data\evaluation_errors"
```

## Open Source Notes

The pipeline and model architecture can be public.

Do not publish raw card photos or card artwork unless you have permission. A good public setup is:

- publish the app code;
- publish these scripts;
- publish instructions for users to train with their own photos;
- optionally publish trained weights only after checking the licensing situation.
