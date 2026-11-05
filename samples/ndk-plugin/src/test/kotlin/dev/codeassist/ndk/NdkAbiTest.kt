package dev.codeassist.ndk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which ABIs the plugin knows, and the names clang and the sysroot know them by. */
class NdkAbiTest {

    @Test
    fun `all four Android ABIs are known by their manifest names`() {
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), NdkAbi.names)
        NdkAbi.values().forEach { assertEquals(it, NdkAbi.byName(it.abiName)) }
    }

    @Test
    fun `an unknown ABI is null rather than a guess`() {
        assertNull(NdkAbi.byName("mips"))
        assertNull(NdkAbi.byName("armeabi"))
        assertNull(NdkAbi.byName(""))
    }

    @Test
    fun `targets carry the sysroot's API level`() {
        assertEquals("aarch64-linux-android26", NdkAbi.ARM64.target)
        assertEquals("armv7a-linux-androideabi26", NdkAbi.ARM32.target)
        assertEquals("i686-linux-android26", NdkAbi.X86.target)
        assertEquals("x86_64-linux-android26", NdkAbi.X86_64.target)
        // The constant older callers use must stay what arm64 resolves to.
        assertEquals(NdkToolchain.TARGET_TRIPLE, NdkAbi.ARM64.target)
    }

    @Test
    fun `armv7 is compiled as armv7a but its sysroot directory is named arm`() {
        assertEquals("arm-linux-androideabi", NdkAbi.ARM32.libTriple)
        assertTrue(NdkAbi.ARM32.target.startsWith("armv7a-"))
    }

    @Test
    fun `only 32-bit x86 is told to realign the stack`() {
        assertEquals(listOf("-mstackrealign"), NdkAbi.X86.extraFlags)
        assertTrue(NdkAbi.ARM64.extraFlags.isEmpty())
        assertTrue(NdkAbi.ARM32.extraFlags.isEmpty())
        assertTrue(NdkAbi.X86_64.extraFlags.isEmpty())
    }

    @Test
    fun `the device's ABI is its first entry the plugin knows`() {
        assertEquals(NdkAbi.ARM64, NdkAbi.forDevice(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(NdkAbi.ARM32, NdkAbi.forDevice(listOf("armeabi-v7a", "armeabi")))
        assertEquals(NdkAbi.X86, NdkAbi.forDevice(listOf("x86")))
        assertEquals(NdkAbi.X86_64, NdkAbi.forDevice(listOf("x86_64", "x86", "arm64-v8a")))
        assertEquals(NdkAbi.X86, NdkAbi.forDevice(listOf("mips", "x86")))
    }

    @Test
    fun `a device that lists nothing the plugin knows falls back to arm64`() {
        assertEquals(NdkAbi.ARM64, NdkAbi.forDevice(emptyList()))
        assertEquals(NdkAbi.ARM64, NdkAbi.forDevice(listOf("mips")))
    }

    @Test
    fun `a 32-bit program is loaded by linker and a 64-bit one by linker64`() {
        assertEquals("/system/bin/linker64", NativeLaunch.systemLinker(NdkAbi.ARM64))
        assertEquals("/system/bin/linker64", NativeLaunch.systemLinker(NdkAbi.X86_64))
        assertEquals("/system/bin/linker", NativeLaunch.systemLinker(NdkAbi.ARM32))
        assertEquals("/system/bin/linker", NativeLaunch.systemLinker(NdkAbi.X86))
    }
}
