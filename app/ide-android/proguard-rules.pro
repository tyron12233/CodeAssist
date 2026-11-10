# ============================================================================
# R8 keep rules for the shipping CodeAssist app (`release`, and `profile`, which
# inherits it). See the `release` block in build.gradle.kts.
#
# The app HOSTS a full on-device toolchain: the Kotlin K2 compiler, the IntelliJ
# platform, Eclipse JDT/ecj, D8/R8/apksig, and ASM. It reaches large parts of
# that toolchain reflectively (ServiceLoader / META-INF/services, IntelliJ
# extension-point + component registration, Class.forName on config-driven
# names) and it dexes user code at runtime. R8 tree-shaking/renaming would strip
# or rename classes only reached reflectively, and the failure surfaces as a
# RUNTIME crash while compiling/running a user project, not a build error.
#
# So: keep the reflective toolchain and the libraries user code links against
# wholesale, and let R8 shrink, optimize and RENAME the rest. Every change here
# needs the on-device feature suite (:ide-android-e2e) to pass before it ships.
#
# Google Play scores this file. For a bundle built with R8, Play reads R8's own
# stats (BUNDLE-METADATA/com.android.tools/r8.json, locally
# build/intermediates/r8_metadata/<variant>/.../r8-metadata.dat): the share of
# the FINAL app's classes, methods and fields that keep rules stop R8 from
# renaming / optimizing / removing. Apps with >10 MB of dex must keep each share
# at or under 75% (25% obfuscated, optimized, shrunk) from February 2027. Note
# the denominator is what SURVIVES: deleting dead code does not help, pinning
# less of the live code does. A wholesale keep on a big package moves the score
# by whole percentage points, so prefer the narrowest keep that works.
#
# Two shapes of keep below:
#  { *; }  the whole class. For code that reflects over PRIVATE members by
#          name: Jackson/Gson/protobuf binding, Caffeine, JGit's NLS bundles,
#          the interpreters' own runtime.
#  { public protected *; <init>(...); volatile <fields>; }
#          the class name and everything another class can LINK to. Outside
#          code (plugins, interpreted user code, XML-registered extensions,
#          runtime-dexed KSP processors) can only name public/protected
#          members; reflective instantiation needs the constructors; and the
#          fields reached by name through field updaters and the ART VarHandle
#          shim (VarHandleArtPass -> VarHandleCompat) are volatile by contract.
#          Private methods and plain private fields are then R8's to rename,
#          inline or drop, which is most of what moves the Play score.
# ============================================================================

