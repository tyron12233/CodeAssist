#import "JvmHost.h"
#include <unistd.h>
#include <fcntl.h>

#if CODEASSIST_EMBEDDED_JVM

#import <CodeAssistUi/CodeAssistUi.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>

// The JVM is OpenJDK built for iOS with the Zero interpreter (no generated code) and linked into this
// executable. Three facts about that build shape everything below.
//
// 1. It is created once per process and never again.
// 2. Zero interprets on the C stack of the thread running the Java code, so that thread needs a real stack:
//    every call is carried to one dedicated thread rather than made on whichever thread asked.
// 3. A statically linked JVM never opens a library file. `System.load("…/layoutlib_jni.dylib")` is answered
//    by looking in this executable for `JNI_OnLoad_<name>`, so the entry points layoutlib and the image
//    classes ask for have to be defined here.

#pragma mark - What System.load finds in this executable

// The name the JVM derives for layoutlib_jni.dylib: the file name with three characters cut for a "lib" prefix
// it does not have. Opens the real library, which iOS will only load from the app's signed Frameworks, and
// passes the call on. A library the VM believes is built in must ask for JNI 1.8; layoutlib asks for 1.6.
JNIEXPORT jint JNICALL JNI_OnLoad_outlib_jni(JavaVM *vm, void *reserved) {
    NSString *path = [[[NSBundle mainBundle] privateFrameworksPath] stringByAppendingPathComponent:@"layoutlib_jni.dylib"];
    void *library = dlopen(path.UTF8String, RTLD_NOW | RTLD_GLOBAL);
    if (!library) { NSLog(@"layoutlib_jni did not open: %s", dlerror()); return JNI_ERR; }
    jint (*onLoad)(JavaVM *, void *) = dlsym(library, "JNI_OnLoad");
    if (!onLoad) return JNI_ERR;
    jint version = onLoad(vm, reserved);
    return version >= JNI_VERSION_1_1 && version < JNI_VERSION_1_8 ? JNI_VERSION_1_8 : version;
}

// This JDK has no AWT native library for iOS. layoutlib uses java.awt.image.BufferedImage only as a holder
// for an int[] its own native code fills, and those classes ask libawt for nothing but to cache field ids for
// native loops that never run here. So "libawt" is an entry point and empty initIDs.
JNIEXPORT jint JNICALL JNI_OnLoad_awt(JavaVM *vm, void *reserved) { return JNI_VERSION_1_8; }
#define AWT_INIT_IDS(cls) JNIEXPORT void JNICALL Java_##cls##_initIDs(JNIEnv *env, jclass c) {}
AWT_INIT_IDS(java_awt_image_BufferedImage)
AWT_INIT_IDS(java_awt_image_ColorModel)
AWT_INIT_IDS(java_awt_image_IndexColorModel)
AWT_INIT_IDS(java_awt_image_Raster)
AWT_INIT_IDS(java_awt_image_SampleModel)
AWT_INIT_IDS(java_awt_image_ComponentSampleModel)
AWT_INIT_IDS(java_awt_image_SinglePixelPackedSampleModel)
AWT_INIT_IDS(java_awt_image_Kernel)
AWT_INIT_IDS(sun_awt_image_IntegerComponentRaster)
AWT_INIT_IDS(sun_awt_image_ByteComponentRaster)
AWT_INIT_IDS(sun_awt_image_ShortComponentRaster)
AWT_INIT_IDS(sun_awt_image_BytePackedRaster)
AWT_INIT_IDS(java_awt_Color)
AWT_INIT_IDS(java_awt_Rectangle)
AWT_INIT_IDS(java_awt_Dimension)
AWT_INIT_IDS(java_awt_Insets)
AWT_INIT_IDS(java_awt_Font)
AWT_INIT_IDS(java_awt_Toolkit)
AWT_INIT_IDS(sun_java2d_Disposer)
AWT_INIT_IDS(sun_java2d_SurfaceData)
AWT_INIT_IDS(sun_awt_image_BufImgSurfaceData)

#pragma mark - The one thread Java runs on

@interface JvmChannel : NSObject <CAUEmbeddedJvm>
@end

// ---- aapt2 ---------------------------------------------------------------------------------------------
//
// An iOS app cannot launch a program, so aapt2 is here as a library: Google's own Mac binary, turned from an
// executable into a dylib and marked for iOS. It still has its `main`, found through the LC_MAIN record the
// conversion keeps, and a run of the tool is a call of it.

#include <mach-o/dyld.h>
#include <mach-o/loader.h>

static int (*aapt2Main)(int, char **);

static BOOL loadAapt2(void) {
    if (aapt2Main) return YES;
    NSString *path = [[NSBundle mainBundle].privateFrameworksPath stringByAppendingPathComponent:@"libaapt2.dylib"];
    if (![[NSFileManager defaultManager] fileExistsAtPath:path]) return NO;
    if (!dlopen(path.fileSystemRepresentation, RTLD_NOW | RTLD_LOCAL)) { fprintf(stderr, "aapt2 did not load: %s\n", dlerror()); return NO; }
    for (uint32_t i = 0; i < _dyld_image_count(); i++) {
        const char *name = _dyld_get_image_name(i);
        if (!name || !strstr(name, "libaapt2.dylib")) continue;
        const struct mach_header_64 *header = (const struct mach_header_64 *)_dyld_get_image_header(i);
        const struct load_command *command = (const struct load_command *)(header + 1);
        for (uint32_t c = 0; c < header->ncmds; c++) {
            if (command->cmd == LC_MAIN) {
                aapt2Main = (int (*)(int, char **))((const char *)header + ((const struct entry_point_command *)command)->entryoff);
                return YES;
            }
            command = (const struct load_command *)((const char *)command + command->cmdsize);
        }
    }
    fprintf(stderr, "aapt2 loaded, but its entry point was not found\n");
    return NO;
}

