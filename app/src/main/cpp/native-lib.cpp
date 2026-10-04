#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <new>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include "whisper.h"

#ifndef NDEBUG
#define TV_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "TransVibeNative", __VA_ARGS__)
#else
#define TV_LOGE(...) ((void)0)
#endif

namespace {

constexpr jint MAX_THREADS = 8;
constexpr jsize MAX_AUDIO_SAMPLES = 70 * 16000;
constexpr int MAX_SEGMENTS = 10000;

class NativeFailure final : public std::runtime_error {
public:
    NativeFailure(std::string code, std::string message)
        : std::runtime_error(std::move(message)), code_(std::move(code)) {}
    const std::string& code() const noexcept { return code_; }
private:
    std::string code_;
};

[[noreturn]] void fail(const char* code, const char* message) {
    throw NativeFailure(code ? code : "UNKNOWN", message ? message : "Native failure");
}

struct WhisperDeleter {
    void operator()(whisper_context* p) const noexcept { if (p) whisper_free(p); }
};

struct NativeContext final {
    explicit NativeContext(whisper_context* p) : ctx(p) {
        if (!p) fail("INIT_FAILED", "Whisper context is null");
    }
    std::unique_ptr<whisper_context, WhisperDeleter> ctx;
    std::atomic_bool cancelled{false};
    std::mutex inferenceMutex;
};

class ScopedUtfChars final {
public:
    ScopedUtfChars(JNIEnv* env, jstring value, bool required) : env_(env), value_(value) {
        if (!env_) fail("INVALID_ARGUMENT", "JNI environment is null");
        if (!value_) {
            if (required) fail("INVALID_ARGUMENT", "Required string is null");
            return;
        }
        chars_ = env_->GetStringUTFChars(value_, nullptr);
        if (!chars_) fail("ALLOCATION_FAILED", "Cannot access Java string");
    }
    ~ScopedUtfChars() { if (chars_) env_->ReleaseStringUTFChars(value_, chars_); }
    ScopedUtfChars(const ScopedUtfChars&) = delete;
    ScopedUtfChars& operator=(const ScopedUtfChars&) = delete;
    const char* get() const noexcept { return chars_; }
private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

class ScopedFloatArray final {
public:
    ScopedFloatArray(JNIEnv* env, jfloatArray value) : env_(env), value_(value) {
        if (!env_) fail("INVALID_ARGUMENT", "JNI environment is null");
        if (!value_) fail("INVALID_AUDIO", "Audio buffer is null");
        data_ = env_->GetFloatArrayElements(value_, nullptr);
        if (!data_) fail("ALLOCATION_FAILED", "Cannot access audio buffer");
    }
    ~ScopedFloatArray() { if (data_) env_->ReleaseFloatArrayElements(value_, data_, JNI_ABORT); }
    ScopedFloatArray(const ScopedFloatArray&) = delete;
    ScopedFloatArray& operator=(const ScopedFloatArray&) = delete;
    jfloat* data() const noexcept { return data_; }
private:
    JNIEnv* env_;
    jfloatArray value_;
    jfloat* data_ = nullptr;
};

std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<NativeContext>> registry;
std::atomic<jlong> nextId{1};

void throwNative(JNIEnv* env, const char* code, const char* message) noexcept {
    if (!env || env->ExceptionCheck()) return;
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    if (!ex) return;
    std::string payload = "TVE|";
    payload += code ? code : "UNKNOWN";
    payload += '|';
    payload += message ? message : "Native failure";
    env->ThrowNew(ex, payload.c_str());
}

void validateModelPath(const char* rawPath) {
    if (!rawPath || *rawPath == '\0') fail("MODEL_INVALID", "Model path is empty");
    const std::filesystem::path path(rawPath);
    if (!path.is_absolute()) fail("MODEL_INVALID", "Model path must be absolute");
    std::error_code ec;
    if (!std::filesystem::exists(path, ec) || ec) fail("MODEL_NOT_FOUND", "Whisper model file does not exist");
    if (!std::filesystem::is_regular_file(path, ec) || ec) fail("MODEL_INVALID", "Whisper model path is not a regular file");
    const auto size = std::filesystem::file_size(path, ec);
    if (ec || size < 1024ULL * 1024ULL) fail("MODEL_INVALID", "Whisper model file is invalid or too small");
    std::ifstream input(path, std::ios::binary);
    if (!input.good()) fail("MODEL_NOT_READABLE", "Whisper model file is not readable");
}

std::shared_ptr<NativeContext> getContext(jlong id) {
    if (id <= 0) fail("INVALID_HANDLE", "Native handle is zero or invalid");
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto it = registry.find(id);
    if (it == registry.end() || !it->second || !it->second->ctx) fail("INVALID_HANDLE", "Whisper context does not exist or was released");
    return it->second;
}

bool abortCallback(void* user) noexcept {
    const auto* context = static_cast<NativeContext*>(user);
    return context && context->cancelled.load(std::memory_order_relaxed);
}

void validateThreads(jint threads) {
    if (threads < 1 || threads > MAX_THREADS) fail("INVALID_ARGUMENT", "Whisper thread count is outside the supported range");
}

jsize checkedAudioLength(JNIEnv* env, jfloatArray audio, jsize minimum) {
    if (!env || !audio) fail("INVALID_AUDIO", "Audio buffer is null");
    const jsize count = env->GetArrayLength(audio);
    if (env->ExceptionCheck()) fail("INVALID_AUDIO", "Cannot read audio buffer length");
    if (count < minimum) fail("INVALID_AUDIO", "Audio buffer is empty or too short");
    if (count > MAX_AUDIO_SAMPLES) fail("INVALID_AUDIO", "Audio buffer exceeds the bounded native chunk limit");
    return count;
}

void validateLanguage(const char* language) {
    if (!language) return;
    const std::string value(language);
    if (value.empty() || value.size() > 31) fail("INVALID_ARGUMENT", "Language code is invalid");
}

std::string escapeJson(const char* s) {
    if (!s) fail("INVALID_RESULT", "Whisper returned a null segment text");
    std::ostringstream out;
    for (const unsigned char c : std::string(s)) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    static constexpr char HEX[] = "0123456789abcdef";
                    out << "\\u00" << HEX[(c >> 4) & 15] << HEX[c & 15];
                } else out << c;
        }
    }
    return out.str();
}

