# Public release review — 2026-10-25

The project is prepared locally as an Android developer demo. No GitHub repository was created, no branch was pushed, and the website was not deployed. Existing Kotlin, JNI, UI, tests, and audit edits were preserved. Public publication still needs to include all intended untracked source and documentation files.

## Findings and changes

| Priority | Finding | Resolution |
| --- | --- | --- |
| P1 | SentencePiece was a gitlink without root submodule configuration. A fresh clone could not initialize it. | Added `.gitmodules` with the upstream URL and retained commit `17d7580d6407802f85855d2cc9190634e2c95624`. |
| P1 | Four optional vendored TTS data files were upstream LFS pointers. Fresh checkout attempted an unavailable LFS fetch from the Android repository. | Removed their LFS filters and retained the existing full data as ordinary Git files. Verified all four files against their original SHA-256 LFS OIDs. No model weights were added. |
| P1 | Transcript history in `filesDir` was eligible for cloud backup and device transfer, contrary to a strict on-device retention claim. | Excluded the entire private file domain in legacy and current backup/transfer XML, including AtomicFile sidecars and legacy models. Existing local history remains intact. Previously created backups cannot be revoked by this change. |
| P2 | Original app code had no root license; dependency/model/audio attribution was incomplete. | Added the requested MIT license, dependency provenance, third-party/model/audio attribution, eight APK notice files, and an in-app model-card/license link. Upstream notices remain intact. |
| P2 | NDK r27 linked the project's shared libraries with 4-KiB LOAD alignment, producing an Android compatibility warning. | Added max-page-size and common-page-size linker options to all project-built native targets. Both APKs now pass 16-KiB LOAD and ZIP alignment checks. The prebuilt RELRO/runtime limitation below remains open. |
| P2 | Public claims could confuse batch throughput with live caption delay, or incremental previews with cache streaming. | README and article distinguish all metrics and explain repeated bounded-segment inference, model download requirements, retention, and practical limits. |
| P3 | No root CI or complete fresh-clone instructions. Model size used binary units labeled MB. | Added CI, a reusable APK checker, toolchain/setup/troubleshooting instructions, and corrected model-size labels to MiB. |

Review covered the manifest/exported entry points, capture and segmentation, queue/backpressure and shutdown behavior, native session lifetime and JNI text conversion, model download/import/replacement, history/logging, dependencies, and source history. The only production activity exported by the app is its launcher. The test host is debug-only and not exported. No audio/transcript upload client or analytics SDK was found. Imports remain trusted local inputs, not a sandboxed native-parser boundary.

## Verification

- A new Git checkout initialized the pinned SentencePiece dependency and completed JVM tests, lint, debug build, and minified release build without copied `.cxx` or app build caches; all 102 Gradle tasks executed in that first clean run. The isolated checkout was then rebuilt with the final linker and UI changes. Gradle dependency/toolchain caches were shared; this was a clean source/native build, not a network-isolation experiment.
- **19 JVM tests passed**, with no failures. **5 connected tests passed on Pixel 8a / Android 17 (API 37)**, none skipped. The added benchmark test records five native batch calls. Existing device tests exercise real-time fixture replay, transactional native model activation, and the model-free UI.
- The microphone permission remained **denied**. No microphone recording was started. The exact pinned model was downloaded to temporary verification storage, and its byte count and SHA-256 were verified before device installation.
- Debug/release builds and Android lint passed; lint reports **14 existing warnings and no errors**. The unsigned release APK is **7,501,482 bytes (7.15 MiB)**. No signing configuration or keys were added.
- Both APKs contain **8 ARM64 shared libraries**, all with at least 16-KiB LOAD alignment, and all eight required notice files. The reusable checker and SDK `zipalign -c -P 16 4` passed. No GGUF weights or debug test activity are packaged into release app functionality.
- `aapt2` inspection of both APKs confirmed that legacy backup, cloud backup, and device transfer exclude `domain="file" path="."`; the release resources remain correct after shrinking/renaming.
- Gitleaks **8.30.1** scanned both existing tracked commits. Its four findings were reviewed: an upstream base64 image fixture, a documentation placeholder API key, and two xxHash vector expressions. No confirmed credential exposure was found. A scan of the isolated publishable source was also reviewed; generated build logs are not publication inputs. Signing material and environment files are ignored. This is not a guarantee that every possible secret format is detectable.
- Website lint and static build passed. Fixed the existing CommonJS Tailwind lint error with a single-line ESLint exception and removed two unused catch bindings. The only remaining tool notice is stale Browserslist data; dependency versions were not changed. Article metadata, canonical URL, social image, BlogPosting data, sitemap entry, images, internal links, and contents anchors were checked. Desktop and 375-px mobile layouts were visually inspected.
- The GitHub Actions workflow is prepared locally; it has not run on hosted GitHub infrastructure. It performs JVM checks/builds and APK checks without downloading the model. Device tests remain separate.

## Reproduced evidence

The current portable `armv8-a`, four-thread debug build processed the 11-second fixture in **4,878 / 4,614 / 6,250 / 6,346 / 6,016 ms**. Median: **6,016 ms**; inference RTF range: **0.42–0.58**. Model initialization including two preview-shape warmups took **1,965 ms** in one observation.

A separate live-pipeline replay reported **1,506 ms** for the latest segment's first preview, **4,355 ms** from its last voiced frame to final text, and **1,986 ms** queue age. Word error rate was **0** for the normalized fixture reference. These are individual pipeline observations, not percentile guarantees. A separate button-driven UI smoke run took **4,189 ms**, shown in the article screenshot and excluded from the five-run table.

Raw summarized data and limitations: [public release evidence](public-release-evidence-2026-10-25.json). Earlier specialized Galaxy S22 Ultra observations remain in the original audit/remediation reports and are explicitly separated from these Pixel results.

## Remaining limits before product distribution

- **16-KiB runtime compatibility is not certified.** The connected phone uses 4-KiB pages. Although all packaged libraries have 16-KiB LOAD alignment, `libc++_shared.so` and `libandroidx.graphics.path.so` have non-aligned RELRO ends. The checker reports them without treating static alignment as runtime proof. Validate on a 16-KiB device and rebuild/update prebuilt dependencies as needed before claiming full support. See [Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes).
- The fixture and device checks do not establish noisy-microphone accuracy, language coverage, sustained memory/battery/thermal behavior, or p95 latency. Live preview responsiveness varies substantially. No calibrated confidence, diarization, GPU/NPU acceleration, or model-level cache streaming is implemented.
- Transcript history is plaintext in private storage, limited to 500 turns. Clipboard copies and older backups are outside the new retention exclusions. The clear action is not forensic secure erasure.
- Published at `https://github.com/meet30997/parakeet-android-on-device-speech-recognition` (originally created as `offline_transcription`; GitHub redirects the old URL).
