# Native dependency provenance

The Android app builds NeMo-Speech.cpp as a CMake subproject. GPU, TTS, HTTP/gRPC, NMT, diarization, and CLI targets are disabled. Original Kotlin/JNI glue is covered by the root MIT license; vendored source retains upstream terms.

| Dependency | Source / reproducible identity | Integration |
| --- | --- | --- |
| NeMo-Speech.cpp 0.2.0 | [NVIDIA/NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp/tree/2bdfb03b38d5e3b0fa55aeb91a079a9f57385373), comparison base `2bdfb03b38d5e3b0fa55aeb91a079a9f57385373` | Vendored source snapshot introduced by project commit `0d2f9f8daa15fbcb91dd2d9dd0e37b4dacb35561`; vendor Git tree `ebbf50dcb9c21400c1de996733e74580d0b025e9`. |
| llama.cpp / ggml | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp/tree/bd4f514db14d87fded667787a7a963bfbaa98e89), NeMo's upstream gitlink `bd4f514db14d87fded667787a7a963bfbaa98e89` | Vendored under NeMo. NeMo's own patch series is applied to a generated tree during configuration. |
| SentencePiece | [google/sentencepiece](https://github.com/google/sentencepiece/tree/17d7580d6407802f85855d2cc9190634e2c95624), `17d7580d6407802f85855d2cc9190634e2c95624` | Root Git submodule, built statically without tests or tcmalloc. |

## Existing Android adaptations

The original import did not record an upstream NeMo commit. To make provenance reviewable, its tracked files were compared by Git blob hash against the upstream comparison base above on 2026-10-25. Excluding the separately vendored llama.cpp dependency, six source files differ:

- `CMakeLists.txt`, `cmake/llama_cpp.cmake`, `src/asr/CMakeLists.txt`, and `src/runtime/ggml/CMakeLists.txt` replace root-oriented `CMAKE_SOURCE_DIR` paths with subproject-aware `PROJECT_SOURCE_DIR` paths.
- `include/nemo_speech/asr.h` adds `threads` to the sized C backend configuration, and `src/asr/c_api.cpp` forwards that value into the runtime. Build the C API library and JNI wrapper together; do not substitute an independently built upstream library with a different struct layout.

The vendored llama.cpp files match its upstream base except four additional UI-vendor files: BigInteger.js and its license, and decimal.js and its license. Those UI files are not built into the Android ASR targets. NeMo's `patches/series` is retained unchanged and applied by its build tooling; it is distinct from these Android source adaptations.

## Git LFS portability fix

The imported snapshot used upstream LFS pointers for four optional Mandarin TTS data files (`hmm_model.utf8`, `jieba.dict.utf8`, `pinyin_chars.tsv`, and `pinyin_phrases.tsv`). A fresh clone attempted to fetch them from this Android repository, which has no corresponding LFS remote. The existing local file contents were verified against the SHA-256 OIDs of all four upstream pointers and are now retained as ordinary Git files. The vendored `.gitattributes` removes their LFS filters. No LFS installation or LFS download is required; these files are not packaged in the Android ASR APK.

## Updating dependencies

Keep the snapshot and Android adaptations together. Record the new upstream revision and any changes to the C API layout, regenerate the patched ggml tree through a clean native build, and run JVM, JNI, release-minification, and device fixture checks. Recheck license notices when updating any source. Do not independently update SentencePiece or remove vendored files solely to reduce source size without validating a clean build.

No model weights are required to compile. The app downloads a pinned NVIDIA GGUF artifact at runtime; its revision, SHA-256, and byte count are defined in `ModelManager` and listed in the README. Imported models are user-provided and are not verified against that artifact hash.