whisper_full_params makeParams(NativeContext* context, int threads, const char* language, bool translate, const char* initialPrompt = nullptr) {
    if (!context || !context->ctx) fail("INVALID_HANDLE", "Whisper context is released");
    validateThreads(threads);
    validateLanguage(language);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.translate = translate;
    params.language = language;
    params.detect_language = language == nullptr || std::string(language) == "auto";
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = false;
    // Keep Whisper's internal rolling context disabled. Cross-chunk context is supplied only
    // through the bounded, application-owned finalized-text prompt.
    params.no_context = true;
    params.n_max_text_ctx = 64;
    params.initial_prompt = (initialPrompt && *initialPrompt) ? initialPrompt : nullptr;
    params.carry_initial_prompt = false;
    params.abort_callback = abortCallback;
    params.abort_callback_user_data = context;
    return params;
}

jstring newStringUtfChecked(JNIEnv* env, const std::string& value) {
    if (!env) fail("INVALID_ARGUMENT", "JNI environment is null");
    jstring result = env->NewStringUTF(value.c_str());
    if (!result) fail("ALLOCATION_FAILED", "Cannot allocate Java result string");
    return result;
}

void reportException(JNIEnv* env, const char* entry, const NativeFailure& e) noexcept {
    TV_LOGE("%s failed [%s]: %s", entry, e.code().c_str(), e.what());
    throwNative(env, e.code().c_str(), e.what());
}

void reportUnknown(JNIEnv* env, const char* entry) noexcept {
    TV_LOGE("%s failed with unknown native exception", entry);
    throwNative(env, "UNKNOWN", "Unknown native failure");
}

} // namespace

static jlong nativeCreateContext(JNIEnv* env, jobject, jstring path_) {
    try {
        ScopedUtfChars path(env, path_, true);
        validateModelPath(path.get());
        auto params = whisper_context_default_params();
        params.use_gpu = false;
        whisper_context* raw = whisper_init_from_file_with_params(path.get(), params);
        if (!raw) fail("INIT_FAILED", "Failed to initialize Whisper model");
        auto context = std::make_shared<NativeContext>(raw);
        const jlong id = nextId.fetch_add(1, std::memory_order_relaxed);
        if (id <= 0) fail("ALLOCATION_FAILED", "Native context identifier overflow");
        std::lock_guard<std::mutex> lock(registryMutex);
        if (!registry.emplace(id, std::move(context)).second) fail("ALLOCATION_FAILED", "Native context identifier collision");
        return id;
    } catch (const NativeFailure& e) { reportException(env, "nativeCreateContext", e); }
      catch (const std::bad_alloc&) { throwNative(env, "ALLOCATION_FAILED", "Native allocation failed while loading Whisper"); }
      catch (const std::exception& e) { TV_LOGE("nativeCreateContext exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native initialization failed"); }
      catch (...) { reportUnknown(env, "nativeCreateContext"); }
    return 0;
}