// ---- the build's Java tools, compiled ahead of time --------------------------------------------------------
//
// The JVM here only interprets, and D8 and the Kotlin compiler are the heaviest Java a build runs. The app
// carries both in one GraalVM native image: a library with a runtime and a heap of its own (an "isolate"),
// which exports only its entry points, so the JDK libraries inside it and the JVM's own never meet. Each tool
// takes two file paths, what to do and where to write what came of it, and answers a code.

typedef int (*AotEntry)(void *, const char *, const char *);
static void *aotIsolate;
static int (*aotAttach)(void *, void **);
static int (*aotDetach)(void *);
static AotEntry aotD8, aotKotlinc;
// The engine itself, in the same image (see EmbeddedEngineHost): a project opened, one request answered.
static void (*aotEngineProperty)(void *, const char *, const char *);
static void (*aotEngineAapt2)(void *, int (*)(int, char **, const char *));
static int (*aotEngineOpen)(void *, const char *, char *, int);
static int (*aotEngineStart)(void *, const char *, char *, int);
static char *(*aotEngineCall)(void *, const char *, int, int *);
static void (*aotEngineSession)(void *, char *(*)(int, const char *));

/// Starts the image's runtime, once. Without it everything still runs, in the VM as before.
static BOOL loadAotTools(void) {
    if (aotIsolate) return YES;
    NSString *path = [[NSBundle mainBundle].privateFrameworksPath stringByAppendingPathComponent:@"libaottools.dylib"];
    if (![[NSFileManager defaultManager] fileExistsAtPath:path]) return NO;
    void *library = dlopen(path.fileSystemRepresentation, RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "the build tools did not load: %s\n", dlerror()); return NO; }
    int (*create)(void *, void **, void **) = dlsym(library, "graal_create_isolate");
    aotAttach = dlsym(library, "graal_attach_thread");
    aotDetach = dlsym(library, "graal_detach_thread");
    if (!create || !aotAttach || !aotDetach) { fprintf(stderr, "the build tools loaded, but their runtime's entry points were not found\n"); return NO; }
    // The image reserves the address range its heap may grow into, by default more than iOS grants an app.
    struct { int version; unsigned long reservedAddressSpaceSize; const char *auxPath; unsigned long auxSize;
             int argc; char **argv; int pkey; char ignoreUnrecognized, reserved4, reserved5; } params = { 0 };
    params.version = 5;
    params.reservedAddressSpaceSize = 2UL << 30;
    void *isolate = NULL, *thread = NULL;
    int created = create(&params, &isolate, &thread);
    if (created != 0) { fprintf(stderr, "the build tools' runtime did not start (%d)\n", created); return NO; }
    // Every call attaches the thread it is on and detaches it after; this one is no different.
    aotDetach(thread);
    aotD8 = dlsym(library, "aot_d8");
    aotKotlinc = dlsym(library, "aot_kotlinc");
    aotEngineProperty = dlsym(library, "aot_engine_property");
    aotEngineAapt2 = dlsym(library, "aot_engine_aapt2");
    aotEngineOpen = dlsym(library, "aot_engine_open");
    aotEngineStart = dlsym(library, "aot_engine_start");
    aotEngineCall = dlsym(library, "aot_engine_call");
    aotEngineSession = dlsym(library, "aot_engine_session");
    aotIsolate = isolate;
    return YES;
}

/// What the backends print (a stack trace, a fatal error) has no console on a phone. It goes to a file in
/// Documents, started afresh with each launch, where the Files app and a debugger can both read it.
static void redirectLog(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSString *documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
        NSString *log = [documents stringByAppendingPathComponent:@"embedded-backend.log"];
        freopen(log.fileSystemRepresentation, "w", stderr);
        dup2(fileno(stderr), fileno(stdout));
        setvbuf(stderr, NULL, _IONBF, 0);
        setvbuf(stdout, NULL, _IOLBF, 0);
    });
}

static BOOL loadAapt2(void);
static int aapt2RunTo(int argc, char **argv, const char *output);

