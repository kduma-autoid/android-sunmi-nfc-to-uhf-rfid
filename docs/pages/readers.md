---
title: Supported readers
keywords: [readers, backend, Sunmi, Chainway, R2, R3, USB, Bluetooth, BLE, auto-detection, trigger, external reader]
categories: [basics]
related: [requirements, settings, troubleshooting]
---
# Supported readers

All app functions — encoding, locking, scanning and validating — work on several
UHF reader backends. The reader is picked automatically by default and can be
forced in [Settings](settings).

| Backend | Connection | Hardware |
|---|---|---|
| Sunmi (built-in) | Sunmi scanner service | UHF module of a Sunmi handheld (UHF R2000 handle, Inner M500, UHF S7100, Inner SIM3500) |
| Chainway USB | USB cable / dock | Chainway desktop and handheld readers, e.g. R3 |
| Chainway Bluetooth | Bluetooth LE | Chainway handheld readers, e.g. R2 / R3 |

Tested with **Chainway R2**, **Chainway R3**, **Sunmi L2k** and **Sunmi L2s RFID**.

## Auto-detection

With the reader setting on **Auto-detect** (the default), the app probes on start
and after every settings change, in this order:

1. **Chainway USB** — a reader attached over USB is the strongest signal of intent;
2. **Sunmi** — the built-in UHF module via the Sunmi scanner service;
3. **Chainway Bluetooth** — the previously selected reader, if one was saved.

The first reader that answers wins; the About tab shows which one is active. To pin
a specific backend regardless of what is attached, choose it explicitly in
[Settings](settings).

## Chainway over USB

Attach the reader and confirm the Android USB permission prompt on first use. No
pairing is needed — the reader is picked up automatically (or select
**Chainway USB** in settings).

## Chainway over Bluetooth

1. Open [Settings](settings) and tap **Choose…** next to the Bluetooth reader row.
2. Grant the Bluetooth permission when asked (Android 12+: *Nearby devices*;
   Android 11 and older: *Location*).
3. Pick your reader from the list — it is sorted by signal strength and shows only
   devices that advertise a name, so the nearest reader is usually on top.
4. Save. The reader is remembered and reconnected automatically from then on.

## Trigger support

The hardware trigger works on every backend in the Scan tab (press to scan,
release to stop):

- **Sunmi** — the pistol-grip / side key configured as the RFID trigger;
- **Chainway R2 / R3** — the reader's own trigger button, delivered over the
  active USB or Bluetooth link.

## Power ranges

The [read/write power settings](settings) apply to all backends. Each reader clamps
the value to its supported range: Sunmi modules accept 10–33 dBm, Chainway modules
1–30 dBm.
