---
title: Settings
keywords: [settings, reader, backend, Sunmi, Chainway, Bluetooth, access password, kill password, lock, scan duration, read power, write power, dBm, inventory mode, high speed, balanced, traversal, session]
categories: [usage]
related: [readers, encoding, troubleshooting]
---
# Settings

Open the settings with the gear icon in the top bar.


~~~gallery
![Settings (top)](assets/settings.png)
![Settings (bottom)](assets/settings-bottom.png)
![Settings (tablet)](assets/settings_l.png)
~~~

| Setting | Default | Description |
|---|---|---|
| UHF reader | Auto-detect | Which [reader backend](readers) to use: Auto-detect, Sunmi (built-in), Chainway USB or Chainway Bluetooth |
| Bluetooth reader | — | The Chainway reader used by the Bluetooth backend; **Choose…** scans for nearby readers and remembers the selection |
| Access password | — | 8 hex digits. Used to lock tags and to re-encode tags locked by this app |
| Kill password | — | 8 hex digits. Written to the tag so nobody else can set one and kill the tag |
| Lock tags after writing | on | Requires both passwords to be set and non-zero |
| Inventory mode | High speed | How tags respond to a [Scan tab](scanning) inventory — see *Inventory modes* below |
| Scan duration | 3 s | Length of the UHF check in the Encode workflow and the Validate search window |
| Read power | 20 dBm | Inventory power. Keep it low — a small field makes isolating a single tag easier |
| Write power | 26 dBm | Power used for write/lock operations. Writes target one EPC, so more power is safe. Internal modules accept 18–26 dBm; each reader clamps the value to its own range (Sunmi 10–33 dBm, Chainway 1–30 dBm) |

> [!WARNING]
> **Store the passwords safely.** A tag locked with a lost access password cannot be
> re-encoded.

## Inventory modes

The inventory mode selects the EPC Gen2 *session* used while scanning. A session
controls how long a tag stays quiet after it has been read once — the trade-off is
between re-reading the same tag quickly (live RSSI, finding one tag) and giving
every tag in the field a chance to answer (bulk inventory):

- **High speed** (session S0) — tags answer continuously and the same tag is
  re-read many times per second. Best for working with a single tag: the read
  count climbs and the RSSI updates live, so you can locate a tag by signal
  strength.
- **Balanced** (session S1) — a tag stays quiet for a short while (roughly
  0.5–5 s) after each read, freeing air time for the other tags. A middle ground
  when a handful of tags are in the field.
- **Traversal** (session S2) — a tag that has been read stays quiet for a long
  time, so each tag answers essentially once per scan. Best for counting many
  tags at once; expect read counts of 1 and no live RSSI updates.

The mode applies **only to the Scan tab**. Encoding, validation and the
post-write verification always scan in high speed — a tag silenced by the S1/S2
quiet period could otherwise be missed and fail the check falsely.
