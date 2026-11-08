# PdfCraft for Android

An independent Android port of [PdfCraft](https://github.com/storytold/pdfcraft),
with a native Kotlin / Jetpack Compose interface and the Rust PDF engine connected
through UniFFI. Based on PdfCraft by the ArtCraft team and contributors.

The current Android app is **0.8.0**, for **Android 8.0+ (API 26)** on
**ARM64 (`arm64-v8a`)**. The Rust workspace retains its upstream version separately.
This fork is not an official ArtCraft release.

## Features

- Continuous page scrolling, pinch zoom and rendering of visible page regions.
- Open password-protected PDFs, search Russian/English text, select and copy text.
- Edit existing text with a PDF preview; add visible text annotations.
- Rotate, move, delete and insert pages; combine PDFs and images; create blank PDFs.
- Remove selected image placements and undo/redo edits.
- Save a copy, extract pages, export pages as PNG/JPEG, and export to Word (`.docx`).

The app imports a private working copy and uses Android's system file picker to
save to a chosen destination. Opening passwords remain required. This frontend
does not enforce advisory PDF permissions after opening a document. Extracted
PDFs and DOCX exports are unencrypted; saving a copy retains source encryption.

## Limits

OCR is not implemented in the Android interface. Text editing needs an existing
text layer; it cannot edit letters that are only pixels or outlines. DOCX export
supports editable text, simple tables and images, with raster fallback for some
pages; it does not promise the original pagination or complex layout. Process-death
recovery and the full upstream desktop feature set are not implemented for Android.
See [Android details](android/README.md) and [testing](android/TESTING.md).

## Build

The build scripts target **Ubuntu ARM64 inside Termux/proot-distro**. They need a
prepared Rust toolchain, JDK, Android SDK/NDK and Linux host tools outside this
checkout. Official x86-64 SDK aapt2 can run through QEMU; the compiler runs natively
on ARM64. Copying the repository alone does not supply that environment.

Follow [environment setup](android/ENVIRONMENT.md), then run from the repository root:

```sh
# Use the same persistent Linux-filesystem directory selected during setup.
# /tmp can be a small RAM-backed filesystem in proot, unsuitable for this build.
export PDFCRAFT_BUILD_ROOT="$HOME/.local/share/pdfcraft-build"

# A development APK uses the local debug certificate; no private release key is needed.
PDFCRAFT_BUILD_TYPE=debug sh android/build-local.sh

# A release APK requires your own private signing.properties and keystore.
PDFCRAFT_SIGNING_PROPERTIES=/absolute/private/path/signing.properties sh android/build-local.sh
```

With this setting, APKs are under `$PDFCRAFT_BUILD_ROOT/android/app/outputs/apk/`;
Rust temporary files are under `$PDFCRAFT_BUILD_ROOT/tmp/`.
The script prints the exact APK path. See [signing and native build details](android/README.md).
The transferred source tree must be rebuilt and tested on the target environment;
older build records do not certify a new checkout. No APK, signing key, generated
native library or local build cache is committed here.

## Repository layout

| Path | Purpose |
|---|---|
| `android/` | Kotlin/Compose app, Gradle Wrapper, build scripts and Android tests |
| `crates/android-bridge/` | UniFFI adapter between Android and the Rust engine |
| `crates/` | PDF engine, feature libraries, CLI automation and retained desktop UI |
| `assets/`, `vendor/` | Attributed resources and pinned source patches |
| `apps/`, `xtask/` | Retained upstream applications and validation tools |
| `docs/`, `parity/`, `ROADMAP.md` | Primarily upstream documentation and feature records |

Desktop and web sources remain to preserve the workspace and shared checks. Their
feature matrix and inherited GitHub workflows are not Android build or device-test
evidence. Android validation is described in [android/TESTING.md](android/TESTING.md).

## Licence and credits

Code is licensed under **MIT OR Apache-2.0**; see [LICENSE-MIT](LICENSE-MIT),
[LICENSE-APACHE](LICENSE-APACHE) and [NOTICE](NOTICE). Original upstream copyright
and third-party notices are retained.

The Android launcher icon is original artwork supplied by this fork's maintainer,
licensed under the same terms. Asset provenance and hashes are in
[ATTRIBUTION.toml](ATTRIBUTION.toml); [ATTRIBUTION.md](ATTRIBUTION.md) is generated
from it. Upstream ArtCraft brand marks are excluded from this fork.