/// The engine compiled ahead of time, ready to open a project: started, told where it may write and where
/// the Android toolchain is, and given the app's aapt2. NO when this build has none, or it would not start.
static BOOL startAotEngine(void) {
    static BOOL started;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        redirectLog();
        if (!loadAotTools() || !aotEngineProperty || !aotEngineOpen || !aotEngineCall) return;
        void *thread = NULL;
        if (aotAttach(aotIsolate, &thread) != 0) return;
        NSString *app = [NSBundle mainBundle].bundlePath;
        NSString *documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
        aotEngineProperty(thread, "java.io.tmpdir", [NSTemporaryDirectory() stringByStandardizingPath].fileSystemRepresentation);
        aotEngineProperty(thread, "user.home", documents.fileSystemRepresentation);
        aotEngineProperty(thread, "user.dir", documents.fileSystemRepresentation);
        // The class library the engine reads the Java platform's own types from: the one the VM runs on.
        aotEngineProperty(thread, "java.home", [app stringByAppendingPathComponent:@"lib"].fileSystemRepresentation);
        NSString *toolchain = [app stringByAppendingPathComponent:@"android-toolchain"];
        if ([[NSFileManager defaultManager] fileExistsAtPath:[toolchain stringByAppendingPathComponent:@"android.jar"]]) {
            aotEngineProperty(thread, "codeassist.android.toolchain", toolchain.fileSystemRepresentation);
        }
        if (aotEngineAapt2 && loadAapt2()) aotEngineAapt2(thread, aapt2RunTo);
        aotDetach(thread);
        started = YES;
        fprintf(stderr, "the engine compiled ahead of time is in this process\n");
    });
    return started;
}

/// The app's store session, which the engine asks for CodeAssist Free AI's access token (see EngineSession in
/// the framework). Set once, before the engine's backend starts.
static id<CAUEngineSession> engineSession;

/// The engine's question about the session, answered by the framework on whichever engine thread asked: 0 is
/// there a session ("1", or NULL for none), 1 a current access token, 2 one to retry with after `arg` was
/// refused. The answer is malloc'd, for the engine to free; NULL for none. A token is never logged.
static char *askEngineSession(int question, const char *arg) {
    @autoreleasepool {
        id<CAUEngineSession> session = engineSession;
        if (!session) return NULL;
        NSString *answer = nil;
        switch (question) {
            case 0: answer = [session signedIn] ? @"1" : nil; break;
            case 1: answer = [session accessToken]; break;
            case 2: answer = [session refreshAccessTokenStale:arg ? [NSString stringWithUTF8String:arg] : nil]; break;
            default: break;
        }
        return answer.length ? strdup(answer.UTF8String) : NULL;
    }
}

/// One request answered by the engine compiled ahead of time, on the calling thread.
static NSData *callAotEngine(NSData *request) {
    void *thread = NULL;
    if (aotAttach(aotIsolate, &thread) != 0) return [NSData data];
    int length = 0;
    char *reply = aotEngineCall(thread, request.bytes, (int)request.length, &length);
    aotDetach(thread);
    if (!reply) return [NSData data];
    return [NSData dataWithBytesNoCopy:reply length:(NSUInteger)length freeWhenDone:YES];
}

/// A tool on the calling thread, which may be any of the build's.
static jint runAotTool(AotEntry tool, JNIEnv *env, jstring first, jstring second) {
    void *thread = NULL;
    if (!tool || aotAttach(aotIsolate, &thread) != 0) return -1;
    const char *a = (*env)->GetStringUTFChars(env, first, NULL);
    const char *b = (*env)->GetStringUTFChars(env, second, NULL);
    int code = tool(thread, a, b);
    (*env)->ReleaseStringUTFChars(env, first, a);
    (*env)->ReleaseStringUTFChars(env, second, b);
    aotDetach(thread);
    return code;
}

/// `NativeD8.run(String argFile, String log)`.
static jint JNICALL d8Run(JNIEnv *env, jclass cls, jstring argFile, jstring log) { return runAotTool(aotD8, env, argFile, log); }

/// `NativeKotlinc.run(String requestFile, String resultFile)`.
static jint JNICALL kotlincRun(JNIEnv *env, jclass cls, jstring request, jstring result) { return runAotTool(aotKotlinc, env, request, result); }

/// aapt2's main with `argv` (program name first), what it prints sent to the file at `output`.
static int aapt2RunTo(int argc, char **argv, const char *output) {
    if (!aapt2Main) return -1;
    int file = open(output, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    // One process, one stdout: it is the file for as long as the tool runs, then what it was again.
    fflush(stdout); fflush(stderr);
    int savedOut = dup(STDOUT_FILENO), savedErr = dup(STDERR_FILENO);
    if (file >= 0) { dup2(file, STDOUT_FILENO); dup2(file, STDERR_FILENO); }
    int code = aapt2Main(argc, argv);
    fflush(stdout); fflush(stderr);
    dup2(savedOut, STDOUT_FILENO); dup2(savedErr, STDERR_FILENO);
    close(savedOut); close(savedErr);
    if (file >= 0) close(file);
    return code;
}

/// `NativeAapt2.run(String[] args, String output)`: aapt2's main, with what it prints sent to `output`.
static jint JNICALL aapt2Run(JNIEnv *env, jclass cls, jobjectArray args, jstring output) {
    if (!aapt2Main) return -1;
    jsize count = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t)count + 2, sizeof(char *));
    argv[0] = strdup("aapt2");
    for (jsize i = 0; i < count; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *utf = (*env)->GetStringUTFChars(env, arg, NULL);
        argv[i + 1] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, arg, utf);
        (*env)->DeleteLocalRef(env, arg);
    }
    const char *path = (*env)->GetStringUTFChars(env, output, NULL);
    int code = aapt2RunTo((int)count + 1, argv, path);
    (*env)->ReleaseStringUTFChars(env, output, path);
    for (jsize i = 0; i <= count; i++) free(argv[i]);
    free(argv);
    return code;
}

