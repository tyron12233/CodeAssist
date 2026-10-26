package dev.ide.android.support.permissions

import dev.ide.lang.resolve.PermissionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPermissionTableTest {

    private val table = assertNotNull(AndroidPermissionTable.bundled(), "android-permissions.txt is bundled")

    @Test
    fun theBundledTableKnowsTheCommonPlatformApis() {
        assertTrue(table.size > 500, "table size ${table.size}")
        assertEquals(
            PermissionRequirement(
                listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"), allRequired = false,
            ),
            table.requirement("android.location.LocationManager", "getLastKnownLocation", 1),
        )
        assertEquals(listOf("android.permission.CAMERA"), table.requirement("android.hardware.camera2.CameraManager", "openCamera", 3)?.permissions)
        assertEquals(listOf("android.permission.RECORD_AUDIO"), table.requirement("android.media.AudioRecord", "<init>", 5)?.permissions)
    }

    @Test
    fun anUnannotatedApiOrArityHasNoRequirement() {
        assertNull(table.requirement("android.location.LocationManager", "getLastKnownLocation", 2))
        assertNull(table.requirement("android.view.View", "setVisibility", 1))
    }

    @Test
    fun readAndWriteRoundTrip() {
        val entries = listOf(
            AndroidPermissionTable.Entry("a.B", "m", 1, PermissionRequirement(listOf("p.X", "p.Y"), allRequired = false, maxApi = 22)),
            AndroidPermissionTable.Entry("a.B.Inner", "<init>", 0, PermissionRequirement(listOf("p.Z"), allRequired = true)),
        )
        val read = AndroidPermissionTable.read(AndroidPermissionTable.write(36, entries))
        assertEquals(entries[0].requirement, read.requirement("a.B", "m", 1))
        // A binary nested-class name finds the dotted entry.
        assertEquals(entries[1].requirement, read.requirement("a.B\$Inner", "<init>", 0))
    }
}