static jstring nativeDetectLanguage(JNIEnv* env, jobject, jlong id, jfloatArray audio_, jint threads) {
    try {
        auto context = getContext(id);
        validateThreads(threads);
        const jsize count = checkedAudioLength(env, audio_, 1600);
        std::lock_guard<std::mutex> inference(context->inferenceMutex);
        if (context->cancelled.load(std::memory_order_relaxed)) fail("CANCELLED", "Processing was cancelled");
        ScopedFloatArray audio(env, audio_);
        auto params = makeParams(context.get(), threads, "auto", false);
        const int rc = whisper_full(context->ctx.get(), params, audio.data(), count);
        if (rc != 0) fail(context->cancelled.load() ? "CANCELLED" : "INFERENCE_FAILED", "Language detection failed");
        const int langId = whisper_full_lang_id(context->ctx.get());
        if (langId < 0) fail("INVALID_RESULT", "Whisper returned an invalid language identifier");
        const char* code = whisper_lang_str(langId);
        if (!code || *code == '\0') fail("INVALID_RESULT", "Whisper returned an empty language code");
        return newStringUtfChecked(env, code);
    } catch (const NativeFailure& e) { reportException(env, "nativeDetectLanguage", e); }
      catch (const std::bad_alloc&) { throwNative(env, "ALLOCATION_FAILED", "Native allocation failed during language detection"); }
      catch (const std::exception& e) { TV_LOGE("nativeDetectLanguage exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native language detection failed"); }
      catch (...) { reportUnknown(env, "nativeDetectLanguage"); }
    return nullptr;
}

static jstring transcribeJson(JNIEnv* env, jlong id, jfloatArray audio_, jstring lang_, jboolean translate, jint threads, jstring prompt_) {
    auto context = getContext(id);
    validateThreads(threads);
    const jsize count = checkedAudioLength(env, audio_, 1);
    ScopedUtfChars language(env, lang_, false);
    ScopedUtfChars prompt(env, prompt_, false);
    validateLanguage(language.get());
    if (prompt.get() && std::strlen(prompt.get()) > 512) fail("INVALID_ARGUMENT", "Initial prompt exceeds bounded limit");
    std::lock_guard<std::mutex> inference(context->inferenceMutex);
    if (context->cancelled.load(std::memory_order_relaxed)) fail("CANCELLED", "Processing was cancelled");
    ScopedFloatArray audio(env, audio_);
    auto params = makeParams(context.get(), threads, language.get(), translate == JNI_TRUE, prompt.get());
    const int rc = whisper_full(context->ctx.get(), params, audio.data(), count);
    if (rc != 0) fail(context->cancelled.load() ? "CANCELLED" : "INFERENCE_FAILED", "Whisper inference failed");

    const int segmentCount = whisper_full_n_segments(context->ctx.get());
    if (segmentCount < 0 || segmentCount > MAX_SEGMENTS) fail("INVALID_RESULT", "Whisper returned an invalid segment count");
    std::ostringstream out;
    out << '[';
    for (int i = 0; i < segmentCount; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(context->ctx.get(), i);
        const int64_t t1 = whisper_full_get_segment_t1(context->ctx.get(), i);
        if (t0 < 0 || t1 < t0) fail("INVALID_RESULT", "Whisper returned invalid segment timestamps");
        const char* text = whisper_full_get_segment_text(context->ctx.get(), i);
        if (!text) fail("INVALID_RESULT", "Whisper returned null segment text");
        const int tokenCount = whisper_full_n_tokens(context->ctx.get(), i);
        double tokenProbabilitySum = 0.0;
        int tokenProbabilityCount = 0;
        for (int token = 0; token < tokenCount; ++token) {
            const float probability = whisper_full_get_token_p(context->ctx.get(), i, token);
            if (std::isfinite(probability) && probability >= 0.0f && probability <= 1.0f) {
                tokenProbabilitySum += probability;
                tokenProbabilityCount++;
            }
        }
        const float averageTokenProbability = tokenProbabilityCount > 0
            ? static_cast<float>(tokenProbabilitySum / tokenProbabilityCount) : -1.0f;
        const float noSpeechProbability = whisper_full_get_segment_no_speech_prob(context->ctx.get(), i);
        if (i) out << ',';
        out << "{\"startMs\":" << t0 * 10
            << ",\"endMs\":" << t1 * 10
            << ",\"text\":\"" << escapeJson(text) << "\""
            << ",\"averageTokenProbability\":" << averageTokenProbability
            << ",\"noSpeechProbability\":" << noSpeechProbability << '}';
    }
    out << ']';
    return newStringUtfChecked(env, out.str());
}