// ---- SHA-256 -------------------------------------------------------------------------------------------
//
// The build fingerprints its inputs, and this JVM only interprets: its own SHA-256 does under two megabytes
// a second. The system's does the platform jar in a blink.

#include <CommonCrypto/CommonDigest.h>
#include <sys/mman.h>
#include <sys/stat.h>

/// `NativeDigest.sha256(String path)`: the file's digest, or null when it cannot be read.
static jbyteArray JNICALL sha256File(JNIEnv *env, jclass cls, jstring path) {
    const char *utf = (*env)->GetStringUTFChars(env, path, NULL);
    int file = open(utf, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    if (file < 0) return NULL;
    CC_SHA256_CTX context;
    CC_SHA256_Init(&context);
    static const size_t size = 1 << 20;
    unsigned char *buffer = malloc(size);
    ssize_t n;
    while ((n = read(file, buffer, size)) > 0) CC_SHA256_Update(&context, buffer, (CC_LONG)n);
    free(buffer);
    close(file);
    if (n < 0) return NULL;
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256_Final(digest, &context);
    jbyteArray result = (*env)->NewByteArray(env, CC_SHA256_DIGEST_LENGTH);
    (*env)->SetByteArrayRegion(env, result, 0, CC_SHA256_DIGEST_LENGTH, (const jbyte *)digest);
    return result;
}

// Every MessageDigest in the JVM (see NativeDigests): the state of a digest in progress is a Java array, read
// into the system's context for each call and written back.

typedef union { CC_SHA256_CTX sha256; CC_SHA1_CTX sha1; CC_MD5_CTX md5; } DigestContext;

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
static void JNICALL digestInit(JNIEnv *env, jclass cls, jint algorithm, jbyteArray state) {
    DigestContext context;
    memset(&context, 0, sizeof context);
    if (algorithm == 0) CC_SHA256_Init(&context.sha256); else if (algorithm == 1) CC_SHA1_Init(&context.sha1); else CC_MD5_Init(&context.md5);
    (*env)->SetByteArrayRegion(env, state, 0, sizeof context, (const jbyte *)&context);
}

static void JNICALL digestUpdate(JNIEnv *env, jclass cls, jint algorithm, jbyteArray state, jbyteArray input, jint offset, jint length) {
    DigestContext context;
    (*env)->GetByteArrayRegion(env, state, 0, sizeof context, (jbyte *)&context);
    jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, input, NULL);
    if (!bytes) return;
    if (algorithm == 0) CC_SHA256_Update(&context.sha256, bytes + offset, (CC_LONG)length);
    else if (algorithm == 1) CC_SHA1_Update(&context.sha1, bytes + offset, (CC_LONG)length);
    else CC_MD5_Update(&context.md5, bytes + offset, (CC_LONG)length);
    (*env)->ReleasePrimitiveArrayCritical(env, input, bytes, JNI_ABORT);
    (*env)->SetByteArrayRegion(env, state, 0, sizeof context, (const jbyte *)&context);
}

static jbyteArray JNICALL digestFinish(JNIEnv *env, jclass cls, jint algorithm, jbyteArray state) {
    DigestContext context;
    (*env)->GetByteArrayRegion(env, state, 0, sizeof context, (jbyte *)&context);
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    jsize size;
    if (algorithm == 0) { CC_SHA256_Final(digest, &context.sha256); size = CC_SHA256_DIGEST_LENGTH; }
    else if (algorithm == 1) { CC_SHA1_Final(digest, &context.sha1); size = CC_SHA1_DIGEST_LENGTH; }
    else { CC_MD5_Final(digest, &context.md5); size = CC_MD5_DIGEST_LENGTH; }
    jbyteArray result = (*env)->NewByteArray(env, size);
    (*env)->SetByteArrayRegion(env, result, 0, size, (const jbyte *)digest);
    return result;
}
#pragma clang diagnostic pop

_Static_assert(sizeof(DigestContext) <= 256, "NativeDigests.STATE_BYTES is too small for a digest context");

@implementation JvmChannel {
    NSCondition *_pending;
    NSMutableArray<dispatch_block_t> *_jobs;
    // The editor's calls (completion, diagnostics) have a thread of their own: on the one above they would
    // wait behind a layout being rendered or a build being asked how it is doing.
    NSCondition *_editorPending;
    NSMutableArray<dispatch_block_t> *_editorJobs;
    // The project the engine compiled ahead of time has open, when it is the one answering; and the one the
    // VM's own engine has open, which it only opens for a layout.
    NSString *_engineRoot;
    NSString *_vmRoot;
    // The engine compiled ahead of time hosting a whole backend (see startBackend): its calls come from any
    // thread of the app's, and are carried to threads with a stack deep enough for the engine's parsers.
    BOOL _wholeBackend;
    NSCondition *_callsPending;
    NSMutableArray<dispatch_block_t> *_callJobs;
    int _callThreads, _callIdle;
    JavaVM *_vm;
    JNIEnv *_env;
    jclass _host;
    jmethodID _open;
    jmethodID _call;
    NSString *_startFailure;
}

