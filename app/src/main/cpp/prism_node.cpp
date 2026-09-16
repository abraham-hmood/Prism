// Starts Node.js inside Prism's own process.
//
// WHY THIS IS HERE. Prism's editor runs VS Code extensions. Most of the marketplace declares a
// `main` entry point and calls `require('fs')` or `child_process`, which a Web Worker cannot
// provide at any price, so the editor carries a real Node.js -- built as a shared library
// (`libnode.so`, see tools/node/build-libnode.sh) and started here.
//
// WHY IT IS THIS SMALL. Everything interesting happens in JavaScript. node::Start is Node's own
// entry point: hand it an argv and it does what the `node` command does, including running the
// event loop until the script is finished. The only things C++ has to do are set the environment
// before the runtime reads it, and get stdout somewhere a developer can see it.
//
// THREADING. node::Start does not return until the script exits, so Kotlin calls it on a thread of
// its own. It is not re-entrant: one runtime per process, which NodeRuntime enforces.

#include <jni.h>

#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <string>
#include <vector>

namespace node {
// Declared rather than included: libnode.so exports this, and the alternative is dragging Node's
// whole header tree into the build for one symbol.
extern int Start(int argc, char* argv[]);
}  // namespace node

static const char* kTag = "PrismNode";

namespace {

/**
 * Pumps a pipe into logcat.
 *
 * An Android app has no console. Without this, everything an extension logs -- and every Node
 * warning and stack trace -- is written to a file descriptor pointing at /dev/null, which makes a
 * misbehaving extension impossible to diagnose. Two threads, one for each stream, because a single
 * reader would serialise them.
 */
void* pump(void* arg) {
    int fd = static_cast<int>(reinterpret_cast<intptr_t>(arg));
    char buffer[1024];
    ssize_t count;
    std::string line;
    while ((count = read(fd, buffer, sizeof(buffer) - 1)) > 0) {
        buffer[count] = '\0';
        line += buffer;
        size_t newline;
        while ((newline = line.find('\n')) != std::string::npos) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "%s", line.substr(0, newline).c_str());
            line.erase(0, newline + 1);
        }
        // A very long line with no newline in it must not grow without bound.
        if (line.size() > 8192) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "%s", line.c_str());
            line.clear();
        }
    }
    return nullptr;
}

void redirectStreams() {
    static bool done = false;
    if (done) return;
    done = true;

    int pipes[2];
    if (pipe(pipes) != 0) return;
    dup2(pipes[1], STDOUT_FILENO);
    dup2(pipes[1], STDERR_FILENO);
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    pthread_t thread;
    pthread_create(&thread, nullptr, pump, reinterpret_cast<void*>(static_cast<intptr_t>(pipes[0])));
    pthread_detach(thread);
}

std::string toUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace

extern "C" {

/**
 * Sets a process environment variable.
 *
 * Node reads TMPDIR, HOME, NODE_OPTIONS and the rest exactly once, during bootstrap. Assigning
 * process.env afterwards is too late, so Kotlin sets them through here before nativeStart.
 */
JNIEXPORT void JNICALL Java_com_prism_launcher_editor_NodeRuntime_nativeSetEnv(
    JNIEnv* env, jobject, jstring name, jstring value) {
    const std::string key = toUtf8(env, name);
    const std::string val = toUtf8(env, value);
    if (!key.empty()) setenv(key.c_str(), val.c_str(), 1);
}

/**
 * Runs Node with the given argv, blocking until the script finishes.
 *
 * Returns Node's exit code. A non-zero one usually means the bootstrap script threw before it could
 * report anything over its socket, which is why stdout is redirected first.
 */
JNIEXPORT jint JNICALL Java_com_prism_launcher_editor_NodeRuntime_nativeStart(
    JNIEnv* env, jobject, jobjectArray args) {
    redirectStreams();

    const jsize count = env->GetArrayLength(args);
    std::vector<std::string> owned;
    owned.reserve(count);
    for (jsize i = 0; i < count; i++) {
        jstring item = static_cast<jstring>(env->GetObjectArrayElement(args, i));
        owned.push_back(toUtf8(env, item));
        env->DeleteLocalRef(item);
    }

    // node::Start takes a mutable argv and keeps pointers into it, so the backing strings have to
    // outlive the call -- hence `owned` above rather than pointers into freed JNI buffers.
    std::vector<char*> argv;
    argv.reserve(owned.size() + 1);
    for (std::string& value : owned) argv.push_back(const_cast<char*>(value.c_str()));
    argv.push_back(nullptr);

    __android_log_print(ANDROID_LOG_INFO, kTag, "starting node with %d arguments", (int)owned.size());
    return node::Start(static_cast<int>(owned.size()), argv.data());
}

}  // extern "C"
