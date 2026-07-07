---
title: One-by-one validation
keywords: [validate, paired, tag missing, verdict, RSSI, originality]
categories: [usage]
related: [scanning, nxp-originality]
---
# One-by-one validation

Tap a card on the **Validate** tab — the app searches for a tag carrying exactly that
card's EPC and shows a full-screen verdict: green **PAIRED** (with the tag's RSSI) or
red **TAG MISSING**. The card's [NXP originality](nxp-originality) status is shown
underneath. The screen is immediately ready for the next card.

**Idle** — waiting for a card:

![Validate tab, idle](assets/validate-idle.png)

**Searching** — the reader looks for the card's EPC within the configured
[scan duration](settings):

![Searching for the tag](assets/validate-searching.png)

**Paired** — a tag carrying the card's EPC answered; its RSSI is shown:

![Tag paired](assets/validate-paired.png)

**Missing** — no tag with that EPC in range:

![Tag missing](assets/validate-tag-missing.png)
