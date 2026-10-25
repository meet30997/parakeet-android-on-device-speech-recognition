# Audit remediation, 2026-10-24

Implemented the actionable findings from the [original audit](performance-audio-audit-2026-10-24.md). The existing model-card layout edits were preserved.

| Finding | Change |
| --- | --- |
| Native lifetime races | One native worker, serialized lifecycle operations, synchronized JNI session calls, replacement initialized before closing the previous recognizer. |
| Lost final speech / restart races | Stop capture, close and drain accepted frames, flush the utterance, join inference, then release resources. Generation checks and a content mutex prevent cleared results reappearing. |
| Repeated inference / capture backpressure | Dedicated audio-priority capture thread; separate segmentation and inference workers; four queued finals and one replaceable preview; at most two offline previews per utterance; visible overflow failure. |
| Quiet onsets / primitive buffers | 32-ms frames, adaptive energy threshold with hysteresis, 256-ms pre-roll, bounded primitive sample storage. |
| CPU feature mismatch | ggml ARM architecture selection applies equally to C and C++; portable ARM64 default and an explicit DOTPROD/FP16 build option. Both generated compile configurations were inspected. |
| Endpoint delay / hard cuts | 400-ms silence target, quiet boundaries after six seconds, eight-second hard limit with 384-ms context and overlap reconciliation. |
| Recorder failure handling | Validate initialization/startup, terminate and surface read errors, unblock and join reads on stop, release partially initialized resources. |
| Model replacement | Pinned revision, exact download size/hash, resumable and cancellation-aware transfer, GGUF metadata/tensor-bound validation, permanent-path native activation, rollback and interrupted-install recovery. Models moved out of backup storage. |
| Misleading metrics | Inference time separated from first-text, speech-end-to-final, and queue-age metrics; unavailable TDT confidence no longer shown as 100%; preview shapes warmed before recording. |
| Allocation / UI overhead | Primitive VAD buffers, bounded previews/history, isolated meter state, cached model size, throttled download progress, static idle waveform, lifecycle-aware collection and conditional auto-scroll. |
| Missing end-to-end tests | Replaced stale screen tests; added lifecycle/backlog, segmentation, WAV, model-validation and rollback tests, plus real native live replay and installation tests. |
| Release size | R8/resource shrinking with explicit JNI keep rules; removed unused Navigation 3/serialization dependencies. |

Additional changes: the most recent 500 turns are saved atomically, including shutdown tails; benchmarks append to history; leaving the app stops and finishes recording; permission state refreshes on resume; unavailable native libraries produce a usable error state; WAV parsing checks format, padding and truncation; JNI text decoding uses standard UTF-8.

## Verification

- **19 JVM tests passed.** Coverage includes reload during blocked inference, stop before first preview, stale-result suppression, quiet onsets, preview limits, final-queue backpressure, tensor truncation, failed activation, interrupted-install recovery, incomplete backups, and malformed WAV input.
- **4 device tests passed, none skipped**, on Galaxy S22 Ultra / Android 16. These include real-time replay of the bundled audio through VAD, scheduling and native JNI, and native activation after transactional installation.
- **Debug and minified release builds passed; lint passed**, with 14 existing SDK/dependency/platform warnings and no errors.
- **Release APK: 48.66 MiB to 7.14 MiB**, approximately 85% smaller. Uncompressed DEX decreased from 42.97 MiB to 1.87 MiB.
- **Preview input budget:** for 6.08 seconds of continuous input followed by stop, the two previews plus final submit 8.64 seconds, compared with the original 26.24 seconds. This is approximately **67% less submitted audio**, not a measured 67% wall-clock speedup. Superseded queued previews are additionally coalesced.
- **Optimized CPU build live replay:** zero word errors against the JFK reference; latest first-text latency 1,613 ms, final speech-end latency 484 ms, queue age 2 ms. These are individual fixture observations, not sustained or percentile guarantees. Both C and C++ ggml kernels used `-march=armv8.2-a+dotprod+fp16`.
- **Minified optimized release smoke:** model loaded, benchmark completed through JNI in 1,901 ms for the 11-second recording, expected transcript displayed, and confidence shown as unavailable. The screen was visually checked. The installed app was subsequently restored to the latest portable debug build with its model and saved benchmark history retained.

Device tests are configured to leave the app installed. During the initial test setup, the runner's default cleanup removed its installation and model; the app and exact original model were restored, and the keep-installed option was enabled for subsequent runs. The original model's SHA-256 matched the pinned NVIDIA artifact.

## Practical limits

The default build remains portable ARM64; the specialized CPU build requires hardware with the selected instructions. The underlying model is still a 0.6B offline model and does not gain cache streaming from these changes. Smaller models and GPU acceleration remain separate experiments.

The energy VAD and overlap reconciliation need broader evaluation on real microphones, background noise, accents and languages. JFK replay establishes a regression baseline, not general speech-quality improvement. Cold/warm behavior, sustained thermals, battery use and p95 latency still require a representative device/audio matrix. No microphone recording was initiated during validation.

Build and test instructions, CPU selection, model handling and history retention are documented in [README](../README.md).

Compiler macro probes and summarized measurements are in [the remediation evidence](remediation-evidence-2026-10-24.json).
