# Third-party notices

The root MIT license applies to Meet Prajapati's original app code and documentation. It does not relicense dependencies, model weights, or sample recordings. Upstream copyright headers and license files are retained.

## Native runtime

- **NeMo-Speech.cpp**, Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES: Apache License 2.0. See [`LICENSE`](app/src/main/cpp/vendor/nemo_speech/LICENSE), [`NOTICE`](app/src/main/cpp/vendor/nemo_speech/NOTICE), and its [third-party notices](app/src/main/cpp/vendor/nemo_speech/THIRD_PARTY_NOTICES.md). The existing Android modifications are described in [dependency provenance](docs/dependencies.md).
- **llama.cpp / ggml**, Copyright (c) 2023–2026 The ggml authors: MIT. See its [license](app/src/main/cpp/vendor/nemo_speech/llama.cpp/LICENSE) and the upstream dependency notices retained in the vendored source. The Android build uses NeMo's patched ggml CPU backend.
- **SentencePiece**, Google and the SentencePiece authors: Apache License 2.0. See its [license](app/src/main/cpp/vendor/sentencepiece/LICENSE) at pinned revision `17d7580d6407802f85855d2cc9190634e2c95624`.

NeMo's upstream notice describes optional components beyond this CPU ASR build. It remains intact; it is not a claim that every optional dependency is packaged into this APK.

## JVM and Android libraries

AndroidX (including Compose and lifecycle), Kotlin, kotlinx.coroutines, and OkHttp use Apache License 2.0. Their upstream notices remain applicable. Gradle resolves the exact dependency versions from `gradle/libs.versions.toml`; Android platform libraries are provided by the device. Test-only dependencies are not shipped as app functionality.

## NVIDIA model

**Parakeet TDT 0.6B v3**, by NVIDIA, from the [official model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/blob/541d1f99c6b0c3cd0b11a95167540bb8edefd82b/README.md), is licensed under [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/). The app downloads NVIDIA's Q8_0 GGUF artifact without modifying its weights. Artifact revision: `541d1f99c6b0c3cd0b11a95167540bb8edefd82b`. Model weights are not included in this repository or the APK.

The app's model-card link and packaged attribution identify NVIDIA and the model license. User-imported files may have different origins and terms; this attribution does not establish their provenance.

## Bundled JFK audio fixture

`app/src/main/assets/sample_jfk.wav` is a byte-identical copy of [`samples/jfk.wav` from whisper.cpp](https://github.com/ggml-org/whisper.cpp/blob/231638a0717b7822784297e5980965ffcec05810/samples/jfk.wav), used as an 11-second ASR regression fixture. It contains an excerpt from President John F. Kennedy's 1961 inaugural address. Source project: whisper.cpp, Copyright (c) 2023–2026 The ggml authors, MIT. The source project's license is retained in [`whisper-cpp-LICENSE.txt`](app/src/main/assets/licenses/whisper-cpp-LICENSE.txt).

Audio SHA-256: `59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e`.

The sample is included for demonstration and reproducible testing. Its transcript is not evidence of accuracy across other speakers, languages, or recording conditions.

## APK notices

Both APK variants include the app license, Apache 2.0 text, NeMo's NOTICE and dependency notices, the llama.cpp license, the SentencePiece license, the whisper.cpp license, and this attribution under `assets/licenses/`.
