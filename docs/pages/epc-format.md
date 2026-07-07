---
title: EPC encoding format
keywords: [EPC, PC word, encoding, format, GS1, ISO 15961, AFI, magic byte, duplicate, 96-bit]
categories: [reference]
related: [encoding, requirements]
---
# EPC encoding format

The EPC is 96 bits (12 bytes, 6 words) and is written together with a new PC word, so
encoding works regardless of the tag's previous EPC length:

```
PC word = 0x3100        L = 6 words; T (toggle) = 1 → non-GS1 / ISO namespace, AFI = 0x00
byte 0     = 0x4E       application magic ('N')
byte 1     = UID length 0x04 / 0x07 / 0x0A
bytes 2–11 = UID bytes, left-aligned, zero-padded
```

Example: card UID `74:0B:2A:EB` (4 bytes) → EPC `4E04740B2AEB000000000000`.

## Design notes

- The T bit in the PC word declares the tag as a **non-GS1** identifier (ISO 15961),
  so the encoding can never collide with present or future GS1 EPC schemes.
  GS1-conformant software represents such tags as `urn:epc:raw:...` instead of
  failing to decode.
- Some tag chips do not persist the T/AFI bits of a written PC word. This is
  harmless — decoding keys on the `4E` prefix and the UID length byte, and post-write
  verification compares the EPC only.
- Two tags carrying the identical EPC look like one entry during inventory. The write
  sequence detects this after the fact: if the *old* EPC is still in the field after
  a successful write, the app reports a duplicate-tag error.
