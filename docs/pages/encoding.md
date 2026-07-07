---
title: Encoding a tag
keywords: [encode, write, EPC, NFC card, UHF tag, continuous mode, overwrite, re-write, lock, verify]
categories: [usage]
related: [epc-format, settings, troubleshooting]
---
# Encoding a tag

The **Encode** tab copies an NFC card's UID onto a UHF tag in four steps.

## 1. Tap the NFC card

The UID and the [NXP originality](nxp-originality) status appear in step 1. A short
beep confirms the tap was registered.

![Waiting for an NFC card](assets/encode-wait-for-card.png)

## 2. UHF tag check

The app scans for the configured duration (default 3 s) and requires exactly one tag
in range. Zero or multiple tags stop the workflow with an error — isolate a single
tag and press *Scan again*.

![UHF scan in progress](assets/encode-uhf-scan.png)

## 3. Confirm the write

The screen shows the tag's current EPC (with RSSI) and the new EPC to be written. If
the tag already carries a UID from a *different* card, an overwrite warning is shown.
Press **Write tag**.

![Write confirmation](assets/encode-confirm-write.png)

## 4. Write, lock, verify

The app writes the EPC, sets the passwords, locks the tag (when locking is enabled),
and verifies the result by reading the tag back. Success is signalled with a green
flash.

![Encoding finished](assets/encode-success.png)

## Continuous mode

**Continuous mode** returns to step 1 automatically two seconds after a success —
convenient for encoding a batch of cards.

## Already-encoded tags

If the scanned tag already carries this card's UID, the app reports *"re-writing is
not required"* instead of writing.

![Tag already encoded](assets/encode-already-encoded.png)

> [!NOTE]
> With locking enabled the app silently completes any missing passwords/locks first —
> this also repairs tags left half-secured by an interrupted write. **Write again
> anyway** forces a full re-write.

## If a write fails

> [!TIP]
> If a write fails mid-sequence (e.g. the tag left the field), simply scan again —
> the workflow re-reads the tag's current state and the whole sequence is safe to
> re-run.
