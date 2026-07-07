---
title: Bulk inventory and validation
keywords: [scan, inventory, trigger key, RSSI, read count, share, clear, pairing, paired, missing tag]
categories: [usage]
related: [validating, encoding]
---
# Bulk inventory and validation

Press **Start scan** or hold the **trigger key** to run a continuous UHF inventory
on the **Scan** tab. The trigger works on every [reader backend](readers): the Sunmi
RFID trigger key as well as the built-in trigger of Chainway R2/R3 handhelds over
USB or Bluetooth.

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
