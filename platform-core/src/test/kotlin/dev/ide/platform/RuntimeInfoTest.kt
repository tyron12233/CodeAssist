package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RuntimeInfo] gates the 32-bit-ARM-ART crash mitigation (collapsing background index concurrency), so a
 * wrong verdict either leaves a fragile 32-bit device crashing or needlessly slows a 64-bit one. These pin the
 * `os.arch` inference the launcher's authoritative signal falls back to — the fallback is what tests and the
 * desktop rely on. The authoritative override path is exercised in the engine (an Android-only API), not here.
 */
class RuntimeInfoTest {

    @Test
    fun sixtyFourBitArchesAreNot32Bit() {
        for (arch in listOf("aarch64", "arm64", "arm64-v8a", "x86_64", "amd64", "x64", "ppc64le", "riscv64")) {
            assertFalse(RuntimeInfo.infer32BitFromArch(arch), "$arch must be treated as 64-bit")
        }
    }

    @Test
    fun thirtyTwoBitArchesAre32Bit() {
        // armv8l = a 32-bit userspace process on a 64-bit ARM core (exactly the crashing config: ABI 'arm').
        for (arch in listOf("arm", "armv7l", "armv8l", "armeabi-v7a", "x86", "i386", "i686")) {
            assertTrue(RuntimeInfo.infer32BitFromArch(arch), "$arch must be treated as 32-bit")
        }
    }

    @Test
    fun unknownOrMissingArchDefaultsToNot32Bit() {
        // Unknown/blank → false, so the mitigation (and its indexing slowdown) is never imposed by mistake.
        for (arch in listOf(null, "", "sparcv9", "mystery")) {
            assertFalse(RuntimeInfo.infer32BitFromArch(arch), "unrecognized arch '$arch' must default to 64-bit")
        }
    }
}