- (instancetype)init {
    if ((self = [super init])) {
        _pending = [NSCondition new];
        _jobs = [NSMutableArray array];
        pthread_attr_t attributes;
        pthread_attr_init(&attributes);
        pthread_attr_setstacksize(&attributes, 64 * 1024 * 1024);
        pthread_t thread;
        pthread_create(&thread, &attributes, runJobs, (__bridge_retained void *)self);
        _editorPending = [NSCondition new];
        _editorJobs = [NSMutableArray array];
        pthread_create(&thread, &attributes, runEditorJobs, (__bridge_retained void *)self);
        pthread_attr_destroy(&attributes);
        _callsPending = [NSCondition new];
        _callJobs = [NSMutableArray array];
    }
    return self;
}

static void *runCallJobs(void *context) {
    JvmChannel *channel = (__bridge_transfer JvmChannel *)context;
    pthread_setname_np("codeassist-engine-call");
    for (;;) {
        dispatch_block_t job;
        [channel->_callsPending lock];
        channel->_callIdle++;
        while (channel->_callJobs.count == 0) [channel->_callsPending wait];
        channel->_callIdle--;
        job = channel->_callJobs.firstObject;
        [channel->_callJobs removeObjectAtIndex:0];
        [channel->_callsPending unlock];
        @autoreleasepool { job(); }
    }
    return NULL;
}

/// One request of the whole backend's protocol, answered by the engine on a thread of the call pool. The pool
/// grows while every thread is busy (one always is: the standing question about the flows), up to a bound.
- (NSData *)callWholeBackend:(NSData *)request {
    __block NSData *answer = [NSData data];
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [_callsPending lock];
    [_callJobs addObject:^{ answer = callAotEngine(request); dispatch_semaphore_signal(done); }];
    if (_callIdle < (int)_callJobs.count && _callThreads < 24) {
        _callThreads++;
        pthread_attr_t attributes;
        pthread_attr_init(&attributes);
        pthread_attr_setstacksize(&attributes, 16 * 1024 * 1024);
        pthread_t thread;
        pthread_create(&thread, &attributes, runCallJobs, (__bridge_retained void *)self);
        pthread_attr_destroy(&attributes);
    }
    [_callsPending signal];
    [_callsPending unlock];
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    return answer;
}

- (NSString *)startBackendProjectsRoot:(NSString *)projectsRoot session:(id<CAUEngineSession>)session {
    __block NSString *answer = @"This build has no engine compiled in.";
    [self onJavaThread:^{
        if (!startAotEngine() || !aotEngineStart) return;
        void *thread = NULL;
        char why[512] = { 0 };
        CFAbsoluteTime started = CFAbsoluteTimeGetCurrent();
        if (aotAttach(aotIsolate, &thread) != 0) { answer = @"The engine's runtime would not take a thread."; return; }
        // CodeAssist Free AI: the Supabase project as properties, the session as a function to ask. Both before
        // the backend starts, because its agent looks for the credits service once, as it is made.
        if (aotEngineSession && [session supabaseUrl].length && [session supabaseKey].length) {
            engineSession = session;
            aotEngineProperty(thread, "codeassist.supabase.url", [session supabaseUrl].UTF8String);
            aotEngineProperty(thread, "codeassist.supabase.key", [session supabaseKey].UTF8String);
            aotEngineProperty(thread, "codeassist.ai.proxy.url", [session aiProxyUrl].UTF8String);
            aotEngineSession(thread, askEngineSession);
        }
        int failed = aotEngineStart(thread, projectsRoot.fileSystemRepresentation, why, (int)sizeof(why));
        aotDetach(thread);
        fprintf(stderr, "engine backend over %s -> %s in %.0f ms\n", projectsRoot.UTF8String, failed ? why : "started", (CFAbsoluteTimeGetCurrent() - started) * 1000);
        answer = failed ? ([NSString stringWithUTF8String:why] ?: @"The engine's backend did not start.") : @"";
        self->_wholeBackend = !failed;
    }];
    return answer;
}

static void *runJobs(void *context) {
    JvmChannel *channel = (__bridge_transfer JvmChannel *)context;
    pthread_setname_np("codeassist-jvm");
    for (;;) {
        dispatch_block_t job;
        [channel->_pending lock];
        while (channel->_jobs.count == 0) [channel->_pending wait];
        job = channel->_jobs.firstObject;
        [channel->_jobs removeObjectAtIndex:0];
        [channel->_pending unlock];
        @autoreleasepool { job(); }
    }
    return NULL;
}

static void *runEditorJobs(void *context) {
    JvmChannel *channel = (__bridge_transfer JvmChannel *)context;
    pthread_setname_np("codeassist-jvm-editor");
    for (;;) {
        dispatch_block_t job;
        [channel->_editorPending lock];
        while (channel->_editorJobs.count == 0) [channel->_editorPending wait];
        job = channel->_editorJobs.firstObject;
        [channel->_editorJobs removeObjectAtIndex:0];
        [channel->_editorPending unlock];
        @autoreleasepool { job(); }
    }
    return NULL;
}

