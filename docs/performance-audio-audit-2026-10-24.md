# Performance, audio quality, and speed audit

This is the historical baseline. Remediation and current verification are recorded in [the remediation report](audit-remediation-2026-10-24.md); source line references below describe the pre-remediation code.

Audited 2026-10-24 at commit `0d2f9f8`, including the existing working-tree change to `ModelDownloadCard.kt`. Application behavior was not changed. Scope covers microphone capture, segmentation, coroutine scheduling, JNI, the active native ASR path and build configuration, model management, Compose, packaging, and tests. Unused vendor services and model families were not exhaustively reviewed.

The largest opportunities are removing repeated offline recognition, making native-session ownership safe, preserving speech at stop and segment boundaries, and enabling the intended CPU instructions consistently.

## Verified baseline

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest --rerun-tasks` | 3 tests passed |
| `:app:lintDebug --rerun-tasks` | Passed, 19 warnings, no errors |
| `:app:assembleDebug` | Passed; APK 65.88 MiB |
| `:app:assembleRelease` | Passed; unsigned APK 48.66 MiB |
| `:app:compileDebugAndroidTestKotlin` | Failed: unresolved `MainScreen`, test line 17 |
| Bundled JFK WAV | PCM16, mono, 16 kHz, exactly 11 seconds |
| Installed model header | `asr.encoder.cache_supported=false`; sample rate 16 kHz |
| Device memory snapshots | Approximately 914 MiB PSS before the sweep and 982 MiB afterward |

The connected Galaxy S22 Ultra (`SM-S908E`, Android 16/API 36) already had the app and model installed. The built-in JFK benchmark was run twice at each thread count, in order 2, 6, 8, 4, with idle model reloads between settings. Four threads was restored. These measurements describe the pre-existing installed app, which was not replaced by the APKs built during this audit.

| CPU threads | JFK inference, two runs | Inference RTF |
| --- | --- | --- |
| 2 | 4,236 / 4,094 ms | 0.385 / 0.372 |
| 4 | 2,156 / 1,850 ms | 0.196 / 0.168 |
| 6 | 5,472 / 5,299 ms | 0.497 / 0.482 |
| 8 | 6,185 / 5,790 ms | 0.562 / 0.526 |

RTF is inference seconds divided by input seconds; lower is better. Two earlier four-thread runs took 2,522 and 2,234 ms. This small sequential sweep was not thermally controlled; the final device thermal status was 1. It supports retaining four threads on this phone, not a universal thread recommendation. The memory difference is a snapshot, not proof of a leak. Audio-quality changes, battery impact, sustained live throughput, and optimization speedups have not been measured.

## Findings, in priority order

### 1. P1: Native recognizers can be freed during recognition

References: [model reload](../app/src/main/java/com/example/offlinetranscription/engine/LiveTranscriptionEngine.kt#L65), [captured live session](../app/src/main/java/com/example/offlinetranscription/engine/LiveTranscriptionEngine.kt#L84), [thread selector](../app/src/main/java/com/example/offlinetranscription/ui/TranscriptionViewModel.kt#L112), [unsynchronized session](../app/src/main/java/com/example/offlinetranscription/nemo/NemoSpeechJni.kt#L36).

Changing thread count starts `loadModel()` on another coroutine, which immediately closes the current session. Live recognition and the JFK benchmark can still be inside a synchronous JNI call using that handle. The `closed` Boolean does not synchronize recognition with destruction. This creates a native use-after-free risk. Even when no call overlaps destruction, the live collector retains the old session and subsequent recognition fails. Rapid reloads can also overlap each other.

Give one worker ownership of session creation, inference, reconfiguration, and destruction. Finish or join outstanding work before closing, and restart live processing against the replacement session. Serialize reload requests and prevent benchmark work from competing with a live session. Coroutine cancellation alone cannot interrupt synchronous native computation. This race is established from code; a native crash was not deliberately induced.

### 2. P1: Stop loses the unfinished utterance and races with restart

References: [engine stop](../app/src/main/java/com/example/offlinetranscription/engine/LiveTranscriptionEngine.kt#L152), [recorder cleanup](../app/src/main/java/com/example/offlinetranscription/audio/AudioRecordSource.kt#L94), [recorder stop](../app/src/main/java/com/example/offlinetranscription/audio/AudioRecordSource.kt#L108).

Stop cancels processing, resets accumulated samples, and clears interim text without final recognition. A short utterance stopped before its first partial can vanish entirely. A native call already running can still publish after stop or clear because the code performs no session-generation check after JNI returns. Stop also does not join recorder cleanup; an old recorder's `finally` block can set the shared recording flag to false after a new recorder starts. The segmenter is reset on the UI thread while its worker may still be using its mutable buffer.

Implement an ordered stop: stop and finish capture, drain accepted audio, flush the segmenter, recognize the final tail, join processing, then publish stopped state. Keep segmenter mutations on its owner worker and use a generation identifier for obsolete results. Validate short utterances, rapid stop/start, and stop during inference.

### 3. P1: Repeated full-prefix inference amplifies work and blocks audio consumption

References: [partial snapshots](../app/src/main/java/com/example/offlinetranscription/audio/VadSegmenter.kt#L63), [serial collector/inference](../app/src/main/java/com/example/offlinetranscription/engine/LiveTranscriptionEngine.kt#L100), [offline JNI call](../app/src/main/cpp/nemo_speech_jni.cpp#L110), [capture queue](../app/src/main/java/com/example/offlinetranscription/audio/AudioRecordSource.kt#L32).

`isFinal=false` only labels the Kotlin result; JNI still invokes the whole-utterance offline API. A probe using the compiled segmenter and production settings produced partials at 0.96, 1.92, 2.88, 3.84, 4.80, and 5.76 seconds, followed by a 6.08-second final. That submits **26.24 seconds of audio for 6.08 seconds captured**, a **4.316x input-volume multiplier**. This is a measurement of submitted audio, not a measured 4.316x CPU cost. With approximately linear inference cost, the raw inference RTF would need to stay below approximately **0.232** to keep up, before other overhead.

Inference runs inside the only audio collector. The 64-chunk SharedFlow buffer permits roughly **10.24 seconds of queued audio**. When full, `_audioChunks.emit()` suspends the capture worker and prevents further `AudioRecord.read()` calls, risking hardware-buffer overrun. Queue age and overruns are not reported.

Separate prompt capture/VAD consumption from a single inference scheduler. Coalesce superseded partial requests, preserve accepted PCM and final endpoints, prioritize finals, and reduce partial frequency when inference falls behind. Bound and report queue age. The installed model explicitly lacks cache streaming, and [the native runner rejects that path](../app/src/main/cpp/vendor/nemo_speech/src/asr/recognizer.cpp#L218). A cache-streaming model is an architectural option that needs separate quality and compatibility validation.

### 4. P1: Fixed RMS gating drops quiet speech and leading sounds

References: [threshold and accumulation](../app/src/main/java/com/example/offlinetranscription/audio/VadSegmenter.kt#L10), [capture frame duration](../app/src/main/java/com/example/offlinetranscription/audio/AudioRecordSource.kt#L21).

Any initial chunk below RMS 0.012 is discarded. There is no pre-roll or adaptive noise floor. Quiet onsets can be lost before a louder vowel starts the segment; sustained background noise can trigger unnecessary recognition. A probe confirmed that 6.08 seconds at RMS 0.01 produces no events and that an initial RMS 0.005 frame is absent from the later endpoint.

Use shorter VAD frames, a primitive pre-roll buffer of roughly 200-300 ms, and onset/offset hysteresis. Evaluate an adaptive energy gate or the already-vendored Silero VAD on quiet speech, far-field speech, fans, and keyboard noise. Choose thresholds using missed-speech and false-trigger rates together with transcript WER. Increasing gain after samples have been discarded cannot recover them.

### 5. P2: CPU feature flags miss C quantization kernels

Reference: [C++-only architecture flags](../app/build.gradle.kts#L35).

The generated compilation database shows `-march=armv8-a+dotprod+fp16` on C++ files, including JNI and `ggml-cpu.cpp`, but not C files such as `ggml-cpu/arch/arm/quants.c`. Compiler macro probes confirmed that the C kernel lacks `__ARM_FEATURE_DOTPROD` and FP16 arithmetic macros while C++ has them. Relevant C kernels contain DOTPROD-specific paths. Both languages already receive `-O3`; optimization level is not the missing setting.

Apply feature configuration consistently at the ggml target level, for example through its ARM architecture option for a capability-specific build. Retain a baseline/runtime-dispatched path for supported phones whose CPUs lack these optional features. Benchmark the actual Q8 model before claiming a gain; this audit did not change or benchmark the flags.

### 6. P2: Endpoint timing adds delay and forced splits discard context

References: [VAD timing defaults](../app/src/main/java/com/example/offlinetranscription/audio/VadSegmenter.kt#L13), [reset at endpoint](../app/src/main/java/com/example/offlinetranscription/audio/VadSegmenter.kt#L56).

At 160-ms capture frames, the 850-ms partial interval becomes **960 ms**, and the 550-ms silence threshold becomes **640 ms**. Both precede inference and queue delay. The nominal six-second hard limit becomes 6.08 seconds and resets without overlap, potentially splitting a word or phrase mid-speech.

Use smaller segmentation frames independently of inference cadence. Prefer quiet boundaries near the size limit; use overlap and transcript reconciliation for mandatory mid-speech splits. Tune endpoint delay against pause-related premature splits on real speech. Increasing the maximum indefinitely would also increase offline inference and memory costs.

### 7. P2: Recorder errors can cause a busy loop and stale recording state

Reference: [read loop](../app/src/main/java/com/example/offlinetranscription/audio/AudioRecordSource.kt#L74).

Only positive reads are handled. Persistent negative returns, including `ERROR_DEAD_OBJECT`, can spin without recovery while the UI continues to show recording. Startup exceptions occur before the loop's cleanup block and are not surfaced through a UI error state.

Handle read failures explicitly: recreate invalid recorders when appropriate, terminate on unrecoverable errors, and publish the cause. Check startup state and buffer-size errors, release partially initialized resources, and make blocking-read shutdown deterministic. Android documents that `ERROR_DEAD_OBJECT` requires recreation in its [AudioRecord reference](https://developer.android.com/reference/android/media/AudioRecord).

### 8. P2: Model replacement can destroy a working setup before validation

References: [readiness check](../app/src/main/java/com/example/offlinetranscription/model/ModelManager.kt#L47), [download promotion](../app/src/main/java/com/example/offlinetranscription/model/ModelManager.kt#L94), [import promotion](../app/src/main/java/com/example/offlinetranscription/model/ModelManager.kt#L110), [ignored initialization failures](../app/src/main/java/com/example/offlinetranscription/ui/TranscriptionViewModel.kt#L134).

Readiness means only that a file exceeds 100 MiB. Download and import remove the old model before checking `renameTo()` success or model compatibility. An incompatible or truncated import can replace a working model. Download/import callers ignore the subsequent `loadModel()` result and clear the status message, hiding initialization failure. Blocking download/copy operations also lack explicit cancellation checks, resumable download support, and mutation serialization.

Validate GGUF metadata, model architecture/capabilities, and expected size/hash for the pinned download before atomic promotion. Preserve a usable rollback copy and check file-operation results. Propagate initialization failures, serialize replacements with inference ownership, and make downloads resumable and cancellation-aware. Close the HTTP response on all paths. The configured NVIDIA download URL currently exists; the issue is local validation and replacement behavior.

### 9. P2: Benchmark and quality indicators omit important costs

References: [native timing](../app/src/main/cpp/nemo_speech_jni.cpp#L113), [JFK benchmark](../app/src/main/java/com/example/offlinetranscription/ui/TranscriptionViewModel.kt#L159), [default decoder confidence](../app/src/main/cpp/vendor/nemo_speech/src/asr/decoders/decoder.h#L226), [confidence display](../app/src/main/java/com/example/offlinetranscription/ui/components/TranscriptCard.kt#L92).

The latency badge times the native inference call. It excludes capture framing, speech-end detection, queue age, most JNI transfer overhead, and UI publication. The JFK path bypasses live VAD and partial reprocessing entirely, so a good offline RTF does not prove live responsiveness. The TDT decoder inherits the base confidence value of 1.0; presenting this as **100%** does not measure transcript accuracy.

Report capture-to-first-partial and speech-end-to-final latency separately from inference duration, plus queue depth/age and total live CPU time. Mark unavailable confidence as unavailable. Add reference transcripts and WER/CER on representative audio; calibrate a probability only if the backend provides meaningful scores. Measure cold and warm inference separately because native graph/session setup is lazy.

### 10. P2: Segmentation boxes every sample

Reference: [boxed sample buffer](../app/src/main/java/com/example/offlinetranscription/audio/VadSegmenter.kt#L17).

`ArrayList<Float>` boxes each captured float, roughly 97,280 boxed entries for a 6.08-second segment. Each partial then allocates another primitive-array snapshot. This adds avoidable allocation and GC pressure alongside the already-expensive inference path.

Use a bounded `FloatArray`/ring buffer and explicit valid lengths. Allocate independent snapshots only when the inference scheduler will consume them, with clear buffer ownership. Measure allocations and GC pauses after fixing repeated inference; no GC-related dropout was measured here.

### 11. P2: Android end-to-end tests do not compile

Reference: [stale UI test](../app/src/androidTest/java/com/example/offlinetranscription/ui/main/MainScreenTest.kt#L17).

The test still references a deleted `MainScreen` and greeting data. The three passing unit tests cover initial silence, one silence endpoint, and RTF arithmetic. They do not cover tail flushing, restart/reload races, queue pressure, quiet onsets, max-duration boundaries, or the actual JNI path.

Repair the UI test around the real screen with injectable recorder/recognizer dependencies. Add deterministic lifecycle and backlog tests plus representative audio replay through the production segmentation path. Use separate device performance and quality baselines; the current synthetic high-amplitude sine test cannot establish speech accuracy.

### 12. P2: Release packaging retains unnecessary code

Reference: [disabled release shrinking](../app/build.gradle.kts#L49).

The unsigned release APK is **48.66 MiB**, with **42.97 MiB of uncompressed DEX**, despite native libraries totaling only a few MiB. Release minification is disabled; the app also includes the full extended material icon library and unused Navigation 3 dependencies.

Enable R8/resource shrinking after adding explicit JNI keep rules for native method bindings and the `TranscriptionResult` class/constructor looked up by name and signature. Remove unused dependencies or replace the small icon set with selected assets. Verify recognition in the minified release build. Size and startup improvements remain unmeasured until that build exists.

## Lower-priority improvements and limits

- **UI work and battery:** [WaveformVisualizer.kt:44](../app/src/main/java/com/example/offlinetranscription/ui/components/WaveformVisualizer.kt#L44) runs an infinite phase animation even when recording is stopped; its draw loop reads phase for every bar. Make the idle waveform static. [TranscriptionViewModel.kt:48](../app/src/main/java/com/example/offlinetranscription/ui/TranscriptionViewModel.kt#L48) combines audio-meter updates with the whole screen state and reads model file size on every update. Cache model metadata, isolate meter state, throttle download progress updates, and collect visible UI state with lifecycle awareness. Profile recompositions before further Compose changes.
- **Long sessions:** [LiveTranscriptionEngine.kt:138](../app/src/main/java/com/example/offlinetranscription/engine/LiveTranscriptionEngine.kt#L138) copies an ever-growing transcript list on every append and retains it indefinitely. Add bounded/paged history with persistence when required. [TranscriptionScreen.kt:100](../app/src/main/java/com/example/offlinetranscription/ui/TranscriptionScreen.kt#L100) scrolls on every partial change, which can disrupt history reading; scroll when the user is already following the latest turn.
- **Lifecycle:** Recording lives in `viewModelScope`; [MainActivity](../app/src/main/java/com/example/offlinetranscription/MainActivity.kt#L25) provides no background/foreground recording policy. Define whether leaving the screen flushes/stops recording or requires supported foreground-service behavior, and test screen lock, interruptions, and microphone route changes.
- **Native-load errors:** [NemoSpeechJni.kt:10](../app/src/main/java/com/example/offlinetranscription/nemo/NemoSpeechJni.kt#L10) catches a library-load `Throwable` and continues; later JNI calls can throw `UnsatisfiedLinkError`, outside the engine's `Exception` handling. Represent unavailable native runtime explicitly and retain a usable failure state.
- **WAV parsing:** [ModelManager.kt:137](../app/src/main/java/com/example/offlinetranscription/model/ModelManager.kt#L137) assumes PCM16 mono/16-kHz without validating the format chunk, chunk padding, or malformed sizes. The bundled sample matches these assumptions, so this did not affect the measured JFK results. Validate/decode formats before exposing general file transcription.
- **Memory/accelerators:** The loaded 0.6B Q8 model already puts PSS near 1 GiB. Profile native graph-cache high-water usage across variable segment lengths and sustained sessions. Smaller compatible models, reduced precision, or Vulkan are experiments requiring device coverage and WER/memory/thermal comparisons. They are not verified gains from this audit.

## Recommended implementation sequence

1. Serialize native-session ownership; add stop/drain/flush/join and safe restart/reload behavior.
2. Separate audio consumption from inference and coalesce obsolete partial requests; add queue and end-to-end timing metrics.
3. Preserve pre-roll, evaluate better VAD, and reconcile forced segment boundaries.
4. Correct CPU feature configuration; retain four threads as this phone's baseline and compare sustained runs.
5. Make model replacement transactional, repair Android tests, and verify a JNI-safe minified release.

Acceptance should cover short speech stopped immediately, quiet and noisy speech, continuous speech beyond six seconds, slow inference, reload during activity, microphone errors, and a sustained session. Track WER/CER, missed onsets/tails, p50/p95 partial/final latency, maximum queue age, allocation rate, PSS, and thermal behavior. Choose product latency targets before claiming an optimization successful.

API behavior was cross-checked against the [Kotlin SharedFlow documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/) and [Android CPU feature documentation](https://developer.android.com/ndk/guides/cpu-features). SharedFlow's extra buffer does not retain audio before the first subscriber; subscribing the consumer before starting capture also removes the startup loss window. The [NVIDIA model artifact](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/blob/main/parakeet-tdt-0.6b-v3.q8_0.gguf) was checked for download availability. Raw summarized measurements are in [the audit evidence file](audit-evidence-2026-10-24.json).
