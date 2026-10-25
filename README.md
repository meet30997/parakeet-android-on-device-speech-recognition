# Parakeet Android: on-device speech recognition

An Android app that does offline speech-to-text with NVIDIA's Parakeet TDT 0.6B v3 model, running entirely on the phone's CPU. It's written in Kotlin and Jetpack Compose and uses [NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp) (built on ggml) for inference through JNI.

There's no cloud API. You download the model once, and after that transcription works in airplane mode.

I wrote up how it works and how it performs here: [On-Device Speech-to-Text on Android: Parakeet and NeMo Benchmarks](https://meetprajapati.com/blogs/on-device-transcription-android-parakeet-nemo-speech/).

<p align="center">
  <img src="docs/images/model-ready.webp" width="260" alt="Parakeet on-device speech recognition app on a Pixel 8a with the model loaded">
  &nbsp;&nbsp;
  <img src="docs/images/benchmark.webp" width="260" alt="Android app transcribing an audio clip offline with Parakeet">
</p>

## How fast is it?

On a Pixel 8a, an 11-second clip takes about 3.5 seconds to transcribe on four CPU threads. The CPU build flag makes a big difference:

| Build (`-PcpuArch=`) | Median time | Real-time factor |
| --- | --- | --- |
| `armv8-a` (default) | 6,490 ms | 0.59 |
| `armv8.2-a+dotprod+fp16` | 4,134 ms | 0.38 |
| `armv8.2-a+dotprod+fp16+i8mm` | 3,500 ms | 0.32 |

Each row is the median of 10 runs. Four threads was faster than two, six or eight. The raw numbers are in [docs/benchmarks](docs/benchmarks/).

Live captions are slower than these numbers suggest. Parakeet doesn't stream here: the app re-runs the model on chunks of audio, so previews and long segments can queue up. In my tests, final text showed up 1 to 16 seconds after speech ended. The blog post goes into why.

## What the app does

- Transcribes from the microphone, with preview text while you speak and a final transcript when you pause
- Downloads the model (resumable, checked against a pinned SHA-256) or imports a GGUF file you already have
- Includes an 11-second JFK clip you can transcribe without granting microphone permission
- Keeps your last 500 transcripts on the device, with copy and clear
- Lets you change the CPU thread count and shows inference time and latency

## Requirements

| | |
| --- | --- |
| Phone | ARM64, Android 9 (API 28) or newer |
| Android SDK | API 36, Build Tools 36.0.0 |
| JDK | 17 |
| Gradle / AGP | 9.1.0 / 9.0.1 |
| NDK / CMake | 27.1.12297006 / 3.31.6 |
| Storage | About 714 MB for the model, plus room for the download |

The APK only includes `arm64-v8a`, so x86 emulators won't work.

## Build and run

```sh
git clone --recurse-submodules https://github.com/meet30997/parakeet-android-on-device-speech-recognition.git
cd parakeet-android-on-device-speech-recognition
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you cloned without `--recurse-submodules`, run `git submodule update --init --recursive`. SentencePiece is a submodule; NeMo-Speech.cpp and its ggml source are vendored (see [docs/dependencies.md](docs/dependencies.md)).

To install the toolchain from the command line:

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;27.1.12297006" "cmake;3.31.6"
```

Point `ANDROID_HOME` at your SDK, or add `sdk.dir=...` to `local.properties`.

When the app opens, tap **Download Model**, wait for it to load, then tap **Run JFK Benchmark Audio** or the mic button.

## The model

The app downloads a pinned revision of NVIDIA's [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/541d1f99c6b0c3cd0b11a95167540bb8edefd82b) GGUF:

- File: `parakeet-tdt-0.6b-v3.q8_0.gguf`
- Size: 713,975,456 bytes
- SHA-256: `e3880d0aaaaf2c308ea2c35016b2b895c423eb3fda924c1b463d1c19b7f4d32e`

Imported files are checked for a valid GGUF structure but not against NVIDIA's hash, so only import files you trust. I've only tested this exact model.

## Faster builds for newer phones

The default build targets plain `armv8-a`, so it runs on any 64-bit ARM phone. If you know your phones support dot-product and int8 matrix instructions (check `asimddp` and `i8mm` in `/proc/cpuinfo`), build with:

```sh
./gradlew :app:assembleRelease -PcpuArch=armv8.2-a+dotprod+fp16+i8mm
```

That was 46% faster on the Pixel 8a. It will crash on phones without those instructions, so don't ship it to everyone. Release APKs are unsigned; add your own signing config.

## How it works

```text
AudioRecord (16 kHz mono, 32 ms frames)
  → capture queue
  → energy-based speech segmenter
  → final queue + one preview slot
  → single inference thread
  → JNI → NeMo-Speech.cpp → ggml (CPU)
  → Jetpack Compose UI
```

The mic is read on its own thread, so slow inference never blocks capture. The segmenter ends a segment after 400 ms of silence and forces a split at 8 seconds. Each segment gets up to two previews and one final transcription. All native calls go through one thread, so a model can't be freed while it's still running.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

The device tests run the bundled clip through the real JNI path and check the word error rate. They need a connected ARM64 phone with the model already downloaded in the app, and they're skipped otherwise. The benchmark test takes optional `threads` and `runs` arguments:

```sh
adb shell am instrument -w \
  -e class 'com.example.offlinetranscription.NativePipelineTest#bundledAudioBenchmarkRecordsRepeatedNativeInference' \
  -e threads 4 -e runs 5 \
  com.example.offlinetranscription.test/androidx.test.runner.AndroidJUnitRunner
```

## Troubleshooting

| Problem | Try |
| --- | --- |
| CMake can't find SentencePiece | Run `git submodule update --init --recursive`. GitHub's "Download ZIP" doesn't include submodules. |
| SDK, NDK or CMake not found | Install the versions above and check `ANDROID_HOME` or `local.properties`. |
| APK won't install | Use an ARM64 phone on Android 9 or newer. |
| Download stopped partway | Tap download again and it resumes. |
| App crashes when the model loads | If you built with `-PcpuArch`, your phone may not support those instructions. Try the default build. |
| Speech gets missed in noise | The segmenter is energy-based, so loud backgrounds confuse it. |

## Limitations

I tested on one phone with one short English clip, so treat the numbers as a starting point. The app doesn't do streaming inference, speaker diarization, or GPU/NPU acceleration, and I haven't measured memory or battery use yet.

## License

The app code is [MIT](LICENSE). NeMo-Speech.cpp is Apache 2.0, and the Parakeet model weights are CC BY 4.0 from NVIDIA. The weights aren't included in this repo. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the rest.
