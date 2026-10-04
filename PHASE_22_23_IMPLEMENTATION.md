# Phases 22–23 implementation

## Phase 22
- User-visible modes: FAST, BALANCED, PRESERVE_ORIGINAL.
- FAST/BALANCED cap foreign-language Whisper work at one inference pass.
- For non-Arabic/non-English speech targeting Arabic, FAST/BALANCED use:
  audio -> Whisper speech-to-English -> ML Kit English-to-Arabic -> Arabic subtitle.
- PRESERVE_ORIGINAL may run source-language transcription plus speech-to-English (up to two Whisper passes).
- Legacy POWER_SAVE preferences/jobs migrate to BALANCED; thermal/power protection remains automatic runtime policy.
- Processing configuration UI explicitly states the inference-cost difference.

## Phase 23
- Decoder and inference now communicate through a bounded Channel<AudioChunk>.
- Normal capacity: 1 chunk. Thermal/power constrained capacity: 0 (rendezvous; no prefetch).
- Whisper inference remains single-consumer through the existing limitedParallelism(1) dispatcher.
- Cancellation cancels native inference, the service job, producer, channel, and consumer scope.
- Runtime log metrics record pipeline elapsed time, queue capacity, and thermal state at start/end.

## Validation in this environment
- XML parsing passed.
- Static Phase 22/23 invariants passed.
- Gradle compilation could not run because Gradle 9.6.1 was not cached and the sandbox cannot access services.gradle.org.
- Device-level total-time/thermal comparison must therefore be confirmed on supported Android hardware; runtime metrics are included for that verification.
