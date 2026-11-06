#!/usr/bin/env bash
# Print the name of the toolchain build the current sources make: a short hash of everything that goes into
# it (the build script, which pins the LLVM and NDK versions, and the daemon sources). The NDK toolchain
# workflow publishes a build as the GitHub release `ndk-toolchain-<key>`, and the NDK plugin release workflow
# packages the one whose key matches the commit it builds, so a change here means a new toolchain build and
# nothing else does.
set -euo pipefail
cd "$(dirname "$0")"
{
    sha256sum build-llvm-android.sh daemon/*.c 2>/dev/null || shasum -a 256 build-llvm-android.sh daemon/*.c
} | awk '{print $1}' | { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-12
