#!/usr/bin/env bash
# Run the NDK plugin's unit tests on ART, on every attached arm64 device or emulator.
#
# Why: the JVM is the more permissive of the two, so a JVM-green test proves less than it looks. This
# plugin has already shipped one bug only ART could see (ICU's regex engine rejects an unescaped `}` that the
# JVM accepts, which killed completion at class-init), and the daemon half of it (ClangDaemonDeviceTest) only
# runs where libndkclangd.so can: arm64 Android. So the same JUnit 5 suite is dexed and run under dalvikvm,
# with the toolchain pushed beside it so the device-only tests have a real clang to talk to.
#
# Usage:  samples/ndk-plugin/run-tests-on-device.sh [test-package]     (default: dev.codeassist.ndk)
# Needs:  adb, an Android SDK with build-tools (for d8), a JDK (javac), and a built toolchain in
#         tools/ndk-toolchain/build/out (or -Pndk.toolchain.dir's equivalent via NDK_TOOLCHAIN_DIR).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
pkg="${1:-dev.codeassist.ndk}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
adb="$sdk/platform-tools/adb"
d8="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)d8"
toolchain="${NDK_TOOLCHAIN_DIR:-$root/tools/ndk-toolchain/build/out}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
remote=/data/local/tmp/ndk-test

log() { printf '\n==> %s\n' "$*"; }

log "compiling the tests and capturing their runtime classpath"
cat > "$work/cp.gradle" <<'EOF'
allprojects {
    tasks.matching { it.name == "testDebugUnitTest" && it.project.path == ":samples:ndk-plugin" }.configureEach { t ->
        t.doFirst {
            new File(System.getProperty("cpOut")).text = t.classpath.files.join("\n")
            throw new StopExecutionException()
        }
    }
}
EOF
(cd "$root" && ./gradlew :samples:ndk-plugin:testDebugUnitTest --rerun -q -I "$work/cp.gradle" -DcpOut="$work/cp.txt")
android_jar="$(grep '/android.jar$' "$work/cp.txt")"
grep -v -E '/android.jar$|/R.jar$' "$work/cp.txt" > "$work/run-cp.txt"

log "compiling the launcher"
mkdir -p "$work/launcher" "$work/jars"
cat > "$work/ArtTestMain.java" <<'EOF'
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import java.nio.file.*;
import java.util.stream.*;

/**
 * A JUnit Platform launcher small enough to run under dalvikvm: one line per test, failures at the end.
 * args[0] is a file listing the test classes, one per line: package scanning walks the classpath as a jar
 * FILE SYSTEM, which ART does not provide, so it finds nothing there.
 */
public class ArtTestMain {
    public static void main(String[] args) {
        java.util.List<String> names;
        try { names = Files.readAllLines(Paths.get(args[0])); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(names.stream().filter(n -> !n.isEmpty()).map(DiscoverySelectors::selectClass).collect(Collectors.toList()))
            .build();
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        TestExecutionListener printer = new TestExecutionListener() {
            @Override public void executionFinished(TestIdentifier id, TestExecutionResult r) {
                if (!id.isTest()) return;
                String name = id.getUniqueId().replaceAll(".*\\[class:([^\\]]*)\\].*\\[method:([^\\]]*)\\].*", "$1#$2");
                System.out.println(r.getStatus() + "  " + name);
                r.getThrowable().ifPresent(t -> System.out.println("    " + t));
            }
            @Override public void executionSkipped(TestIdentifier id, String reason) {
                if (id.isTest()) System.out.println("SKIPPED  " + id.getDisplayName() + ": " + reason);
            }
        };
        LauncherFactory.create().execute(request, summary, printer);
        TestExecutionSummary s = summary.getSummary();
        System.out.println("\nTOTAL found=" + s.getTestsFoundCount() + " ok=" + s.getTestsSucceededCount()
            + " failed=" + s.getTestsFailedCount() + " skipped=" + (s.getTestsSkippedCount() + s.getTestsAbortedCount()));
        s.getFailures().forEach(f -> {
            System.out.println("\nFAILURE " + f.getTestIdentifier().getDisplayName());
            f.getException().printStackTrace(System.out);
        });
        System.exit(s.getTestsFailedCount() == 0 ? 0 : 1);
    }
}
EOF
javac -nowarn -d "$work/launcher" -cp "$(tr '\n' ':' < "$work/run-cp.txt")" --release 11 "$work/ArtTestMain.java" 2>/dev/null

log "renaming test methods dex cannot represent"
# Kotlin's backtick test names (`a note is folded into the error above`) are legal JVM names, but dex rejects
# commas, apostrophes and parentheses outright, and spaces below API 30. JUnit finds tests by annotation, not
# by name, so the methods are renamed in a copy of the compiled test classes -- with every call to them in
# those classes -- rather than renaming the tests in source.
asm="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm" -name 'asm-9*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort -V | tail -1)"
[ -n "$asm" ] || { echo "no ASM 9 jar in the Gradle cache" >&2; exit 1; }
mkdir -p "$work/renamer" "$work/test-classes"
cat > "$work/DexSafeNames.java" <<'JAVA'
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Renames, in place, every method in a class tree whose name dex cannot hold; call sites follow. */
public class DexSafeNames {
    static boolean safe(String n) {
        return n.startsWith("<") || n.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-');
    }

