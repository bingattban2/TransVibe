# PHASE 26–27 implementation

## PHASE 26 — source-language policy

Three explicit modes are implemented through `SourceLanguagePolicy`:

- `MANUAL` — persisted as `manual:<language>`; no language detection is run.
- `AUTO_VIDEO` — persisted as `auto_video`; detects once from the first VAD-approved speech region and persists the result in the processing job.
- `AUTO_ADAPTIVE` — persisted as `auto_adaptive`; uses the same initial detection, then allows redetection only after three consecutive low-confidence recognition observations and applies a cooldown between redetection attempts.

Legacy `auto`, `ar`, and `en` settings are migrated without losing behavior.

Recognition quality uses bounded evidence: usable output text, repetition/hallucination checks, and (when the updated native library is installed) average token probability and Whisper no-speech probability. Detection is never run unconditionally per chunk.

## PHASE 27 — bounded context

`params.no_context` remains `true`. Whisper's unbounded internal prior-text context stays disabled.

A separate `RollingWhisperPrompt` keeps at most 3 finalized prior subtitle sentences and at most 240 characters. It is refreshed only after the current chunk has been durably persisted. Current/unverified recognition is never fed back into the current chunk. Duplicate and highly repetitive text is rejected to reduce hallucination propagation.

The native bridge accepts the bounded prompt as `initial_prompt`, caps `n_max_text_ctx` to 64 tokens, and keeps `carry_initial_prompt=false`.

## Native binary compatibility

The project still contains the previously bundled `libtransvibe_native.so` so existing app builds do not crash. `WhisperNative` falls back safely if that older binary does not expose the new prompt JNI entry point. To activate the native prompt and token-confidence metadata, rebuild `libtransvibe_native.so` from the updated `app/src/main/cpp` sources and replace the bundled arm64-v8a library. `.github/workflows/build-native.yml` is included for this purpose.
