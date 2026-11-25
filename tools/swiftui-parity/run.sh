#!/usr/bin/env bash
# SwiftUI parity: real SwiftUI (iOS Simulator) vs CodeAssist's engine (samples/swift-plugin/swiftui), per case of
# Cases.swift. Needs Xcode and a booted iPhone Simulator (the canvas is the iPhone 17 Pro's 402x874 points).
#
#   ./run.sh            build both sides, capture the reference, compare: prints the share of differing pixels per case
#   ./run.sh probe      print real SwiftUI's frames for Probe.swift's calibration views
#
# Output in $OUT (default /tmp/parity): ref/<case>.png, out/<case>.png (reference | ours | difference).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ENGINE="$HERE/../../samples/swift-plugin/swiftui/Sources"
OUT="${OUT:-/tmp/parity}"
mkdir -p "$OUT"

bundle() { # <name> <bundle id> <sources...>
    local name="$1" id="$2"; shift 2
    rm -rf "$OUT/$name.app"; mkdir -p "$OUT/$name.app"
    xcrun -sdk iphonesimulator swiftc -target arm64-apple-ios26.0-simulator -parse-as-library -O "$@" -o "$OUT/$name.app/$name"
    cat > "$OUT/$name.app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>$id</string><key>CFBundleExecutable</key><string>$name</string>
<key>CFBundleName</key><string>$name</string><key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string><key>CFBundleShortVersionString</key><string>1.0</string>
<key>MinimumOSVersion</key><string>26.0</string><key>UILaunchScreen</key><dict/>
<key>UIDeviceFamily</key><array><integer>1</integer></array>
</dict></plist>
PLIST
    codesign -s - --force "$OUT/$name.app" >/dev/null 2>&1
    xcrun simctl install booted "$OUT/$name.app"
}

if [[ "${1:-}" == probe ]]; then
    bundle Probe dev.codeassist.probe "$HERE/Probe.swift"
    xcrun simctl launch --console-pty --terminate-running-process booted dev.codeassist.probe | grep PROBE
    exit 0
fi

bundle Ref dev.codeassist.parity "$HERE/RefApp.swift" "$HERE/Cases.swift"
xcrun simctl launch --console-pty --terminate-running-process booted dev.codeassist.parity | grep -E 'PARITY (CASE|DONE)' | tail -1
container="$(xcrun simctl get_app_container booted dev.codeassist.parity data)"
rm -rf "$OUT/ref"; cp -R "$container/Documents/parity" "$OUT/ref"

# A case that does not compile against the engine is an API gap: stop, rather than run the previous build.
rm -f "$OUT/ours"
if ! swiftc -parse-as-library -module-name Ours -Onone "$ENGINE"/*.swift "$HERE/Cases.swift" "$HERE/Ours.swift" -o "$OUT/ours" 2> "$OUT/ours-build.log"; then
    grep -E 'error:' "$OUT/ours-build.log" >&2
    echo "Cases.swift does not compile against the engine (API gap): see above" >&2
    exit 1
fi
CODEASSIST_SYMBOL_METRICS="$HERE/../../samples/swift-plugin/swiftui/symbol-metrics.tsv" "$OUT/ours" "$OUT/ref" "$OUT/out"
