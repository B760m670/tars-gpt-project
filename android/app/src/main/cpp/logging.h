#pragma once
#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG "tars-llm"
#endif

// NOTE: deliberately NOT using __android_log_is_loggable() — that symbol is only
// available from API 30, and gating on it breaks the build at minSdk < 30
// (-Werror=unguarded-availability-new). We just log directly; Android's logd
// applies its own level filtering.

#if defined(NDEBUG)
#define LOGv(...) ((void)0)
#define LOGd(...) ((void)0)
#else
#define LOGv(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGd(...) __android_log_print(ANDROID_LOG_DEBUG,   LOG_TAG, __VA_ARGS__)
#endif

#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline int android_log_prio_from_ggml(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN:  return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_INFO:  return ANDROID_LOG_INFO;
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        default:                   return ANDROID_LOG_DEFAULT;
    }
}

static inline void tars_android_log_callback(enum ggml_log_level level,
                                             const char* text,
                                             void* /*user*/) {
    __android_log_write(android_log_prio_from_ggml(level), LOG_TAG, text);
}