/// One request to the backend on the calling thread, which must be attached to the VM as `env`.
- (NSData *)call:(NSData *)request env:(JNIEnv *)env {
    NSData *answer = [NSData data];
    (*env)->PushLocalFrame(env, 8);
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)request.length);
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)request.length, request.bytes);
    jbyteArray reply = (jbyteArray)(*env)->CallStaticObjectMethod(env, _host, _call, bytes);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    } else if (reply) {
        jsize length = (*env)->GetArrayLength(env, reply);
        NSMutableData *data = [NSMutableData dataWithLength:(NSUInteger)length];
        (*env)->GetByteArrayRegion(env, reply, 0, length, data.mutableBytes);
        answer = data;
    }
    (*env)->PopLocalFrame(env, NULL);
    return answer;
}

/// An editor request (they open with "EDT1"), on the editor's thread. Empty while there is no VM: the
/// editor's calls never start one, and the app answers them itself until a project has.
- (NSData *)callEditorRequest:(NSData *)request {
    __block NSData *answer = [NSData data];
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [_editorPending lock];
    [_editorJobs addObject:^{
        JNIEnv *env = NULL;
        if (self->_vm && self->_call && (*self->_vm)->AttachCurrentThreadAsDaemon(self->_vm, (void **)&env, NULL) == JNI_OK) {
            answer = [self call:request env:env];
        }
        dispatch_semaphore_signal(done);
    }];
    [_editorPending signal];
    [_editorPending unlock];
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    return answer;
}

/// Run `work` on the Java thread and wait for it.
- (void)onJavaThread:(dispatch_block_t)work {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [_pending lock];
    [_jobs addObject:^{ work(); dispatch_semaphore_signal(done); }];
    [_pending signal];
    [_pending unlock];
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
}

