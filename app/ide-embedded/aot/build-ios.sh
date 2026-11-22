#!/bin/bash
# build-ios.sh <graalvm-home> <ios-kit> <out-dir> <jar>...
#
# The engine compiled ahead of time for the iOS app, whose JVM only interprets: the project model, the indexes,
# the language services and the build with its compilers, in one GraalVM native image (see EmbeddedEngineHost
# for what stays in the JVM). D8 and the Kotlin compiler can also be entered by themselves, for a build that
# runs in the JVM. Writes <out-dir>/libaottools.dylib, which the app carries in its Frameworks and enters
# through the functions the entry points (src) export.
#
#   <graalvm-home>  GraalVM CE for macOS arm64 (graalvm/graalvm-ce-builds), which has native-image
#   <ios-kit>       that release's static libraries labeled for iOS, with the options to build and link against
#                   them (graalvm-mobile.properties): the ios-device kit of NucleusFramework/graalvm-mobile,
#                   built from <graalvm-home> by its build.sh
#   <jar>...        every jar that `./gradlew :ide-embedded:assembleEngineAot` puts in build/aot/engine/jars
#
# What the tools reach by reflection is in the config-* folders: traced on a desktop JVM with native-image's
# agent (config-d8, config-kotlinc, config-engine), and for the compiler's syntax tree, whose nodes are all made by
# reflection, listed from the jars by gen/PsiConstructors.java (config-psi).
#
# The library exports only the entry points and the isolate's functions: it links its own copies of the JDK's
# native libraries, and the app's JVM has the same ones under the same names.
set -euo pipefail
graal=$(cd "$1" && pwd); kit=$(cd "$2" && pwd); out=$3; shift 3
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"; out=$(cd "$out" && pwd)
work=$(mktemp -d)
prop() { sed -n "s/^$1=//p" "$kit/graalvm-mobile.properties"; }
cp=$(IFS=:; echo "$*")
configs=$(ls -d "$here"/config-* | paste -sd, -)

# shellcheck disable=SC2046
"$graal/bin/javac" -cp "$cp" -d "$work/classes" $(find "$here/src" -name '*.java')
# shellcheck disable=SC2046
"$graal/bin/native-image" -cp "$work/classes:$cp" -H:ConfigurationFileDirectories="$configs" $(cat "$here/options") \
  -Dsvm.platform="$(prop native-image.platform)" $(prop native-image.flags) \
  -H:+UnlockExperimentalVMOptions -H:+SharedLibrary -H:+ExitAfterRelocatableImageWrite -H:TempDirectory="$work/tmp" \
  -o "$work/aottools"
object=$(find "$work/tmp" -name "aottools.o" | head -1)

# Every function the entry points export, by the name its annotation gives it.
{ grep -rho '@CEntryPoint(name = "[a-z0-9_]*")' "$here/src" | sed 's/.*"\(.*\)".*/_\1/'
  printf '_%s\n' graal_create_isolate graal_attach_thread graal_detach_thread graal_tear_down_isolate; } > "$work/exports.txt"
# The image's runtime names compiler-rt's cache flush, which the iOS SDK's link does not bring: the system's own.
printf '#include <libkern/OSCacheControl.h>\nvoid __clear_cache(void *start, void *end) { sys_icache_invalidate(start, (char *)end - (char *)start); }\n' > "$work/clear_cache.c"
xcrun --sdk iphoneos clang -target arm64-apple-ios15.0 -c "$work/clear_cache.c" -o "$work/clear_cache.o"
# CFNetwork: the engine downloads (libraries, platforms), and the JDK's networking asks the system for its proxies.
# shellcheck disable=SC2046
xcrun --sdk iphoneos clang -target arm64-apple-ios15.0 -dynamiclib -install_name "@rpath/libaottools.dylib" \
  -o "$out/libaottools.dylib" "$object" "$work/clear_cache.o" -L"$kit/lib" $(prop link.flags) -framework CFNetwork -framework SystemConfiguration -Wl,-exported_symbols_list,"$work/exports.txt"
echo "$out/libaottools.dylib"
