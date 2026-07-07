---
title: Settings
keywords: [settings, reader, backend, Sunmi, Chainway, Bluetooth, access password, kill password, lock, scan duration, read power, write power, dBm]
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
| Scan duration | 3 s | Length of the UHF check in the Encode workflow and the Validate search window |
| Read power | 20 dBm | Inventory power. Keep it low — a small field makes isolating a single tag easier |
| Write power | 26 dBm | Power used for write/lock operations. Writes target one EPC, so more power is safe. Internal modules accept 18–26 dBm; each reader clamps the value to its own range (Sunmi 10–33 dBm, Chainway 1–30 dBm) |

> [!WARNING]
> **Store the passwords safely.** A tag locked with a lost access password cannot be
> re-encoded.
