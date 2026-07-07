---
title: NFC to RFID
keywords: [NFC, RFID, UHF, Sunmi, EPC, overview, introduction, shadow tag]
categories: [basics]
related: [requirements, installation, using-the-app]
---
# NFC to RFID

![NFC to RFID — encode NFC card UIDs onto UHF RFID tags](assets/banner-large.png)

**NFC to RFID** is an Android app for Sunmi handheld terminals (L2k / L2s / L2H / L3)
equipped with a UHF RFID module. It copies the UID of an NFC card onto a UHF EPC Gen2
tag, so that the tag becomes a radio-readable "shadow" of the card: a UHF scan from a
distance returns the same identifier that the card reports over NFC.

Typical use case: assets or badges carry both an NFC card (short-range, secure
identification) and a UHF label (long-range, bulk inventory). This app encodes the
labels, keeps them password-locked against tampering, and verifies that card–tag
pairs match.

![About tab](assets/about.png#w=300&h=75vh)

## What the app does

- **Encode** — reads an NFC card's UID, checks that exactly *one* UHF tag is in
  range, and writes the UID into the tag's EPC memory bank (with confirmation before
  every write). See [Encoding a tag](encoding).
- **Lock** (optional, on by default) — after writing, the tag is protected: an access
  password and a kill password are set, and the EPC, access-password and
  kill-password banks are locked. Re-encoding a locked tag is only possible with the
  configured access password. See [Settings](settings).
- **Scan** — bulk inventory of tags encoded by this app, with live pairing validation
  against tapped NFC cards. See [Bulk inventory and validation](scanning).
- **Validate** — one-by-one check: tap a card, get an immediate PAIRED / TAG MISSING
  verdict. See [One-by-one validation](validating).
- **NXP originality check** — on every card tap the app verifies the card's NXP
  originality signature against NXP's public keys. See
  [NXP originality check](nxp-originality).

> [!TIP]
> Feedback is designed for warehouse use: distinct success/error tones, vibration,
> and a full-screen green/red flash visible from the corner of your eye.

---

![NFC to RFID](assets/banner.png)
