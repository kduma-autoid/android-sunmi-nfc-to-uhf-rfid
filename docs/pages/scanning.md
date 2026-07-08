---
title: Bulk inventory and validation
keywords: [scan, inventory, trigger key, RSSI, read count, share, clear, pairing, paired, missing tag, inventory mode, high speed, balanced, traversal, session]
categories: [usage]
related: [validating, encoding]
---
# Bulk inventory and validation

Press **Start scan** or hold the **trigger key** to run a continuous UHF inventory
on the **Scan** tab. The trigger works on every [reader backend](readers): the Sunmi
RFID trigger key as well as the built-in trigger of Chainway R2/R3 handhelds over
USB or Bluetooth.

How tags respond to the inventory is controlled by the **Inventory mode**
[setting](settings), which selects the EPC Gen2 session used on this tab:

- **High speed** (default) — tags answer continuously; best for finding a single
  tag and watching its live RSSI.
- **Balanced** — each tag stays quiet for a moment after it has been read, giving
  the others more air time.
- **Traversal** — each tag answers once per scan; best for taking inventory of
  many tags at once.

The mode applies only to this tab — encoding, validation and the post-write
verification always scan in high speed.

Tags encoded by this app are listed with their decoded UID, live RSSI and read count;
foreign tags are only counted, not listed. The list accumulates between scans —
**Clear** resets it, **Share** exports the UID list (one per line) via the system
share sheet.

## Validating cards against the inventory

Tapping NFC cards while on this tab validates card–tag pairs:

- Card whose tag has been seen over UHF → entry turns **green** (*Paired*), success
  signal.
- Card without a matching UHF read → **red** entry pinned to the top (*missing tag*),
  and it turns green automatically once a scan reads the tag.

~~~gallery w=180 scroll
![Empty](assets/scan-empty.png)

![Inventory](assets/scan-tag-list.png)

![Paired](assets/scan-validation-paired.png)
The card's tag was seen over UHF — the entry turns **green**.

![Missing](assets/scan-validation-missing.png)
No matching UHF read — pinned **red** at the top.
~~~

~~~gallery w=180 scroll
![Empty (tablet)](assets/scan-empty_l.png)

![Inventory (tablet)](assets/scan-tag-list_l.png)

![Paired (tablet)](assets/scan-validation-paired_l.png)

![Missing (tablet)](assets/scan-validation-missing_l.png)
~~~
