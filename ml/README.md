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

## Symbol Dataset

Use this for small cropped symbols found inside effect text, such as rune costs, power, and might. Each source folder should contain flat crop images for one class:

```text
symbol_dataset/
  rune_white/
  rune_black/
  power/
  might/
```

Each crop should contain one target symbol only, with a small amount of surrounding context. Avoid full words such as `EMPOWER`, `REPEAT`, or `FLOW` in the crop unless the word itself is the class being trained.

Prepare the current rune dataset:

```powershell
python ml\prepare_symbol_dataset.py `
  ".ml-data\processed_symbol_dataset" `
  --label rune_white "C:\Users\Adriel Nicolau\Downloads\white\good" `
  --label rune_black "C:\Users\Adriel Nicolau\Downloads\black" `
  --clear
```

Train a symbol classifier:

```powershell
python ml\train_symbol_classifier.py `
  ".ml-data\processed_symbol_dataset" `
  ".ml-data\models\symbols_rune" `
  --epochs 120
```

Outputs:

```text
.ml-data/models/symbols_rune/
  labels.txt
  riftbound_symbol_classifier.keras
  riftbound_symbol_classifier.tflite
```

## Open Source Notes

The pipeline and model architecture can be public.

Do not publish raw card photos or card artwork unless you have permission. A good public setup is:

- publish the app code;
- publish these scripts;
- publish instructions for users to train with their own photos;
- optionally publish trained weights only after checking the licensing situation.

## OCR Bulk Review Dataset

The Android app can export bulk scans with full-card images and parsed JSON. Use this flow to turn that export into crops, review sheets, and training-candidate datasets.

Prepare review crops:

```powershell
python ml\prepare_ocr_review_crops.py `
  "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947" `
  "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_crops" `
  --overwrite `
  --enhanced `
  --symbol-candidates
```

Open the generated `index.html` first. It links to:

- review panels for likely name, might, footer/noise, and rotation issues;
- OCR-enhanced contact sheets for comparing preprocessing variants;
- symbol candidate sheets for manually splitting useful inline symbols;
- `sheets/card_power_cost.jpg`, which shows the visual card power-cost area on the left side of the card;
- `corrections_template_all.csv` and `corrections_template_suspicious.csv`.

Build a training-candidate dataset from the review folder:

```powershell
python ml\build_ocr_training_dataset.py `
  "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_crops" `
  "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_training_candidate" `
  --corrections "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_crops\corrections_template_all.csv" `
  --overwrite
```

The output contains:

```text
labels.jsonl
text/name/images + labels.csv
text/effect/images + labels.csv
text/card_number/images + labels.csv
classification/type/<label>
classification/domain/<label>
classification/power_cost/<label>
classification/set/<label>
numeric/cost/<label>
numeric/might/<label>
```

By default, parsed values are exported as pseudo-labels. If you only want reviewed/corrected data, fill the `correct_*` columns in a correction CSV and rerun with `--only-corrected`.

For card power cost, the training crop comes from `crop_card_power_cost`, not from inline effect text. Inline symbols inside the effect box should be reviewed separately from `symbol_candidates`.

See `ml/OCR_BULK_EXPERIMENTS.md` for the current pseudo-label baseline results and notes about which fields are worth improving first.

## External Card Metadata

If you have an external metadata export with fields such as `name`, `card_number`, `card_type`, `card_type_labels`, and `tags`, use it to enrich the correction CSV before rebuilding the training dataset:

```powershell
python ml\enrich_corrections_from_card_metadata.py `
  "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_crops\corrections_template_all.csv" `
  "hf:Wysme/riftbound-cards" `
  "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_crops\corrections_with_metadata.csv"
```

You can also pass a local CSV, JSON, or JSONL export instead of the `hf:` source.

Then rebuild with the enriched file:

```powershell
python ml\build_ocr_training_dataset.py `
  "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_crops" `
  "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_training_candidate" `
  --corrections "C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_crops\corrections_with_metadata.csv" `
  --overwrite
```

The Hugging Face dataset `Wysme/riftbound-cards` includes card names, type, type labels, tags, domain, energy, power, might, ability text, image URLs, and source URLs. Use it as metadata/reference data, not as a replacement for camera-photo training data.
