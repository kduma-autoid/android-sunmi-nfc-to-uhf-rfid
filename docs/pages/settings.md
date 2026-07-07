---
title: Settings
keywords: [settings, access password, kill password, lock, scan duration, read power, write power, dBm]
categories: [usage]
related: [encoding, troubleshooting]
---
# Settings

Open the settings with the gear icon in the top bar.

![Settings](assets/settings.png#w=300&h=75vh)

| Setting | Default | Description |
|---|---|---|
| Access password | — | 8 hex digits. Used to lock tags and to re-encode tags locked by this app |
| Kill password | — | 8 hex digits. Written to the tag so nobody else can set one and kill the tag |
| Lock tags after writing | on | Requires both passwords to be set and non-zero |
| Scan duration | 3 s | Length of the UHF check in the Encode workflow and the Validate search window |
| Read power | 20 dBm | Inventory power. Keep it low — a small field makes isolating a single tag easier |
| Write power | 26 dBm | Power used for write/lock operations. Writes target one EPC, so more power is safe. Internal modules accept 18–26 dBm |

> [!WARNING]
> **Store the passwords safely.** A tag locked with a lost access password cannot be
> re-encoded.
