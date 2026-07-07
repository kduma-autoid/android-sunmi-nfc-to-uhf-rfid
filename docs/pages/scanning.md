---
title: Bulk inventory and validation
keywords: [scan, inventory, trigger key, RSSI, read count, share, clear, pairing, paired, missing tag]
categories: [usage]
related: [validating, encoding]
---
# Bulk inventory and validation

Press **Start scan** or hold the device's **trigger key** to run a continuous UHF
inventory on the **Scan** tab.

![Scan tab, empty](assets/scan-empty.png)

Tags encoded by this app are listed with their decoded UID, live RSSI and read count;
foreign tags are only counted, not listed. The list accumulates between scans —
**Clear** resets it, **Share** exports the UID list (one per line) via the system
share sheet.

![One tag inventoried](assets/scan-tag-list.png)

## Validating cards against the inventory

Tapping NFC cards while on this tab validates card–tag pairs:

- Card whose tag has been seen over UHF → entry turns **green** (*Paired*), success
  signal.

  ![Card paired with tag](assets/scan-validation-paired.png)

- Card without a matching UHF read → **red** entry pinned to the top (*missing tag*),
  and it turns green automatically once a scan reads the tag.

  ![Card without a tag](assets/scan-validation-missing.png)
