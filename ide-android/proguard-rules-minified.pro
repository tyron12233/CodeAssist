# ============================================================================
# EXPERIMENTAL R8 keep rules for the CodeAssist app's own (non-shipping)
# `minified` build type. See the `minified` block in build.gradle.kts.
#
# The app HOSTS a full on-device toolchain: the Kotlin K2 compiler, the IntelliJ
# platform, Eclipse JDT/ecj, D8/R8/apksig, and ASM. It reaches large parts of
# that toolchain reflectively (ServiceLoader / META-INF/services, IntelliJ
# extension-point + component registration, Class.forName on config-driven
# names) and it dexes user code at runtime. R8 tree-shaking/renaming would strip
# or rename classes only reached reflectively, and the failure surfaces as a
# RUNTIME crash while compiling/running a user project, not a build error.
#
# This is a CONSERVATIVE first pass: keep the reflective toolchain wholesale and
# let R8 tree-shake only the clearly-safe libraries (AndroidX, Compose,
# coroutines, guava, Play services, ...). The goal is to MEASURE the safe floor
# of savings, not to ship. Tighten package-by-package later, validating on-device
# after every change.
# ============================================================================

# --- Bundled toolchains: kept wholesale (heavily reflective) ----------------
-keep class org.jetbrains.kotlin.** { *; }
-keep class org.jetbrains.** { *; }
-keep class com.intellij.** { *; }
-keep class org.eclipse.jdt.** { *; }
-keep class org.eclipse.core.** { *; }
-keep class org.eclipse.osgi.** { *; }
-keep class com.android.tools.r8.** { *; }
-keep class com.android.tools.build.** { *; }
-keep class com.android.apksig.** { *; }
-keep class org.objectweb.asm.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlin.Metadata { *; }

# --- The app's own engine + plugins (EP registry / plugin loader use FQNs) --
-keep class dev.ide.** { *; }

# --- Hardening: reflection-sensitive libraries R8 stripped in the first pass.
#     Each is reached by a name/reflection/service mechanism R8 can't trace, so
#     tree-shaking them risks a runtime crash in a real feature. Kept for
#     correctness (measured cost: they add a few MB back). ---------------------
# BouncyCastle: keystore creation (Keystore Manager) registers + looks up crypto
# providers BY ALGORITHM NAME (Security provider SPI) — R8 sees no static ref.
-keep class org.bouncycastle.** { *; }
# kotlin-metadata-jvm: the Kotlin editor backend decodes library @kotlin.Metadata
# through this to build symbols/completion for classpath binaries.
-keep class kotlin.metadata.** { *; }
# Caffeine (IntelliJ platform caches): LocalCacheFactory/NodeFactory load generated
# cache classes by constructed name (e.g. cache.SSMSW) — classic R8 strip hazard.
-keep class com.github.benmanes.caffeine.cache.** { *; }
# kotlinx.serialization + okhttp: the AI agent's LLM client (:agent-impl) transport
# and @Serializable provider models. Serialization does reflective serializer lookup;
# okhttp selects TLS/platform integrations reflectively. Both are runtime-critical.
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
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
# bundletool's protobuf/dagger closure is dropped from the minified measurement classpath (see
# build.gradle.kts stripBundletoolDex) — silence the resulting missing-reference reports.
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

# --- First measurement pass: isolate tree-shaking. Don't rename, since the big
#     packages are kept anyway (obfuscation buys little) and un-renamed output
#     makes the size accounting + any runtime stack traces readable. ----------
-dontobfuscate
