package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.LibraryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LibrarySdkTableTest {

    @Test
    fun libraryTableInternsByName() = withWorkspace { _, store ->
        store.workspace.libraryTable.create("g:a:1").apply { kind = LibraryKind.JAR; commit() }
        assertEquals(LibraryKind.JAR, store.workspace.libraryTable.byName("g:a:1")?.kind)

        // re-creating the same name replaces rather than duplicates (interned by name)
        store.workspace.libraryTable.create("g:a:1").apply { kind = LibraryKind.AAR; commit() }
        assertEquals(1, store.workspace.libraryTable.libraries.count { it.name == "g:a:1" })
        assertEquals(LibraryKind.AAR, store.workspace.libraryTable.byName("g:a:1")?.kind)

        assertNull(store.workspace.libraryTable.byName("absent"))
    }

    @Test
    fun projectAndWorkspaceLibraryTablesAreSeparateScopes() = withWorkspace { _, store ->
        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        store.workspace.projects.single().libraryTable.create("proj:lib:1").apply { commit() }

        assertNotNull(store.workspace.projects.single().libraryTable.byName("proj:lib:1"))
        assertNull(store.workspace.libraryTable.byName("proj:lib:1")) // not visible at workspace scope
    }

    @Test
    fun sdkTableLooksUpByName() = withWorkspace { _, store ->
        store.replaceSdks(
            listOf(
                SdkData("jdk-17", listOf("/x/jrt.jar"), buildToolsPath = null),
                SdkData("android-34", listOf("/sdk/android.jar"), buildToolsPath = "/sdk/build-tools/34"),
            ),
        )
        assertEquals(2, store.workspace.sdkTable.sdks.size)
        assertEquals("jdk-17", store.workspace.sdkTable.byName("jdk-17")?.name)
        assertNull(store.workspace.sdkTable.byName("nope"))
    }
}