    public static void main(String[] args) throws Exception {
        List<Path> classes;
        try (Stream<Path> s = Files.walk(Paths.get(args[0]))) {
            classes = s.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
        }
        Map<String, String> renames = new HashMap<>(); // owner.name+desc -> new name
        for (Path p : classes) {
            ClassReader r = new ClassReader(Files.readAllBytes(p));
            int[] n = {0};
            r.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int a, String name, String desc, String sig, String[] ex) {
                    if (!safe(name)) renames.put(r.getClassName() + "." + name + desc,
                        name.replaceAll("[^A-Za-z0-9_$]+", "_") + "_" + (n[0]++));
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        for (Path p : classes) {
            ClassReader r = new ClassReader(Files.readAllBytes(p));
            ClassWriter w = new ClassWriter(0);
            r.accept(new ClassVisitor(Opcodes.ASM9, w) {
                @Override public MethodVisitor visitMethod(int a, String name, String desc, String sig, String[] ex) {
                    String renamed = renames.getOrDefault(r.getClassName() + "." + name + desc, name);
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(a, renamed, desc, sig, ex)) {
                        @Override public void visitMethodInsn(int op, String owner, String n2, String d2, boolean itf) {
                            super.visitMethodInsn(op, owner, renames.getOrDefault(owner + "." + n2 + d2, n2), d2, itf);
                        }
                    };
                }
            }, 0);
            Files.write(p, w.toByteArray());
        }
        System.out.println("renamed " + renames.size() + " methods");
    }
}
JAVA
javac -nowarn -d "$work/renamer" -cp "$asm" "$work/DexSafeNames.java"
test_classes="$(grep '/compileDebugUnitTestKotlin/classes$' "$work/run-cp.txt")"
cp -R "$test_classes/." "$work/test-classes/"
java -cp "$work/renamer:$asm" DexSafeNames "$work/test-classes"
# The test classes, by name, for the launcher: top-level classes under the package asked for.
(cd "$work/test-classes" && find . -name '*.class' ! -name '*$*' | sed -e 's|^\./||' -e 's|\.class$||' -e 's|/|.|g' \
    | grep "^$pkg\." | sort) > "$work/classes.txt"
grep -v -x -F "$test_classes" "$work/run-cp.txt" > "$work/run-cp2.txt"
echo "$work/test-classes" >> "$work/run-cp2.txt"
mv "$work/run-cp2.txt" "$work/run-cp.txt"

log "dexing"
# d8 wants jars, so the class and resource directories on the classpath are zipped first.
i=0
inputs=()
while IFS= read -r entry; do
    if [ -d "$entry" ]; then
        i=$((i + 1)); (cd "$entry" && zip -qr "$work/jars/dir$i.jar" .) && inputs+=("$work/jars/dir$i.jar")
    elif [ -f "$entry" ]; then
        inputs+=("$entry")
    fi
done < "$work/run-cp.txt"
(cd "$work/launcher" && zip -qr "$work/jars/launcher.jar" .)
inputs+=("$work/jars/launcher.jar")
mkdir -p "$work/dex"
# API 30, not the plugin's 26: the tests use backtick names with spaces, which DEX only allows from version
# 040 (API 30). The PLUGIN still runs on 26; only this harness cannot, and says so per device below.
"$d8" --min-api 30 --lib "$android_jar" --output "$work/dex" "${inputs[@]}"
# Resources (junit-platform.properties, service files) ride along in the same jar as the dex.
mkdir -p "$work/res"
for jar in "${inputs[@]}"; do (cd "$work/res" && unzip -qo "$jar" -x '*.class' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/MANIFEST.MF' 2>/dev/null) || true; done
cp "$work"/dex/classes*.dex "$work/res/"
(cd "$work/res" && zip -qr "$work/tests.jar" .)

devices="$("$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
[ -n "$devices" ] || { echo "no device attached" >&2; exit 1; }

status=0
for serial in $devices; do
    api="$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    abi="$("$adb" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
    log "$serial (API $api, $abi)"
    if [ "$api" -lt 30 ]; then
        echo "skipped: the dexed tests need API 30+ (spaces in test names); this device is API $api"
        continue
    fi
    "$adb" -s "$serial" shell "rm -rf $remote/tmp; mkdir -p $remote/lib $remote/tc $remote/tmp"
    if [ "$abi" = "arm64-v8a" ] && [ -d "$toolchain/jniLibs/arm64-v8a" ]; then
        "$adb" -s "$serial" push --sync "$toolchain/jniLibs/arm64-v8a/." "$remote/lib/" >/dev/null
        "$adb" -s "$serial" push --sync "$toolchain/assets/toolchain/." "$remote/tc/" >/dev/null
        "$adb" -s "$serial" shell "chmod 755 $remote/lib/*"
    fi
    "$adb" -s "$serial" push "$work/tests.jar" "$remote/tests.jar" >/dev/null
    "$adb" -s "$serial" push "$work/classes.txt" "$remote/classes.txt" >/dev/null
    "$adb" -s "$serial" shell "cd $remote/tmp && dalvikvm -Xmx512m -Djava.io.tmpdir=$remote/tmp \
        -Dndk.lib=$remote/lib -Dndk.toolchain=$remote/tc -cp $remote/tests.jar ArtTestMain $remote/classes.txt" \
        | tee "$work/result-$serial.txt" || true
    grep -q '^TOTAL .* failed=0 ' "$work/result-$serial.txt" || status=1
done
exit $status
