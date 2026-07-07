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

~~~gallery w=180 scroll
![Idle](assets/validate-idle.png)

![Searching](assets/validate-searching.png)
The reader looks for the card's EPC within the configured scan duration.

![Paired](assets/validate-paired.png)
A tag answered — its RSSI is shown.

![Missing](assets/validate-tag-missing.png)
No tag with this card's EPC in range.
~~~
