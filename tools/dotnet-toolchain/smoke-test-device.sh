#!/usr/bin/env bash
# Prove the assembled toolchain on a real device: push it, compile a C# program with csc, run the result,
# and check what it printed. The program touches the parts that have failed on a device before: the
# runtime starting at all, the core library, LINQ, and SHA-256 through the carried OpenSSL.
#
# It runs out of /data/local/tmp, which the shell user may execute from. That is NOT where the plugin runs
# it (an app executes the launcher from its unpacked lib directory), but it is the cheapest place to find
# out whether the binaries work, with no APK in the way.
#
# Usage: ./smoke-test-device.sh [out-dir]      out-dir defaults to build/out/<the device's ABI>
set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-adb}"
DEV=/data/local/tmp/ca-dotnet

log() { printf '\n==> %s\n' "$*"; }

abi=$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')
api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
OUT="${1:-build/out/$abi}"
[ -d "$OUT/tree" ] || { echo "no toolchain for this device's ABI ($abi) under $OUT; run build-dotnet-android.sh with HOST_ABI=$abi" >&2; exit 1; }

log "pushing the toolchain to $DEV (device: $abi, API $api)"
$ADB shell "rm -rf $DEV && mkdir -p $DEV/work $DEV/home $DEV/tmp"
$ADB push "$OUT/tree" "$DEV/dotnet" >/dev/null
$ADB push "$OUT/jniLibs/$abi/libdotnetlaunch.so" "$DEV/" >/dev/null
$ADB shell "chmod 755 $DEV/libdotnetlaunch.so"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/Smoke.cs" <<'EOF'
using System;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

record Point(int X, int Y);

class Program {
    static void Main() {
        Console.WriteLine($"runtime {Environment.Version} {System.Runtime.InteropServices.RuntimeInformation.RuntimeIdentifier}");
        Console.WriteLine(new Point(3, 4));
        Console.WriteLine(string.Join(",", Enumerable.Range(1, 5).Select(i => i * i)));
        Console.WriteLine(Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("abc"))));
    }
}
EOF

cat > "$tmp/Smoke.runtimeconfig.json" <<'EOF'
{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"},"configProperties":{"System.Globalization.Invariant":true}}}
EOF

# The environment the plugin gives every .NET process.
cat > "$tmp/run.sh" <<EOF
export DOTNET_ROOT=$DEV/dotnet DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 DOTNET_OPENSSL_VERSION_OVERRIDE=3
export LD_LIBRARY_PATH=$DEV/dotnet/lib HOME=$DEV/home TMPDIR=$DEV/tmp
cd $DEV/work
refs=""
for f in $DEV/dotnet/ref/*.dll; do refs="\$refs -r:\$f"; done
# Milliseconds now. Android 8's `date` has no %N and prints the letter, so there the figure is whole seconds.
now() { t=\$(date +%s%N); case \$t in *N) echo \$(( \${t%N} * 1000 )) ;; *) echo \$(( t / 1000000 )) ;; esac; }
started=\$(now)
$DEV/libdotnetlaunch.so $DEV/dotnet/sdk/Roslyn/csc.dll -nologo -noconfig -target:exe -out:Smoke.dll \$refs Smoke.cs || exit 1
echo "compiled in \$(( \$(now) - started )) ms" >&2
started=\$(now)
$DEV/libdotnetlaunch.so Smoke.dll || exit 2
echo "ran in \$(( \$(now) - started )) ms" >&2
EOF

$ADB push "$tmp/Smoke.cs" "$tmp/Smoke.runtimeconfig.json" "$DEV/work/" >/dev/null
$ADB push "$tmp/run.sh" "$DEV/" >/dev/null

log "compiling and running Smoke.cs"
got="$($ADB shell "sh $DEV/run.sh" | tr -d '\r')"
printf '%s\n' "$got"

want_tail='Point { X = 3, Y = 4 }
1,4,9,16,25
BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD'
if [ "$(printf '%s\n' "$got" | tail -n 3)" != "$want_tail" ]; then
    echo "FAILED: the program did not print what was expected" >&2
    exit 1
fi

$ADB shell "rm -rf $DEV"
log "OK on $abi, API $api"
