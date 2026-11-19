#!/bin/bash
# build-ios.sh <graalvm-home> <ios-kit> <out-dir> <jar>...
#
# The build's Java tools compiled ahead of time for the iOS app, whose JVM only interprets: D8 and the Kotlin
# compiler in one GraalVM native image, so the app carries one more runtime and one more heap, not one per
# tool. Writes <out-dir>/libaottools.dylib, which the app carries in its Frameworks and enters through the
# functions the entry points (src) export.
#
#   <graalvm-home>  GraalVM CE for macOS arm64 (graalvm/graalvm-ce-builds), which has native-image
#   <ios-kit>       that release's static libraries labeled for iOS, with the options to build and link against
#                   them (graalvm-mobile.properties): the ios-device kit of NucleusFramework/graalvm-mobile,
#                   built from <graalvm-home> by its build.sh
#   <jar>...        the tools' jars: the build's r8 jar, and every jar that
#                   `./gradlew :ide-embedded:assembleKotlincAot` puts in build/aot/kotlinc/jars
#
# What the tools reach by reflection is in the config-* folders: traced on a desktop JVM with native-image's
# agent (config-d8, config-kotlinc), and for the compiler's syntax tree, whose nodes are all made by
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
# shellcheck disable=SC2046
xcrun --sdk iphoneos clang -target arm64-apple-ios15.0 -dynamiclib -install_name "@rpath/libaottools.dylib" \
  -o "$out/libaottools.dylib" "$object" -L"$kit/lib" $(prop link.flags) -Wl,-exported_symbols_list,"$work/exports.txt"
echo "$out/libaottools.dylib"
