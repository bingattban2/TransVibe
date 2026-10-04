# Native model-loading repair and Whisper model catalog expansion

## Root cause from device log

The bundled `app/src/main/jniLibs/arm64-v8a/libtransvibe_native.so` was linked against
`libc++_shared.so`, while the APK did not contain that runtime. Android therefore failed to
`dlopen()` the Whisper JNI library. The model download itself could finish, but the semantic
native verification necessarily failed afterwards.

## Permanent native fix

`app/src/main/cpp/CMakeLists.txt` and `.github/workflows/build-native.yml` now force
`c++_static`. The workflow also runs `readelf` and fails if a newly built
`libtransvibe_native.so` still declares `libc++_shared.so` as a dependency.

The prebuilt `.so` currently committed in `jniLibs` predates this fix. Run the GitHub
`Build TransVibe Native` workflow once and replace it with the produced
`artifact/arm64-v8a/libtransvibe_native.so`, then rebuild the Android app.

## Diagnostics

Native model verification now preserves a useful diagnostic. A missing C++ runtime is reported
as a native-initialization problem instead of looking like a bad network/model download.

## Whisper catalog

The in-app downloadable standard GGML catalog now includes:

- Tiny / Tiny.en
- Base / Base.en
- Small / Small.en
- Medium / Medium.en
- Large v1 / Large v2 / Large v3
- Large v3 Turbo

The `.en` models are explicitly marked English-only. Preflight requires manual English source
selection for them because they cannot perform multilingual language detection.
