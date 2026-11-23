#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <filesystem>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "ctranslate2/models/model.h"
#include "ctranslate2/replica_pool.h"
#include "ctranslate2/translator.h"
#include "sentencepiece_processor.h"

#define LOG_TAG "QuickMTJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct QuickMTContext {
    std::unique_ptr<ctranslate2::Translator> translator;
    sentencepiece::SentencePieceProcessor src_sp;
    sentencepiece::SentencePieceProcessor tgt_sp;
    bool shared_sp = false;
};

std::mutex g_mutex;
std::unique_ptr<QuickMTContext> g_ctx;

std::string jstring_to_utf8(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

// Lightweight sentence splitter for multi-sentence game subtitles
std::vector<std::string> split_sentences(const std::string& input) {
    std::vector<std::string> out;
    std::string cur;
    for (size_t i = 0; i < input.size(); ++i) {
        char c = input[i];
        cur.push_back(c);
        if ((c == '.' || c == '!' || c == '?') &&
            (i + 1 == input.size() || input[i + 1] == ' ' || input[i + 1] == '\n')) {
            // Avoid splitting on tiny abbreviations like "Mr." or "Dr."
            size_t len = cur.size();
            bool is_abbrev = (len >= 3 && (cur.substr(len - 3) == "Mr." || cur.substr(len - 3) == "Dr.")) ||
                             (len >= 4 && cur.substr(len - 4) == "Mrs.");
            if (!is_abbrev) {
                auto first = cur.find_first_not_of(" \t\r\n");
                auto last = cur.find_last_not_of(" \t\r\n");
                if (first != std::string::npos) {
                    out.push_back(cur.substr(first, last - first + 1));
                }
                cur.clear();
            }
        }
    }
    auto first = cur.find_first_not_of(" \t\r\n");
    auto last = cur.find_last_not_of(" \t\r\n");
    if (first != std::string::npos) {
        out.push_back(cur.substr(first, last - first + 1));
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_quickmt_gamesubtitles_QuickMTNative_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_dir_j,
    jint num_threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string model_dir = jstring_to_utf8(env, model_dir_j);
    if (model_dir.empty()) {
        LOGE("Model directory path is empty.");
        return JNI_FALSE;
    }

    try {
        namespace fs = std::filesystem;
        auto ctx = std::make_unique<QuickMTContext>();

        const fs::path base(model_dir);
        const fs::path joint_spm = base / "joint.spm.model";
        const fs::path src_spm = base / "src.spm.model";
        const fs::path tgt_spm = base / "tgt.spm.model";

        if (fs::exists(joint_spm)) {
            auto st = ctx->src_sp.Load(joint_spm.string());
            if (!st.ok()) {
                LOGE("Failed to load joint.spm.model: %s", st.ToString().c_str());
                return JNI_FALSE;
            }
            ctx->shared_sp = true;
        } else {
            auto st1 = ctx->src_sp.Load(src_spm.string());
            auto st2 = ctx->tgt_sp.Load(tgt_spm.string());
            if (!st1.ok() || !st2.ok()) {
                LOGE("Failed to load src/tgt SentencePiece models from %s", model_dir.c_str());
                return JNI_FALSE;
            }
            ctx->shared_sp = false;
        }

        ctranslate2::models::ModelLoader loader(model_dir);
        loader.device = ctranslate2::Device::CPU;
        loader.compute_type = ctranslate2::ComputeType::INT8;
        loader.num_replicas_per_device = 1;

        ctranslate2::ReplicaPoolConfig pool_cfg;
        pool_cfg.num_threads_per_replica = static_cast<size_t>(std::max(1, static_cast<int>(num_threads)));

        ctx->translator = std::make_unique<ctranslate2::Translator>(loader, pool_cfg);
        g_ctx = std::move(ctx);
        LOGI("QuickMT initialized successfully from %s (threads=%d)", model_dir.c_str(), static_cast<int>(num_threads));
        return JNI_TRUE;
    } catch (const std::exception& ex) {
        LOGE("Exception initializing QuickMT: %s", ex.what());
        g_ctx.reset();
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_quickmt_gamesubtitles_QuickMTNative_nativeTranslate(
    JNIEnv* env,
    jobject /* thiz */,
    jstring text_j,
    jint beam_size) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || !g_ctx->translator) {
        return env->NewStringUTF("");
    }

    const std::string input = jstring_to_utf8(env, text_j);
    if (input.empty()) {
        return env->NewStringUTF("");
    }

    try {
        auto sentences = split_sentences(input);
        if (sentences.empty()) {
            return env->NewStringUTF("");
        }

        std::vector<std::vector<std::string>> batch;
        batch.reserve(sentences.size());
        for (const auto& sent : sentences) {
            std::vector<std::string> pieces;
            g_ctx->src_sp.Encode(sent, &pieces);
            pieces.push_back("</s>");
            batch.push_back(std::move(pieces));
        }

        ctranslate2::TranslationOptions options;
        options.beam_size = static_cast<size_t>(std::max(1, static_cast<int>(beam_size)));
        options.max_decoding_length = 160;
        options.disable_unk = true;
        options.no_repeat_ngram_size = 3;

        auto results = g_ctx->translator->translate_batch(batch, options);

        const auto& decoder = g_ctx->shared_sp ? g_ctx->src_sp : g_ctx->tgt_sp;
        std::ostringstream joined;
        for (size_t i = 0; i < results.size(); ++i) {
            if (results[i].hypotheses.empty()) continue;
            std::string decoded;
            decoder.Decode(results[i].hypotheses[0], &decoded);
            if (!decoded.empty()) {
                if (joined.tellp() > 0) joined << " ";
                joined << decoded;
            }
        }
        return env->NewStringUTF(joined.str().c_str());
    } catch (const std::exception& ex) {
        LOGE("Translation exception: %s", ex.what());
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_com_quickmt_gamesubtitles_QuickMTNative_nativeRelease(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_ctx.reset();
    LOGI("QuickMT native resources released.");
}

}  // extern "C"
