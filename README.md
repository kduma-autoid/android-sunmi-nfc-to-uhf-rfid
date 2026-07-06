# NFC to RFID

Android app for Sunmi handhelds (L2k / L2s / L2H / L3) with a UHF RFID module.
It copies the UID of an NFC card onto a UHF EPC Gen2 tag — so a long-range UHF scan
returns the same identifier the card reports over NFC.

| Encode | Scan & validate | Validate one-by-one |
|---|---|---|
| ![Encode](screenshots/encode-confirm-write.png) | ![Scan](screenshots/scan-validation-paired.png) | ![Validate](screenshots/validate-paired.png) |

## Features

- **Encode** — tap a card, confirm, and its UID is written into the tag's EPC bank
  (96-bit EPC: `4E` + UID length + UID, PC word `0x3100`, non-GS1/ISO namespace)
- **Lock** — tags are protected with access/kill passwords and bank locks
- **Scan** — bulk inventory of encoded tags with live card–tag pairing validation
- **Validate** — instant PAIRED / TAG MISSING verdict per card
- **NXP originality check** — the card's silicon is verified against NXP public keys on every tap
- Sound + vibration + full-screen green/red flash feedback; English and Polish UI

Full manual, encoding format details and troubleshooting: **[docs.md](docs.md)**

## Getting the app

The app is available in the **Sunmi App Store** on Sunmi devices.
Alternatively, download the APK from [Releases](../../releases) or build it yourself:

```bash
./gradlew :app:assembleDebug
```

Requires JDK 17+; the Sunmi `SunmiScannerSdk` AAR is bundled in `app/libs/`.
The app needs the Sunmi scanner service (preinstalled on Sunmi devices) and an attached
or built-in UHF module.

## CI

- [build.yml](.github/workflows/build.yml) — APK build + unit tests on every push/PR, manually,
  or as part of a release
- [release.yml](.github/workflows/release.yml) — version bump, `vX.Y.Z` tag, GitHub Release with
  the APK attached (run manually from the Actions tab)

## License

[MIT](LICENSE)
