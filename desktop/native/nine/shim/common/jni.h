/*
 * The JDK's jni.h, made to accept NINE's Android-style calls.
 *
 * Android declares JavaVM::AttachCurrentThread(JNIEnv**, void*); the JDK declares it with void**.
 * NINE is written against Android's, so its calls are adapted here rather than edited (its sources
 * are re-synced from its own repository on every build).
 */
#pragma once
#include_next <jni.h>

#ifdef __cplusplus
#define AttachCurrentThread(env, args) AttachCurrentThread(reinterpret_cast<void**>(env), args)
#define AttachCurrentThreadAsDaemon(env, args) AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(env), args)
#endif
