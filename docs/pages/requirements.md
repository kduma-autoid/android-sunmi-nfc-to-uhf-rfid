---
title: Requirements
keywords: [requirements, device, Sunmi, UHF module, scanner service, cards, tags, MIFARE, NTAG, DESFire, EPC Gen2, Impinj, UCODE]
categories: [basics]
related: [installation, epc-format]
---
# Requirements

| Component | Requirement |
|---|---|
| Device | Sunmi handheld with UHF module (UHF R2000 handle, Inner M500, UHF S7100, Inner SIM3500) |
| Service | Sunmi scanner service (`com.sunmi.scanner`) — preinstalled on Sunmi devices |
| Cards | NFC-A: MIFARE Classic/Ultralight, NTAG, DESFire (UID 4, 7 or 10 bytes) |
| Tags | EPC Gen2 (ISO 18000-6C) with a 96-bit EPC bank — e.g. Impinj Monza R6, M730/M750, NXP UCODE 8/9 |

> [!NOTE]
> Cards with random UIDs (e.g. bank cards) are rejected — a random UID cannot serve
> as a stable identifier.
