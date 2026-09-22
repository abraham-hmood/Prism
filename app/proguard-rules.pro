# Prism — R8 keep rules.
#
# WHY THIS FILE IS LONG. R8 removes anything it cannot see a reference to, and Prism reaches for
# classes by name in more places than a typical app: JNI callbacks from four native libraries,
# Room's generated code, Chaquopy's Python bridge, BouncyCastle's provider lookup, jgit's
# ServiceLoader, and plugin pages loaded reflectively out of *other* APKs. None of that is visible
# to static analysis, so anything not kept here disappears and fails at runtime rather than at
# build time.
#
# The rule of thumb used throughout: keep by *mechanism*, not by guessing at individual classes.

# ── JNI ─────────────────────────────────────────────────────────────────────
#
# Native code resolves these by name through JNIEnv. Renaming or removing one produces an
# UnsatisfiedLinkError at the call site, a long way from the cause.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Classes the native side constructs or calls back into.
-keep class com.prism.launcher.messaging.GgufInferenceService { *; }
-keep class com.prism.launcher.messaging.GgufInferenceService$* { *; }
-keep class com.prism.launcher.editor.NodeRuntime { *; }
-keep class com.prism.launcher.wallet.RandomXNative { *; }
-keep class com.prism.launcher.nora.** { native <methods>; }

# ── WebView bridges ─────────────────────────────────────────────────────────
#
# @JavascriptInterface methods are called from JavaScript by name. R8 keeps the annotation but not
# necessarily the members, and a renamed bridge method is a silently dead editor.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Room ────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ── Chaquopy / Python ───────────────────────────────────────────────────────
#
# The Python side calls into Java by fully-qualified name; nothing in Kotlin references those
# entry points, so R8 sees them as dead.
-keep class com.chaquo.python.** { *; }
-keep class com.prism.launcher.cakechat.** { *; }
-dontwarn com.chaquo.python.**

# ── Cryptography ────────────────────────────────────────────────────────────
#
# BouncyCastle registers algorithms through a provider table keyed by class name, and the wallet
# derives keys through it. A shrunk provider fails as "no such algorithm" at signing time.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.prism.launcher.wallet.** { *; }
-keep class com.prism.launcher.lock.LockStore { *; }

# ── Reflection-loaded pages and plugins ─────────────────────────────────────
#
# DynamicPageLoader inflates a View from another installed APK by class name. Neither end is
# visible to the other at build time.
-keep class com.prism.launcher.PluginPageInfo { *; }
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ── Serialisation ───────────────────────────────────────────────────────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { @kotlinx.serialization.Serializable *; }
-keep class com.prism.core.json.** { *; }

# ── Service loaders ─────────────────────────────────────────────────────────
#
# jgit and others discover implementations through META-INF/services, which is a text file R8 does
# not read as a reference.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-keepnames class * implements java.util.spi.**

# ── TensorFlow Lite / MediaPipe ─────────────────────────────────────────────
-keep class org.tensorflow.** { *; }
-keep class com.google.mediapipe.** { *; }
-dontwarn org.tensorflow.**
-dontwarn com.google.mediapipe.**

# ── WireGuard ───────────────────────────────────────────────────────────────
-keep class com.wireguard.android.** { *; }
-dontwarn com.wireguard.**

# ── Keep line numbers in stack traces ───────────────────────────────────────
#
# Prism's own diagnostics page shows stack traces to the user, and an obfuscated one is unreadable
# to everybody including the person who wrote the code. The mapping file is the fallback, but a
# launcher that reports its own crashes should report them legibly.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