/// Java thread only. Creates the VM the first time; afterwards answers how that went.
- (BOOL)startIfNeeded {
    if (_vm) return YES;
    if (_startFailure) return NO;
    NSBundle *bundle = [NSBundle mainBundle];
    NSString *app = bundle.bundlePath;
    // The VM finds its class library relative to the executable and reads no option before it has: on iOS
    // that place is <app>/lib, which is where the build puts it.
    NSString *javaHome = [app stringByAppendingPathComponent:@"lib"];
    NSString *jarDir = [app stringByAppendingPathComponent:@"jvm-jars"];
    NSMutableArray<NSString *> *classPath = [NSMutableArray array];
    for (NSString *name in [[[NSFileManager defaultManager] contentsOfDirectoryAtPath:jarDir error:nil] sortedArrayUsingSelector:@selector(compare:)]) {
        if ([name hasSuffix:@".jar"]) [classPath addObject:[jarDir stringByAppendingPathComponent:name]];
    }
    if (classPath.count == 0) { _startFailure = @"This build carries no JVM payload."; return NO; }
    NSString *documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *tmp = [NSTemporaryDirectory() stringByStandardizingPath];
    NSArray<NSString *> *options = @[
        [@"-Djava.home=" stringByAppendingString:javaHome],
        [@"-Djava.class.path=" stringByAppendingString:[classPath componentsJoinedByString:@":"]],
        [@"-Djava.io.tmpdir=" stringByAppendingString:tmp],
        [@"-Duser.home=" stringByAppendingString:documents],
        [@"-Duser.dir=" stringByAppendingString:documents],
        // layoutlib picks its library's file extension from the OS name and knows "Mac OS X", not "Darwin".
        @"-Dos.name=Mac OS X",
        @"-Djava.awt.headless=true",
        [NSString stringWithFormat:@"-Dlayoutlib.native=%@/", bundle.privateFrameworksPath],
        [NSString stringWithFormat:@"-XX:ErrorFile=%@/jvm_error.log", documents],
        @"--enable-native-access=ALL-UNNAMED",
        @"-XX:+UseSerialGC",
        @"-Xshare:off",
        // With the engine compiled ahead of time, this VM only renders layouts.
        _engineRoot ? @"-Xmx1g" : @"-Xmx2g",
        // An app's address space is a few gigabytes, and the default class space alone reserves one of them:
        // with the heap and the tools compiled ahead of time (each reserving its own) the VM could not start.
        @"-XX:CompressedClassSpaceSize=256m",
    ];
    // What the VM and the backend print (a stack trace, a fatal error) has no console on a phone. It goes to a
    // file in Documents, started afresh with each VM, where the Files app and a debugger can both read it.
    redirectLog();
    // The engine's parsers post to Swing's event thread as they start, and there is no AWT toolkit here to
    // run one: the build's java.desktop patch replaces that one class (see :ide-embedded).
    NSString *desktopPatch = [app stringByAppendingPathComponent:@"jvm-patch/java.desktop.jar"];
    if ([[NSFileManager defaultManager] fileExistsAtPath:desktopPatch]) {
        options = [options arrayByAddingObject:[@"--patch-module=java.desktop=" stringByAppendingString:desktopPatch]];
    }
    // The platform jar a project's own views are compiled against for the preview, when the build carries one.
    NSString *toolchain = [app stringByAppendingPathComponent:@"android-toolchain"];
    if ([[NSFileManager defaultManager] fileExistsAtPath:[toolchain stringByAppendingPathComponent:@"android.jar"]]) {
        options = [options arrayByAddingObject:[@"-Dcodeassist.android.toolchain=" stringByAppendingString:toolchain]];
    }
    if (loadAapt2()) options = [options arrayByAddingObject:@"-Dcodeassist.aapt2.native=true"];
    options = [options arrayByAddingObject:@"-Dcodeassist.sha256.native=true"];
    options = [options arrayByAddingObject:@"-Dcodeassist.digests.native=true"];
    // This JVM only interprets, and dexing a library in it takes tens of seconds: take them dexed already.
    options = [options arrayByAddingObject:@"-Dcodeassist.dexpack=true"];
    JavaVMOption *vmOptions = calloc(options.count, sizeof(JavaVMOption));
    for (NSUInteger i = 0; i < options.count; i++) vmOptions[i].optionString = strdup(options[i].UTF8String);
    JavaVMInitArgs arguments = { .version = JNI_VERSION_10, .nOptions = (jint)options.count, .options = vmOptions, .ignoreUnrecognized = JNI_TRUE };
    CFAbsoluteTime started = CFAbsoluteTimeGetCurrent();
    jint created = JNI_CreateJavaVM(&_vm, (void **)&_env, &arguments);
    if (created != JNI_OK) { _vm = NULL; _startFailure = [NSString stringWithFormat:@"The JVM did not start (%d).", created]; return NO; }
    fprintf(stderr, "JVM started in %.0f ms with %lu jars\n", (CFAbsoluteTimeGetCurrent() - started) * 1000, (unsigned long)classPath.count);

    jclass host = (*_env)->FindClass(_env, "dev/ide/embedded/EmbeddedLayoutHost");
    if (!host) { _startFailure = [self takeException] ?: @"The embedded backend is not on the class path."; return NO; }
    _host = (*_env)->NewGlobalRef(_env, host);
    _open = (*_env)->GetStaticMethodID(_env, _host, "open", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    _call = (*_env)->GetStaticMethodID(_env, _host, "call", "([B)[B");
    if (!_open || !_call) { _startFailure = [self takeException] ?: @"The embedded backend has no entry points."; return NO; }
    jclass digest = (*_env)->FindClass(_env, "dev/ide/embedded/NativeDigest");
    JNINativeMethod digestMethod = { "sha256", "(Ljava/lang/String;)[B", (void *)sha256File };
    if (!digest || (*_env)->RegisterNatives(_env, digest, &digestMethod, 1) != JNI_OK) {
        fprintf(stderr, "the native file hash could not be registered: %s\n", ([self takeException] ?: @"no reason given").UTF8String);
    }
    jclass digests = (*_env)->FindClass(_env, "dev/ide/embedded/NativeDigests");
    JNINativeMethod digestMethods[] = {
        { "init", "(I[B)V", (void *)digestInit },
        { "update", "(I[B[BII)V", (void *)digestUpdate },
        { "finish", "(I[B)[B", (void *)digestFinish },
    };
    if (!digests || (*_env)->RegisterNatives(_env, digests, digestMethods, 3) != JNI_OK) {
        fprintf(stderr, "the native digests could not be registered: %s\n", ([self takeException] ?: @"no reason given").UTF8String);
    }
    if (aapt2Main) {
        jclass tool = (*_env)->FindClass(_env, "dev/ide/embedded/NativeAapt2");
        JNINativeMethod method = { "run", "([Ljava/lang/String;Ljava/lang/String;)I", (void *)aapt2Run };
        if (!tool || (*_env)->RegisterNatives(_env, tool, &method, 1) != JNI_OK) {
            fprintf(stderr, "aapt2 is in the app but could not be registered: %s\n", ([self takeException] ?: @"no reason given").UTF8String);
        }
    }
    loadAotTools();
    struct { AotEntry tool; const char *name, *owner; void *function; const char *property; } tools[] = {
        { aotD8, "D8", "dev/ide/embedded/NativeD8", (void *)d8Run, "codeassist.d8.native" },
        { aotKotlinc, "kotlinc", "dev/ide/embedded/NativeKotlinc", (void *)kotlincRun, "codeassist.kotlinc.native" },
    };
    jclass system = (*_env)->FindClass(_env, "java/lang/System");
    jmethodID setProperty = (*_env)->GetStaticMethodID(_env, system, "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    for (int i = 0; i < 2; i++) {
        if (!tools[i].tool) continue;
        jclass owner = (*_env)->FindClass(_env, tools[i].owner);
        JNINativeMethod method = { "run", "(Ljava/lang/String;Ljava/lang/String;)I", tools[i].function };
        if (!owner || (*_env)->RegisterNatives(_env, owner, &method, 1) != JNI_OK) {
            fprintf(stderr, "%s is in the app but could not be registered: %s\n", tools[i].name, ([self takeException] ?: @"no reason given").UTF8String);
            continue;
        }
        (*_env)->CallStaticObjectMethod(_env, system, setProperty, (*_env)->NewStringUTF(_env, tools[i].property), (*_env)->NewStringUTF(_env, "true"));
    }
    // With D8 here, libraries are dexed on the phone faster than they download dexed.
    if (aotD8) (*_env)->CallStaticObjectMethod(_env, system, setProperty, (*_env)->NewStringUTF(_env, "codeassist.dexpack"), (*_env)->NewStringUTF(_env, "false"));
    return YES;
}

/// Java thread only. The pending exception as text, cleared; nil when there is none.
- (nullable NSString *)takeException {
    if (!(*_env)->ExceptionCheck(_env)) return nil;
    jthrowable thrown = (*_env)->ExceptionOccurred(_env);
    (*_env)->ExceptionDescribe(_env);
    (*_env)->ExceptionClear(_env);
    jclass object = (*_env)->FindClass(_env, "java/lang/Object");
    jmethodID toString = (*_env)->GetMethodID(_env, object, "toString", "()Ljava/lang/String;");
    jstring text = (jstring)(*_env)->CallObjectMethod(_env, thrown, toString);
    if ((*_env)->ExceptionCheck(_env) || !text) { (*_env)->ExceptionClear(_env); return @"A Java exception was thrown."; }
    const char *utf = (*_env)->GetStringUTFChars(_env, text, NULL);
    NSString *message = [NSString stringWithUTF8String:utf] ?: @"A Java exception was thrown.";
    (*_env)->ReleaseStringUTFChars(_env, text, utf);
    return message;
}

/// Java thread only. Opens `projectRoot` in the VM's own engine, starting the VM first; empty, or why not.
- (NSString *)openInVm:(NSString *)projectRoot {
    if (![self startIfNeeded]) return _startFailure;
    if ([_vmRoot isEqualToString:projectRoot]) return @"";
    NSString *answer = @"";
    JNIEnv *env = _env;
    (*env)->PushLocalFrame(env, 16);
    NSString *home = [[NSBundle mainBundle].bundlePath stringByAppendingPathComponent:@"layoutlib"];
    NSString *caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject;
    jstring result = (jstring)(*env)->CallStaticObjectMethod(env, _host, _open,
        (*env)->NewStringUTF(env, projectRoot.UTF8String),
        (*env)->NewStringUTF(env, home.UTF8String),
        (*env)->NewStringUTF(env, [caches stringByAppendingPathComponent:@"layout-preview"].UTF8String));
    NSString *thrown = [self takeException];
    fprintf(stderr, "open %s -> %s\n", projectRoot.UTF8String, thrown ? thrown.UTF8String : "returned");
    if (thrown) {
        answer = thrown;
    } else if (result) {
        const char *utf = (*env)->GetStringUTFChars(env, result, NULL);
        answer = [NSString stringWithUTF8String:utf] ?: @"";
        (*env)->ReleaseStringUTFChars(env, result, utf);
    }
    (*env)->PopLocalFrame(env, NULL);
    if (answer.length) fprintf(stderr, "open failed: %s\n", answer.UTF8String);
    else _vmRoot = projectRoot;
    return answer;
}

- (NSString *)openProjectProjectRoot:(NSString *)projectRoot {
    __block NSString *answer = @"";
    [self onJavaThread:^{
        // The engine compiled ahead of time opens the project when the build has one. The VM is then not
        // started here at all: it comes up when a layout is first asked for.
        if (startAotEngine()) {
            void *thread = NULL;
            char why[512] = { 0 };
            CFAbsoluteTime started = CFAbsoluteTimeGetCurrent();
            if (aotAttach(aotIsolate, &thread) == 0) {
                int failed = aotEngineOpen(thread, projectRoot.fileSystemRepresentation, why, (int)sizeof(why));
                aotDetach(thread);
                fprintf(stderr, "engine open %s -> %s in %.0f ms\n", projectRoot.UTF8String, failed ? why : "opened", (CFAbsoluteTimeGetCurrent() - started) * 1000);
                if (!failed) { self->_engineRoot = projectRoot; return; }
            }
        }
        self->_engineRoot = nil;
        answer = [self openInVm:projectRoot];
    }];
    return answer;
}

- (BOOL)compiledEngine {
    return _engineRoot != nil;
}

- (NSData *)callRequest:(NSData *)request {
    if (_wholeBackend && request.length >= 4 && memcmp(request.bytes, "IDE1", 4) == 0) return [self callWholeBackend:request];
    BOOL editor = request.length >= 4 && memcmp(request.bytes, "EDT1", 4) == 0;
    BOOL engine = editor || (request.length >= 4 && (memcmp(request.bytes, "BLD1", 4) == 0 || memcmp(request.bytes, "FTRE", 4) == 0));
    NSString *engineRoot = _engineRoot;
    if (engine && engineRoot) {
        // The build's state and the tree are quick to answer and are asked from the caller's thread. The
        // editor's calls run deep, and get the editor thread's stack.
        if (!editor) return callAotEngine(request);
        __block NSData *answer = [NSData data];
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        [_editorPending lock];
        [_editorJobs addObject:^{ answer = callAotEngine(request); dispatch_semaphore_signal(done); }];
        [_editorPending signal];
        [_editorPending unlock];
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
        return answer;
    }
    if (editor) return [self callEditorRequest:request];
    __block NSData *answer = [NSData data];
    [self onJavaThread:^{
        // A layout, with the engine compiled ahead of time answering the rest: the VM's engine opens the
        // project now, the first time one is asked for.
        if (engineRoot && [self openInVm:engineRoot].length) return;
        // With no VM there is no dispatcher to say why; the caller has already been told by openProject.
        if (![self startIfNeeded]) return;
        answer = [self call:request env:self->_env];
    }];
    return answer;
}

@end

@implementation JvmHost
+ (nullable id)start {
    static JvmChannel *channel;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ channel = [JvmChannel new]; });
    return channel;
}
@end

#else

@implementation JvmHost
+ (nullable id)start { return nil; }
@end

#endif
