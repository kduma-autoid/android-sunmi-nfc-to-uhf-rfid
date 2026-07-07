---
title: NXP originality check
keywords: [NXP, originality, signature, counterfeit, DESFire, NTAG, Ultralight, ISO-DEP, public key]
categories: [reference]
related: [encoding, validating, troubleshooting]
---
# NXP originality check

On every card tap the app reads the NXP originality signature (DESFire family over
ISO-DEP, NTAG21x/Ultralight over NFC-A) and verifies it against NXP's public keys.

> [!IMPORTANT]
> A valid signature proves the silicon is a genuine NXP chip — it does **not** prove
> the card belongs to your system.

An invalid signature pauses the workflow with a warning dialog asking whether to
continue.

The originality status is shown in step 1 of the [Encode workflow](encoding) and
under the verdict on the [Validate tab](validating).