static jstring nativeTranscribeJson(JNIEnv* env, jobject, jlong id, jfloatArray audio_, jstring lang_, jboolean translate, jint threads) {
    try { return transcribeJson(env, id, audio_, lang_, translate, threads, nullptr); }
    catch (const NativeFailure& e) { reportException(env, "nativeTranscribeJson", e); }
    catch (const std::bad_alloc&) { throwNative(env, "ALLOCATION_FAILED", "Native allocation failed during Whisper inference"); }
    catch (const std::exception& e) { TV_LOGE("nativeTranscribeJson exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native Whisper inference failed"); }
    catch (...) { reportUnknown(env, "nativeTranscribeJson"); }
    return nullptr;
}

static jstring nativeTranscribeJsonWithPrompt(JNIEnv* env, jobject, jlong id, jfloatArray audio_, jstring lang_, jboolean translate, jint threads, jstring prompt_) {
    try { return transcribeJson(env, id, audio_, lang_, translate, threads, prompt_); }
    catch (const NativeFailure& e) { reportException(env, "nativeTranscribeJsonWithPrompt", e); }
    catch (const std::bad_alloc&) { throwNative(env, "ALLOCATION_FAILED", "Native allocation failed during Whisper inference"); }
    catch (const std::exception& e) { TV_LOGE("nativeTranscribeJsonWithPrompt exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native Whisper inference failed"); }
    catch (...) { reportUnknown(env, "nativeTranscribeJsonWithPrompt"); }
    return nullptr;
}

static void nativeCancel(JNIEnv* env, jobject, jlong id) {
    try {
        auto context = getContext(id);
        context->cancelled.store(true, std::memory_order_relaxed);
    } catch (const NativeFailure& e) { reportException(env, "nativeCancel", e); }
      catch (const std::exception& e) { TV_LOGE("nativeCancel exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native cancellation failed"); }
      catch (...) { reportUnknown(env, "nativeCancel"); }
}

static void nativeReleaseContext(JNIEnv* env, jobject, jlong id) {
    try {
        if (id <= 0) fail("INVALID_HANDLE", "Native handle is zero or invalid");
        std::shared_ptr<NativeContext> removed;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            const auto it = registry.find(id);
            if (it == registry.end() || !it->second) fail("INVALID_HANDLE", "Whisper context does not exist or was already released");
            removed = std::move(it->second);
            registry.erase(it);
        }
        removed->cancelled.store(true, std::memory_order_relaxed);
    } catch (const NativeFailure& e) { reportException(env, "nativeReleaseContext", e); }
      catch (const std::exception& e) { TV_LOGE("nativeReleaseContext exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native release failed"); }
      catch (...) { reportUnknown(env, "nativeReleaseContext"); }
}


extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (!vm) return JNI_ERR;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return JNI_ERR;
    jclass clazz = env->FindClass("com/transvibe/app/nativebridge/whisper/WhisperNative");
    if (!clazz || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_ERR;
    }
    static const JNINativeMethod methods[] = {
        {const_cast<char*>("nativeCreateContext"), const_cast<char*>("(Ljava/lang/String;)J"), reinterpret_cast<void*>(nativeCreateContext)},
        {const_cast<char*>("nativeDetectLanguage"), const_cast<char*>("(J[FI)Ljava/lang/String;"), reinterpret_cast<void*>(nativeDetectLanguage)},
        {const_cast<char*>("nativeTranscribeJson"), const_cast<char*>("(J[FLjava/lang/String;ZI)Ljava/lang/String;"), reinterpret_cast<void*>(nativeTranscribeJson)},
        {const_cast<char*>("nativeTranscribeJsonWithPrompt"), const_cast<char*>("(J[FLjava/lang/String;ZILjava/lang/String;)Ljava/lang/String;"), reinterpret_cast<void*>(nativeTranscribeJsonWithPrompt)},
        {const_cast<char*>("nativeCancel"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(nativeCancel)},
        {const_cast<char*>("nativeReleaseContext"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(nativeReleaseContext)},
    };
    if (env->RegisterNatives(clazz, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0]))) != JNI_OK || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return JNI_ERR;
    }
    env->DeleteLocalRef(clazz);
    return JNI_VERSION_1_6;
}
