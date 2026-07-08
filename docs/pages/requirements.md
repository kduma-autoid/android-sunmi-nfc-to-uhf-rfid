---
title: Requirements
keywords: [requirements, device, Sunmi, Chainway, UHF module, scanner service, cards, tags, MIFARE, NTAG, DESFire, EPC Gen2, Impinj, UCODE]
categories: [basics]
related: [readers, installation, epc-format]
---
# Requirements

| Component | Requirement |
|---|---|
| UHF reader | One of the [supported readers](readers): the built-in UHF module of a Sunmi L2s RFID / L3, the UHF R2000 trigger handle on a Sunmi L2k / L2s / L2H / L2s Pro, or an external Chainway reader (e.g. R2 / R3) over USB or Bluetooth |
| Device | Android 7.0+ with NFC; for Sunmi readers a Sunmi handheld with the Sunmi scanner service (`com.sunmi.scanner`, preinstalled) |
| Cards | NFC-A: MIFARE Classic/Ultralight, NTAG, DESFire (UID 4, 7 or 10 bytes) |
| Tags | EPC Gen2 (ISO 18000-6C) with a 96-bit EPC bank — e.g. Impinj Monza R6, M730/M750, NXP UCODE 8/9 |

Tested with Chainway R2, Chainway R3, Sunmi L2k, Sunmi L2s RFID and Sunmi L3.

> [!NOTE]
> Cards with random UIDs (e.g. bank cards) are rejected — a random UID cannot serve
> as a stable identifier.
