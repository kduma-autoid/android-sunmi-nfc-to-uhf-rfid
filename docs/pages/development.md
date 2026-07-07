---
title: Development
keywords: [development, build, Gradle, unit tests, CI, release, signing, workflow, APK]
categories: [development]
related: [installation]
---
# Development

## Building

```bash
./gradlew :app:assembleDebug
```

AGP 9 with built-in Kotlin, JDK 17+; the Sunmi `SunmiScannerSdk` AAR is bundled in
`app/libs/`.

## Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

## CI

Pushes and PRs build the APK (`.github/workflows/build.yml`); the release workflow
(`.github/workflows/release.yml`) bumps the version, tags `vX.Y.Z` and publishes the
APK to GitHub Releases.

> [!NOTE]
> Release signing uses the single `RELEASE_SIGNING` JSON secret; without it the APK
> is debug-signed.
