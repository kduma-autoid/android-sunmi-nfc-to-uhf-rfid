---
title: Troubleshooting
keywords: [troubleshooting, error, failed, locked, duplicate, signature, not detected, tag left the field]
categories: [reference]
related: [encoding, settings, nxp-originality]
---
# Troubleshooting

## Found N tags — leave exactly one tag in range

More than one tag answered during the check. Move other tags away or lower the
[read power](settings).

## The tag left the field during the operation

Hold the tag close to the reader and scan again; the sequence is safe to re-run.

## Access failed / Write failed — locked with a different password

The tag was locked with a password other than the configured one. Without that
password the tag cannot be re-encoded.

## A tag with the old EPC is still in range

Two tags carried the identical EPC and only one took the write. Remove one and scan
again.

## NXP originality: could not read signature

The card was pulled away too quickly — hold it on the reader a moment longer.

## NXP originality: INVALID signature

The signature does not match any known NXP key — possible counterfeit chip. The app
asks whether to continue. See [NXP originality check](nxp-originality).

## RFID reader: not detected (About tab)

No reader answered during auto-detection. Depending on the backend:

- **Sunmi** — UHF module not present/attached, or the Sunmi scanner service is
  unavailable;
- **Chainway USB** — cable not attached, or the USB permission prompt was declined
  (re-attach the reader to get the prompt again);
- **Chainway Bluetooth** — no reader selected yet, the reader is out of range /
  powered off, or the Bluetooth permission was declined.

See [Supported readers](readers) and [Requirements](requirements). Selecting the
backend explicitly in [Settings](settings) shows the connection state for that
specific reader.
