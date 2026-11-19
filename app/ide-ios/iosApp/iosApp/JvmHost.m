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
    int file = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    (*env)->ReleaseStringUTFChars(env, output, path);
    // One process, one stdout: it is the file for as long as the tool runs, then what it was again.
    fflush(stdout); fflush(stderr);
    int savedOut = dup(STDOUT_FILENO), savedErr = dup(STDERR_FILENO);
    if (file >= 0) { dup2(file, STDOUT_FILENO); dup2(file, STDERR_FILENO); }
    int code = aapt2Main((int)count + 1, argv);
    fflush(stdout); fflush(stderr);
    dup2(savedOut, STDOUT_FILENO); dup2(savedErr, STDERR_FILENO);
    close(savedOut); close(savedErr);
    if (file >= 0) close(file);
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

@implementation JvmChannel {
    NSCondition *_pending;
    NSMutableArray<dispatch_block_t> *_jobs;
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
        pthread_attr_destroy(&attributes);
    }
    return self;
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
        @"-Xmx2g",
    ];
    // What the VM and the backend print (a stack trace, a fatal error) has no console on a phone. It goes to a
    // file in Documents, started afresh with each VM, where the Files app and a debugger can both read it.
    NSString *log = [documents stringByAppendingPathComponent:@"embedded-backend.log"];
    freopen(log.fileSystemRepresentation, "w", stderr);
    dup2(fileno(stderr), fileno(stdout));
    setvbuf(stderr, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IOLBF, 0);
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
    if (aapt2Main) {
        jclass tool = (*_env)->FindClass(_env, "dev/ide/embedded/NativeAapt2");
        JNINativeMethod method = { "run", "([Ljava/lang/String;Ljava/lang/String;)I", (void *)aapt2Run };
        if (!tool || (*_env)->RegisterNatives(_env, tool, &method, 1) != JNI_OK) {
            fprintf(stderr, "aapt2 is in the app but could not be registered: %s\n", ([self takeException] ?: @"no reason given").UTF8String);
        }
    }
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

- (NSString *)openProjectProjectRoot:(NSString *)projectRoot {
    __block NSString *answer = @"";
    [self onJavaThread:^{
        if (![self startIfNeeded]) { answer = self->_startFailure; return; }
        JNIEnv *env = self->_env;
        (*env)->PushLocalFrame(env, 16);
        NSString *home = [[NSBundle mainBundle].bundlePath stringByAppendingPathComponent:@"layoutlib"];
        NSString *caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject;
        jstring result = (jstring)(*env)->CallStaticObjectMethod(env, self->_host, self->_open,
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
    }];
    return answer;
}

- (NSData *)callRequest:(NSData *)request {
    __block NSData *answer = [NSData data];
    [self onJavaThread:^{
        // With no VM there is no dispatcher to say why; the caller has already been told by openProject.
        if (![self startIfNeeded]) return;
        JNIEnv *env = self->_env;
        (*env)->PushLocalFrame(env, 8);
        jbyteArray bytes = (*env)->NewByteArray(env, (jsize)request.length);
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)request.length, request.bytes);
        jbyteArray reply = (jbyteArray)(*env)->CallStaticObjectMethod(env, self->_host, self->_call, bytes);
        if (![self takeException] && reply) {
            jsize length = (*env)->GetArrayLength(env, reply);
            NSMutableData *data = [NSMutableData dataWithLength:(NSUInteger)length];
            (*env)->GetByteArrayRegion(env, reply, 0, length, data.mutableBytes);
            answer = data;
        }
        (*env)->PopLocalFrame(env, NULL);
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