# Java serialization looks these up by name.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- Bundled toolchains: kept by their linkable surface ---------------------
-keep class org.jetbrains.kotlin.** { public protected *; <init>(...); volatile <fields>; }
# kotlinc's argument parser reads each option's @Argument off the PRIVATE backing
# field, found by reflection (ArgumentUtilsKt.getArgumentAnnotation). Renamed or
# dropped fields make every compile die in a static initializer
# (ExceptionInInitializerError <- NPE). Device-verified 2026-11-09.
-keep class org.jetbrains.kotlin.cli.common.arguments.** { *; }
-keep class org.jetbrains.** { public protected *; <init>(...); volatile <fields>; }
-keep class com.intellij.** { public protected *; <init>(...); volatile <fields>; }
-keep class org.eclipse.jdt.** { public protected *; <init>(...); volatile <fields>; }
-keep class org.eclipse.core.** { public protected *; <init>(...); volatile <fields>; }
-keep class org.eclipse.osgi.** { public protected *; <init>(...); volatile <fields>; }
# R8/D8 run IN-PROCESS for small dex jobs (ForkedDexPolicy; the forked path uses
# the separate r8.dex.zip asset). Its public API is what the app calls; its
# `internal` package is already R8 output (obfuscated by Google) and R8 adapts
# its one META-INF/services entry when renaming, so only the API keeps names.
-keep class !com.android.tools.r8.internal.**,com.android.tools.r8.** { public protected *; <init>(...); volatile <fields>; }
-keep class com.android.tools.build.** { public protected *; <init>(...); volatile <fields>; }
-keep class com.android.apksig.** { public protected *; <init>(...); volatile <fields>; }
-keep class org.objectweb.asm.** { public protected *; <init>(...); volatile <fields>; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { public protected *; <init>(...); volatile <fields>; }
-keep class kotlin.Metadata { *; }

# --- The app's own code (dev.ide) -------------------------------------------
# Only what something outside the app's own compiled code reaches BY NAME keeps
# its name; the rest (UI, the engine's implementations) is renamed and optimized.
#
# 1. The published plugin SPI: a plugin APK compiles against the published
#    artifacts and links the host's copies by name and descriptor. These are the
#    packages of every class in the :spi-compat baselines (<module>/api/*.api).
#    A NEW SPI PACKAGE MUST BE ADDED HERE (ProguardSpiKeepsTest fails until it is).
-keep class dev.ide.agent.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.analysis.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.block.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.build.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.index.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.interp.api.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.completion.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.dom.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.folding.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.formatting.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.highlight.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.hints.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.imports.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.incremental.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.patterns.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.postfix.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.resolve.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.signature.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.synthetic.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lang.template.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.lsp.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.model.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.model.event.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.model.graph.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.model.sync.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.model.template.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.platform.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.platform.impl.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.platform.log.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.platform.notify.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.platform.settings.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.platform.storage.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.plugin.** { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.vcs.* { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.vfs.* { public protected *; <init>(...); volatile <fields>; }
# 2. Code that interpreted or runtime-generated code calls by name: the preview
#    and console interpreters and their bytecode VM (peers, reflection mirrors,
#    the Compose render harness), AWT/Swing support, the preview tools and the
#    log bridge injected into user apps, the console run bridge, the forked
#    compiler worker, KSP/compiler-plugin hosting, and the ART compat shims that
#    tool bytecode is rewritten to call. Plus the shaded protobuf (reflective)
#    and the MCP server's Jackson-bound types.
-keep class dev.ide.interp.** { *; }
-keep class dev.ide.jvm.** { *; }
-keep class dev.ide.vm.** { *; }
-keep class dev.ide.awt.** { *; }
-keep class dev.ide.swing.** { *; }
-keep class dev.ide.preview.** { *; }
-keep class dev.ide.applog.** { *; }
-keep class dev.ide.build.jvm.run.** { *; }
-keep class dev.ide.android.fork.** { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.kotlin.** { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.ksp.** { public protected *; <init>(...); volatile <fields>; }
# KSP's API (symbol-processing-api + -common-deps) ships IN the app, while the KSP runner and the bundled
# processors (Room, Hilt, Moshi, ...) are dexed at runtime and load it parent-first, BY NAME: KSPJvmConfig,
# SymbolProcessorProvider, KSPLogger, the Resolver/KS* model. Renamed, no processor loads at all and every
# Room build fails ("no SymbolProcessorProvider found"). Device-verified 2026-11-10 (KspRoomTest).
-keep class com.google.devtools.ksp.** { *; }
# The app also ships the JDK APIs Android lacks that annotation processors run on (javax.annotation.processing,
# javax.lang.model, javax.tools, javax.inject, javax.annotation, StAX). Nothing in the app calls them, so R8
# would drop them, but the runtime-dexed processors resolve them from the app by name, and their bundles don't
# carry them (they're JDK classes everywhere else). Without them Room dies loading its own RoomProcessor:
# "Failed resolution of: ...javac/JavacBasicAnnotationProcessor" (its superclass AbstractProcessor is gone).
-keep class javax.** { *; }
-keep class dev.ide.psi.** { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.**.compat.** { public protected *; <init>(...); volatile <fields>; }
-keep class dev.ide.shadedproto.** { *; }
-keep class dev.ide.agent.mcp.** { *; }
# Renamed classes stay in their packages: crash scrubbing (CrashScrub) tells the
# app's own frames apart by the "dev.ide." prefix.
-keeppackagenames dev.ide.**

# --- Hardening: reflection-sensitive libraries R8 stripped in the first pass.
#     Each is reached by a name/reflection/service mechanism R8 can't trace, so
#     tree-shaking them risks a runtime crash in a real feature. Kept for
#     correctness (measured cost: they add a few MB back). ---------------------
# BouncyCastle: keystore creation (Keystore Manager) registers + looks up crypto
# providers BY ALGORITHM NAME (Security provider SPI): the provider tables name
# their implementation classes as strings. Those live in the provider packages;
# the primitives under them (asn1, crypto, math, cert, ...) are plain static calls.
-keep class org.bouncycastle.jcajce.** { *; }
-keep class org.bouncycastle.jce.** { *; }
-keep class org.bouncycastle.pqc.jcajce.** { *; }
-keep class org.bouncycastle.x509.** { *; }
# kotlin-metadata-jvm: the Kotlin editor backend decodes library @kotlin.Metadata
# through this to build symbols/completion for classpath binaries.
-keep class kotlin.metadata.** { public protected *; <init>(...); volatile <fields>; }
# Caffeine (IntelliJ platform caches): LocalCacheFactory/NodeFactory load generated
# cache classes by constructed name (e.g. cache.SSMSW) — classic R8 strip hazard.
-keep class com.github.benmanes.caffeine.cache.** { *; }
# kotlinx.serialization + okhttp: the AI agent's LLM client (:agent-impl) transport
# and @Serializable provider models. Serialization does reflective serializer lookup;
# okhttp selects TLS/platform integrations reflectively. Both are runtime-critical.
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { public protected *; <init>(...); volatile <fields>; }
-keep class okio.** { public protected *; <init>(...); volatile <fields>; }
# JGit (:vcs-impl): the transport layer registers protocols through
# ServiceLoader and resolves SystemReader/FS implementations by name, neither of
# which R8 can trace from a static reference.
-keep class org.eclipse.jgit.** { *; }
-keep class com.googlecode.javaewah.** { *; }

# --- Libraries the preview / console interpreters call BY NAME. A user's @Preview or `main` links against the
#     IDE's bundled Compose, coroutines, stdlib and AndroidX: the interpreter resolves those calls reflectively at
#     runtime, so whatever the IDE's own code doesn't happen to use looks dead to R8. Device-verified 2026-11-07:
#     without these the template project's preview failed ("Array contains no element matching the predicate");
#     R8 had removed ~9.1k androidx.compose and ~1.7k kotlin/kotlinx entries. --------------------------------------
-keep class androidx.compose.** { public protected *; <init>(...); volatile <fields>; }
-keep class kotlin.** { public protected *; <init>(...); volatile <fields>; }
-keep class kotlinx.coroutines.** { public protected *; <init>(...); volatile <fields>; }
-keep class androidx.lifecycle.** { public protected *; <init>(...); volatile <fields>; }
-keep class androidx.activity.** { public protected *; <init>(...); volatile <fields>; }
-keep class androidx.core.** { public protected *; <init>(...); volatile <fields>; }
-keep class androidx.collection.** { public protected *; <init>(...); volatile <fields>; }
-keep class androidx.savedstate.** { public protected *; <init>(...); volatile <fields>; }

# --- Reflection / serialization / protobuf driven libraries R8 trimmed heavily: bundletool + its protobuf model
#     (on-device .aab builds), Jackson + the MCP SDK + JSON schema + YAML (the agent's MCP client and skills), Gson.
# bundletool's shaded copy of R8: same reasoning as the app's own R8 above.
-keep class !shadow.bundletool.com.android.tools.r8.internal.**,shadow.bundletool.** { public protected *; <init>(...); volatile <fields>; }
-keep class com.android.bundle.** { *; }
-keep class com.android.aapt.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class tools.jackson.** { *; }
-keep class com.fasterxml.** { *; }
-keep class io.modelcontextprotocol.** { *; }
-keep class com.networknt.** { *; }
-keep class org.snakeyaml.** { *; }
-keep class com.google.gson.** { *; }

# NOTE: JNA is deliberately NOT kept. It arrives only via Eclipse core.filesystem
# (JDT); the native path is unused on ART (no libjnidispatch.so shipped, pure-Java
# fallback), and the JNA classes the kept Eclipse code references are retained
# anyway — so the ~1.4k removed JNA classes are dead weight on-device.

# --- The toolchains reference many optional deps that aren't on the runtime
#     classpath; we keep those packages wholesale, so silence the missing-class
#     noise from within them (real removals still show in the size delta). ----
-dontwarn org.jetbrains.**
-dontwarn com.intellij.**
-dontwarn org.eclipse.**
-dontwarn com.android.tools.**
-dontwarn com.android.apksig.**
-dontwarn com.google.**
-dontwarn kotlinx.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.**
-dontwarn sun.**
-dontwarn org.w3c.**
-dontwarn org.xml.sax.**
-dontwarn net.rubygrapefruit.**
-dontwarn org.slf4j.**
# bundletool's optional dagger/annotation closure isn't on the runtime classpath.
-dontwarn dagger.**
-dontwarn javax.inject.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.protobuf.**

# Benign missing references surfaced by R8 (AGP's missing_rules.txt): optional
# OpenTelemetry, the JPMS module API (java.lang.module.*, not on the Android
# bootclasspath — used reflectively by the index's module-info reader), and
# xerces internals.
-dontwarn io.opentelemetry.**
-dontwarn java.lang.module.**
-dontwarn org.apache.xerces.**

# --- Renaming is ON (Play scores it). Stack traces from the field need the
#     build's mapping.txt to read: AGP puts it in the bundle for Play Console,
#     and `retrace` takes it for anything else. Keep line numbers so a retraced
#     trace points at the right line. ------------------------------------------
-keepattributes SourceFile,LineNumberTable

# Small, by-name-reached libraries that were never kept but must keep their
# names now that renaming is on: IntelliJ's XML descriptors and JDOM.
-keep class org.intellij.** { public protected *; <init>(...); volatile <fields>; }
-keep class org.jdom.** { public protected *; <init>(...); volatile <fields>; }
