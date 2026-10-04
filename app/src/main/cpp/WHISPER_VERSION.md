# Pinned whisper.cpp

TransVibe vendors the official `ggml-org/whisper.cpp` source tree.

- Release: **v1.9.4**
- Commit: **927cfce34f31707e17f2bff35c349632fb9e2c3a**
- Integration: `app/src/main/cpp/third_party/whisper.cpp`

## Safe update procedure

1. Download or check out a specific official `ggml-org/whisper.cpp` release/commit; never copy isolated `.cpp` files from tutorials.
2. Replace the complete vendored tree, including `ggml/`, `include/`, `src/`, and upstream CMake files.
3. Update the release and commit recorded here and the expected version in TransVibe's `CMakeLists.txt`.
4. Configure an Android `arm64-v8a` build with the project's pinned NDK/CMake versions.
5. Build `transvibe_native`, then run JNI smoke tests and a real Tiny-model transcription test.
6. Verify upstream target names and public APIs used by `native-lib.cpp` before accepting the update.
7. Re-run model-integrity, cancellation, and long-video bounded-memory tests.

The bridge adapts to upstream API changes; TransVibe does not fork or rewrite Whisper internals.
