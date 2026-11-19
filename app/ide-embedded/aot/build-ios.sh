#!/bin/bash
# build-ios.sh <tool> <graalvm-home> <ios-kit> <out-dir> <jar>...
#
# One of the build's Java tools compiled ahead of time for the iOS app, whose JVM only interprets: <tool> names
# a folder here (d8) holding the tool's entry point (src) and what it reaches by reflection (config, traced on
# a desktop JVM with native-image's agent). Writes <out-dir>/libaot<tool>.dylib, which the app carries in its
# Frameworks and enters through the functions the entry point exports.
#
#   <graalvm-home>  GraalVM CE for macOS arm64 (graalvm/graalvm-ce-builds), which has native-image
#   <ios-kit>       that release's static libraries labeled for iOS, with the options to build and link against
#                   them (graalvm-mobile.properties): the ios-device kit of NucleusFramework/graalvm-mobile,
#                   built from <graalvm-home> by its build.sh
#   <jar>...        the tool's own jars (for d8, the build's r8 jar)
#
# The library exports only its entry points and the isolate's: it links its own copies of the JDK's native
# libraries, and the app's JVM has the same ones under the same names.
set -euo pipefail
tool=$1; graal=$(cd "$2" && pwd); kit=$(cd "$3" && pwd); out=$4; shift 4
here=$(cd "$(dirname "$0")" && pwd)/$tool
mkdir -p "$out"; out=$(cd "$out" && pwd)
work=$(mktemp -d)
prop() { sed -n "s/^$1=//p" "$kit/graalvm-mobile.properties"; }
cp=$(IFS=:; echo "$*")

"$graal/bin/javac" -cp "$cp" -d "$work/classes" $(find "$here/src" -name '*.java')
# shellcheck disable=SC2046
"$graal/bin/native-image" -cp "$work/classes:$cp" -H:ConfigurationFileDirectories="$here/config" \
  -Dsvm.platform="$(prop native-image.platform)" $(prop native-image.flags) \
  -H:+UnlockExperimentalVMOptions -H:+SharedLibrary -H:+ExitAfterRelocatableImageWrite -H:TempDirectory="$work/tmp" \
  -o "$work/aot$tool"
object=$(find "$work/tmp" -name "aot$tool.o" | head -1)

printf '_%s\n' "aot_$tool" graal_create_isolate graal_attach_thread graal_detach_thread graal_tear_down_isolate > "$work/exports.txt"
# shellcheck disable=SC2046
xcrun --sdk iphoneos clang -target arm64-apple-ios15.0 -dynamiclib -install_name "@rpath/libaot$tool.dylib" \
  -o "$out/libaot$tool.dylib" "$object" -L"$kit/lib" $(prop link.flags) -Wl,-exported_symbols_list,"$work/exports.txt"
echo "$out/libaot$tool.dylib"
