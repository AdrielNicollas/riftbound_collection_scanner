# Riftbound Collection Scanner

Private Android app for scanning and cataloging physical Riftbound cards.

The app uses the phone camera and on-device OCR to help identify cards, extract card details, and store them in a local collection database. Its main goal is to make it easy to track which Riftbound cards I own and how many copies of each card are in my collection.

## What It Does

- Scans physical Riftbound cards with the Android camera
- Shows a guide frame to align the card before capture
- Crops saved photos to the card area
- Uses OCR to detect card text
- Stores scanned cards locally on the device
- Lets the user review and correct detected card details
- Tracks saved cards and duplicate copies in a personal collection

## Riot API Usage

The app is designed to optionally use Riot's official Riftbound API when access is available.

The Riot API would only be used to match scanned physical cards with official Riot card data, such as:

- official card name
- card number
- card type
- domain
- rules/effect text
- official card image

If Riot API access is unavailable, the app falls back to the locally cropped photo and OCR-detected text. The user can then manually correct the card information before saving it.

API keys are not stored in the repository. Local Riot API credentials should be configured through `local.properties`.

## Scope

This project is intended for private personal use only, possibly shared with one or two friends.

It is not monetized, not advertised, and not intended for broad public distribution. It does not simulate gameplay, automate rules, provide matchmaking, rankings, win rates, metagame statistics, tournament functionality, or automated gameplay assistance.

## Status

Early Android prototype.

Current focus:

- reliable card scanning
- clean card-image cropping
- local collection tracking
- optional official data lookup when Riot API access is available
