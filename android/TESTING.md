# Android validation

The source import is not a new device-tested release. Historical local build logs
and APKs are not included in the repository. Run the checks below from a clean
checkout on the prepared Ubuntu ARM64 / Android environment before committing a
release claim. The inherited desktop feature matrix and CI do not certify Android.

## Automated checks

The build-wrapper environment checks can also run on macOS or Linux without an SDK:

```sh
python3 -m unittest discover -s android/tests -v
```

They substitute external build commands and check temporary-directory propagation;
they do not compile Rust, Kotlin or an APK or exercise QEMU/proot.

From the repository root, with the environment in [ENVIRONMENT.md](ENVIRONMENT.md):

```sh
export PDFCRAFT_BUILD_ROOT="$HOME/.local/share/pdfcraft-build"
sh android/check-local.sh
sh android/check-native-local.sh
PDFCRAFT_BUILD_TYPE=debug sh android/build-local.sh
```

- `check-local.sh`: Rust formatting, bridge host tests and clippy, layering and assets.
- `check-native-local.sh`: bridge tests compiled for Android ARM64 and executed via
  the Android environment. This is not an installed-app UI test.
- `build-local.sh`: optimized Rust library, generated UniFFI bindings, APK packaging
  and Kotlin unit tests for the selected debug/release variant.

For engine, annotation, export or automation changes, also run the affected Rust
crate tests and clippy. Keep the repository's wasm, licensing and parity checks.
The Android adapter's wasm compatibility has not been established by this import;
record any gate failures rather than omitting them from the report.

Before distributing a release, build with the private release key, run Android lint,
and verify the **exact final APK** with SDK `apksigner` and `zipalign`. Check its
package/version, `debuggable=false`, signing identity, ARM64 libraries, Android-only
ELF dependencies, 16 KiB ZIP/ELF alignment, and bundled font/license files. Do not
publish a debug APK as a signed release or include signing credentials in reports.

## Device checks

| Area | Check on an installed build |
|---|---|
| Launch and loading | Cold launch; open a synthetic PDF; correct and incorrect opening passwords |
| Viewer | Scroll a long document; pinch/pan across pages; rotation and high-zoom detail |
| Reading | Russian/English search, selection handles, clipboard and pages without text |
| Editing | Preview/apply/cancel text edits and new text; undo/redo; reopen a saved copy |
| Pages | Move, rotate, insert and delete; protect the final page; extract selected pages |
| Exports | PNG/JPEG and DOCX; destination cancellation, retry, low storage and partial failure |
| Passwords | Save/reopen encrypted copies; make unencrypted export behaviour clear |
| Lifecycle | Keyboard, activity recreation, background/foreground, unsaved-work confirmation |
| Performance | Large inputs, rapid navigation, bounded memory and cancellation |
| Accessibility | TalkBack labels, focus order, font scaling and touch targets |
| Upgrade | Install over the previous release with its existing signing key and preserved data |

Use contributor-original synthetic fixtures and allowed fonts/images. Keep personal
PDFs, device identifiers, private paths and credentials out of committed test data.
Record the source commit, tool versions, commands actually run and observed results.
Do not infer on-device success from a build log or a host unit test.
