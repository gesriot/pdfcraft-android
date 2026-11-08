# Android app

Native Kotlin / Jetpack Compose frontend over `crates/android-bridge` (UniFFI).
The application ID remains `org.pdfcraft.android`, versionCode 8 / versionName
0.8.0. Builds target API 36, require API 26 or later, and package ARM64 only.

## Building

Use [ENVIRONMENT.md](ENVIRONMENT.md) to prepare Ubuntu ARM64 in Termux/proot-distro.
Run commands from the repository root:

```sh
export PDFCRAFT_BUILD_ROOT="$HOME/.local/share/pdfcraft-build"
PDFCRAFT_BUILD_TYPE=debug sh android/build-local.sh
```

The default is `PDFCRAFT_BUILD_TYPE=release`, which requires private release signing
configuration. Both variants build optimized Rust with two jobs, generate matching
Kotlin bindings, package the APK and run the variant's Kotlin unit tests. The debug
variant uses Android's development certificate; the release variant never falls
back to it. R8 is disabled because the generated JNA reflection path needs separate
keep-rule and device validation. The script does not install the app.

Outputs with that build root:

- Debug: `$PDFCRAFT_BUILD_ROOT/android/app/outputs/apk/debug/app-debug.apk`
- Release: `$PDFCRAFT_BUILD_ROOT/android/app/outputs/apk/release/app-release.apk`

## Release signing

Keep the existing release keystore and a private backup outside the checkout.
Do not replace it when moving the project: updates to an installed release need
its signing identity. Keep `org.pdfcraft.android` unchanged for those updates too.

Copy [signing.properties.example](signing.properties.example) to a private directory,
fill in the values locally, and restrict access to the directory and files. Use an
absolute `storeFile` path. Required properties are `storeFile`, `storePassword`,
`keyAlias` and `keyPassword`. Never commit the populated file or keystore.

```sh
export PDFCRAFT_SIGNING_PROPERTIES=/absolute/private/path/signing.properties
sh android/build-local.sh
```

Without an override, Gradle looks in the Java user's home directory at
`.local/share/pdfcraft/signing.properties`. This preserves the existing root-user
proot location. An absent file fails the release build explicitly. A debug APK
has a different certificate from a release APK and cannot update it in place.

## Native output contract

Gradle expects `PDFCRAFT_NATIVE_DIR` to contain both outputs from the same Rust build:

```text
jniLibs/arm64-v8a/libpdfcraft_android_bridge.so
kotlin/org/pdfcraft/android/engine/pdfcraft_android_bridge.kt
```

`build-local.sh` produces them automatically. For another prepared cross-compilation
host, build the library using that host's Android NDK linker and run a **host** binding
generator against the resulting library:

```sh
cargo build --locked --release -p pdfcraft-android-bridge --target aarch64-linux-android
cargo run --locked -p pdfcraft-android-bridge --features bindgen --bin pdfcraft-bindgen -- \
  generate --library PATH_TO_ANDROID_SO --language kotlin --metadata-no-deps --no-format \
  --crate pdfcraft_android_bridge --out-dir PATH_TO_NATIVE_DIR/kotlin
```

Copy the `.so` to the `jniLibs/arm64-v8a/` location above. Gradle fails if either
output is missing. Generated Kotlin bindings and native libraries are not sources
to commit. `PDFCRAFT_ANDROID_BUILD_DIR` controls Gradle's output location; the local
build script sets it from `PDFCRAFT_BUILD_ROOT`.

Gradle copies `../app-icon.png` into generated launcher resources and the existing
Inter Regular font and OFL notice from `../assets/fonts/` into assets. Keep these
relative paths intact when moving the complete repository.

## Behaviour and limits

- Opening imports a private cache copy. Save Copy uses a new destination from the
  system file picker; the source file is not overwritten. Cache is not durable recovery.
- Required opening passwords remain enforced. Advisory edit/copy/export restrictions
  are not enforced by this Android frontend. Desktop and automation defaults are unchanged.
- Existing-text edits and added FreeText annotations use a PDF preview before applying;
  successful edits are undoable. Inter supports Russian/English replacement text.
- Page organization supports moving, rotating, deleting and inserting pages, blank
  pages and images. Selected operations are capped at 500 pages. Whole-document page
  image export proceeds serially and reports any resolution reduction.
- DOCX export preserves editable text, simple inferred tables and inline PNG/JPEG
  where supported. Scans, rotated text and failed extraction can become page images.
  Complex layout, original pagination and independently editable FreeText are not promised.
  The DOCX is unencrypted, including when its source PDF was encrypted.
- Input is capped at 64 MiB. Rendering uses bounded tiles and caches. Native work already
  running may finish before cancellation takes effect. OCR, comment threads, cross-page
  selection and process-death recovery remain outside the Android feature set.

See [TESTING.md](TESTING.md) before distributing a build. Pure Rust or Kotlin test
success does not establish Android touch, file-provider or lifecycle behaviour.
