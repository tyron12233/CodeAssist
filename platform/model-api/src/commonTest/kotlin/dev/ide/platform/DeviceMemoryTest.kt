package dev.ide.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The user's "Low memory mode" override against the tier the host detected. */
class DeviceMemoryTest {

    @AfterTest
    fun reset() {
        DeviceMemory.detected = MemoryTier.NORMAL
        DeviceMemory.applyMode(null)
    }

    @Test
    fun autoFollowsTheDetectedTier() {
        DeviceMemory.detected = MemoryTier.LOW
        DeviceMemory.applyMode(DeviceMemory.MODE_AUTO)
        assertEquals(MemoryTier.LOW, DeviceMemory.tier)
        DeviceMemory.detected = MemoryTier.NORMAL
        assertEquals(MemoryTier.NORMAL, DeviceMemory.tier)
    }

    @Test
    fun onAndOffOverrideDetection() {
        DeviceMemory.detected = MemoryTier.NORMAL
        DeviceMemory.applyMode(DeviceMemory.MODE_ON)
        assertEquals(MemoryTier.LOW, DeviceMemory.tier)

        DeviceMemory.detected = MemoryTier.LOW
        DeviceMemory.applyMode(DeviceMemory.MODE_OFF)
        assertEquals(MemoryTier.NORMAL, DeviceMemory.tier)
    }

    @Test
    fun anUnknownValueMeansAuto() {
        DeviceMemory.detected = MemoryTier.LOW
        DeviceMemory.applyMode("sometimes")
        assertEquals(MemoryTier.LOW, DeviceMemory.tier)
        assertEquals(4, DeviceMemory.pick(normal = 8, low = 4))
    }
}
