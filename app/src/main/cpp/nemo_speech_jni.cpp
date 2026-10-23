#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <chrono>

#include "nemo_speech/asr.h"

#define TAG "NeMoSpeechJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static void throwRuntimeException(JNIEnv* env, const char* msg) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, msg);
    }
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_offlinetranscription_nemo_NemoSpeechJni_nativeGetVersion(
        JNIEnv* env,
        jobject /* this */) {
    const char* version = nemo_speech_asr_version();
    return env->NewStringUTF(version ? version : "unknown");
}

JNIEXPORT jlong JNICALL
Java_com_example_offlinetranscription_nemo_NemoSpeechJni_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jstring jModelPath,
        jint numThreads) {
    if (!jModelPath) {
        throwRuntimeException(env, "Model path cannot be null");
        return 0;
    }

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Initializing NeMo-Speech ASR recognizer with model: %s, threads: %d", modelPath, numThreads);

    nemo_speech_asr_backend_config backend_cfg = {};
    backend_cfg.size = sizeof(backend_cfg);
    backend_cfg.gpu = -1;  // CPU
    backend_cfg.threads = (numThreads > 0) ? numThreads : 0;

    nemo_speech_asr_model_config model_cfg = {};
    model_cfg.size = sizeof(model_cfg);
    model_cfg.path = modelPath;
    model_cfg.name = "";

    nemo_speech_asr_recognizer_config cfg = {};
    cfg.size = sizeof(cfg);
    cfg.backend = &backend_cfg;
    cfg.model = &model_cfg;

    nemo_speech_asr_recognizer* recognizer = nullptr;
    nemo_speech_asr_status st = nemo_speech_asr_create(&cfg, &recognizer);

    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (st != NEMO_SPEECH_ASR_OK || !recognizer) {
        const char* err = nemo_speech_asr_last_error();
        std::string errMsg = "Failed to create recognizer (code " + std::to_string(st) + "): ";
        errMsg += (err ? err : "unknown error");
        LOGE("%s", errMsg.c_str());
        throwRuntimeException(env, errMsg.c_str());
        return 0;
    }

    LOGI("Successfully initialized NeMo-Speech ASR recognizer: %p", recognizer);
    return reinterpret_cast<jlong>(recognizer);
}

JNIEXPORT jobject JNICALL
Java_com_example_offlinetranscription_nemo_NemoSpeechJni_nativeRecognizeChunk(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jfloatArray jAudioSamples,
        jint sampleRate,
        jboolean isFinal) {
    if (handle == 0) {
        throwRuntimeException(env, "Recognizer handle is invalid");
        return nullptr;
    }
    if (!jAudioSamples) {
        throwRuntimeException(env, "Audio samples array cannot be null");
        return nullptr;
    }

    auto* recognizer = reinterpret_cast<nemo_speech_asr_recognizer*>(handle);
    jsize nSamples = env->GetArrayLength(jAudioSamples);
    if (nSamples == 0) {
        jclass resultClass = env->FindClass("com/example/offlinetranscription/nemo/TranscriptionResult");
        jmethodID ctor = env->GetMethodID(resultClass, "<init>", "(Ljava/lang/String;FFJZ)V");
        jstring emptyStr = env->NewStringUTF("");
        return env->NewObject(resultClass, ctor, emptyStr, 0.0f, 0.0f, (jlong)0, isFinal);
    }

    jfloat* samples = env->GetFloatArrayElements(jAudioSamples, nullptr);
    if (!samples) {
        throwRuntimeException(env, "Failed to get audio float elements");
        return nullptr;
    }

    nemo_speech_asr_recognition_options opts = nemo_speech_asr_recognition_options_default();
    opts.enable_automatic_punctuation = true;

    auto startTime = std::chrono::steady_clock::now();

    nemo_speech_asr_result* result = nullptr;
    nemo_speech_asr_status st = nemo_speech_asr_recognize_f32(
            recognizer,
            &opts,
            samples,
            static_cast<size_t>(nSamples),
            sampleRate,
            &result);

    auto endTime = std::chrono::steady_clock::now();
    jlong latencyMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();

    env->ReleaseFloatArrayElements(jAudioSamples, samples, JNI_ABORT);

    if (st != NEMO_SPEECH_ASR_OK || !result) {
        const char* err = nemo_speech_asr_last_error();
        std::string errMsg = "nemo_speech_asr_recognize_f32 failed: ";
        errMsg += (err ? err : "unknown error");
        LOGE("%s", errMsg.c_str());
        throwRuntimeException(env, errMsg.c_str());
        return nullptr;
    }

    const char* text = nemo_speech_asr_result_transcript(result, 0);
    float conf = nemo_speech_asr_result_confidence(result, 0);
    float audioProcessedSec = nemo_speech_asr_result_audio_processed(result);

    jclass resultClass = env->FindClass("com/example/offlinetranscription/nemo/TranscriptionResult");
    jmethodID ctor = env->GetMethodID(resultClass, "<init>", "(Ljava/lang/String;FFJZ)V");

    jstring jText = env->NewStringUTF(text ? text : "");
    jobject resultObj = env->NewObject(resultClass, ctor, jText, conf, audioProcessedSec, latencyMs, isFinal);

    nemo_speech_asr_result_destroy(result);
    return resultObj;
}

JNIEXPORT void JNICALL
Java_com_example_offlinetranscription_nemo_NemoSpeechJni_nativeDestroy(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong handle) {
    if (handle != 0) {
        auto* recognizer = reinterpret_cast<nemo_speech_asr_recognizer*>(handle);
        LOGI("Destroying NeMo-Speech ASR recognizer: %p", recognizer);
        nemo_speech_asr_destroy(recognizer);
    }
}

} // extern "C"
