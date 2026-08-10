# OCR Bulk Experiments

Dataset source:

```text
C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947
```

Review pack:

```text
C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_crops
```

Training candidate:

```text
C:\Users\Adriel Nicolau\Downloads\riftbound_ocr_dataset_20260809_132947_training_candidate
```

Important: these runs use parsed app values as pseudo-labels. They are useful as signal checks, not as final model quality.

## Generated Review Data

- Cards processed: 341
- Name suspects: 60
- Might suspects: 49
- Footer/effect noise suspects: 17
- Rotation suspects: 22
- Inline symbol candidates:
  - colored: 940
  - dark_round: 2272
- Card power-cost crops are available at `zones/card_power_cost` and `sheets/card_power_cost.jpg`.

The generated `manifest.csv` and correction CSVs include `parsed_effect` / `correct_effect`, so effect text can now be corrected and reused for OCR experiments.

## Training Candidate Counts

- `labels.jsonl`: 341 card records
- Pseudo-label values exported: 2448
- Corrected values exported: 0
- Empty fields skipped: 621

## Baseline Models

All models were trained with `ml/train_symbol_classifier.py` using the generated training candidate folders.

| Field | Output | Labels | Best validation accuracy | Notes |
| --- | --- | --- | --- | --- |
| domain | `.ml-data/models/ocr_bulk_domain_pseudo` | body, calm, chaos, fury, mind, order | 86.27% | Promising, but `calm` only has 3 pseudo-labeled examples. |
| type | `.ml-data/models/ocr_bulk_type_pseudo` | champion_unit, gear, spell, unit | 73.21% | Useful signal, but not strong enough to replace OCR/rules yet. |
| power_cost | `.ml-data/models/ocr_bulk_power_cost_pseudo` | any, body, calm, chaos, fury, mind | 94.12% | This older pseudo-label run was before separating `card_power_cost`; rerun only after correcting labels. Current pseudo-labels contain no `order` examples. |
| set | `.ml-data/models/ocr_bulk_set_pseudo` | ogn, ogs, sfd, unl, ven | 88.24% | Good signal, but `ogs` and `unl` have almost no examples. |
| cost | `.ml-data/models/ocr_bulk_cost_pseudo` | 0-9 | 36.23% | Not usable yet; needs cleaner labels/crops or a better digit pipeline. |
| might | `.ml-data/models/ocr_bulk_might_pseudo` | noisy values including 34, 41, 51, 94 | 41.38% | Not usable yet; pseudo-label noise must be corrected first. |

## Practical Read

Domain, set, and power cost look worth pursuing with corrected data. Cost and might should not be trusted from this pseudo-label run. For power cost specifically, review `sheets/card_power_cost.jpg` and fill `correct_power_cost`; otherwise cards like Darius with Order power cannot train an Order class. The next useful step is to correct a focused CSV slice for:

- `correct_name`
- `correct_type`
- `correct_domain`
- `correct_power_cost`
- `correct_might`
- `correct_effect`
- `correct_set`
- `correct_card_number`

After corrections, rerun `ml/build_ocr_training_dataset.py` with `--only-corrected` to create a cleaner ground-truth subset.
