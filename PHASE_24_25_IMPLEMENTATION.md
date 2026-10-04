# PHASE 24–25 implementation

## Phase 24 — WakeLock ownership

- `SubtitleProcessingService.process()` is the sole owner of the media-processing WakeLock.
- The WakeLock is acquired once at the beginning of `process()` inside its `try` block.
- The matching release is in `finally`, so success, failure and cancellation all release the lock.
- `onTimeout()` and `onDestroy()` retain idempotent safety releases for Android-driven service termination paths.
- Segment/chunk/translation callbacks no longer acquire, release or renew the WakeLock.
- The WakeLock is non-reference-counted and has no rolling callback timeout; its lifetime matches active processing.

## Phase 25 — local bounded VAD

New independent media component:

`VoiceActivityDetector` → `LightweightVoiceActivityDetector`

Pipeline:

`AudioDecoder -> bounded AudioChunk channel -> local VAD -> SpeechRegion[] -> Whisper`

Properties:

- local: no network or external model;
- lightweight: short-frame scalar features only;
- bounded: works inside the decoder's bounded chunk and allocates one fixed decision array for that chunk;
- cancelable: checks coroutine cancellation throughout frame scanning and region assembly;
- UI independent: the VAD package has no Android UI/service dependency.

The detector uses short-frame RMS energy, zero-crossing rate, crest factor, first-difference energy, temporal smoothing, minimum speech duration, hangover, padding and region merging. Silence, stationary tonal music and broadband background noise are rejected before Whisper. Long pauses split speech into separate regions.

The service logs VAD input samples, speech samples and speech-region count with the existing audio-pipeline metrics so reduction in Whisper input can be measured on-device.

## Verification

A standalone Kotlin execution of the VAD implementation produced:

- silence: 0 regions
- quiet speech: 1 region
- music test tone: 0 regions
- background noise: 0 regions
- speech: 1 region
- speech + long pause + speech: 2 regions

`LightweightVoiceActivityDetectorTest` contains the required silence, quiet speech, music, background-noise and speech tests plus a long-pause split test.

Full Gradle test execution could not run in the current offline environment because the Gradle 9.6.1 wrapper distribution is not cached and `services.gradle.org` is unreachable. The VAD implementation itself was compiled and executed directly with the locally installed Kotlin compiler and coroutines runtime.
