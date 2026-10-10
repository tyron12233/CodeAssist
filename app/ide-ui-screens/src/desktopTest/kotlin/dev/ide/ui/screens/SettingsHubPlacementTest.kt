package dev.ide.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsHubPlacementTest {

    private val appPages = listOf("appearance", "editor", "completion", "analysis", "buildRuntime", "privacy", "keymap")

    private fun ids(placement: List<Pair<HubGroup, List<HubTarget>>>) = placement.map { (group, targets) ->
        group to targets.map { t ->
            when (t) {
                is HubTarget.Page -> t.id
                is HubTarget.Tool -> t.tool.name
            }
        }
    }

    @Test
    fun pagesAndToolsAreSiblingsGroupedByTopic() {
        assertEquals(
            listOf(
                HubGroup.Editor to listOf("appearance", "Colors", "editor", "CodeStyle", "keymap", "Symbols"),
                HubGroup.Code to listOf("completion", "analysis"),
                HubGroup.Build to listOf("buildRuntime", "SdkManager", "Keystore"),
                HubGroup.Extensions to listOf("Plugins"),
                HubGroup.System to listOf("Storage", "privacy"),
            ),
            ids(hubPlacement(appPages)),
        )
    }

    @Test
    fun projectPagesJoinBuildOnlyWhenOffered() {
        val build = ids(hubPlacement(appPages + listOf("preview", "build"))).toMap().getValue(HubGroup.Build)
        assertEquals(listOf("build", "preview", "buildRuntime", "SdkManager", "Keystore"), build)
    }

    @Test
    fun pluginPagesLandInExtensionsAfterPluginsInBackendOrder() {
        val extensions = ids(hubPlacement(listOf("vcs", "appearance", "ai"))).toMap().getValue(HubGroup.Extensions)
        assertEquals(listOf("Plugins", "vcs", "ai"), extensions)
    }

    @Test
    fun aGroupWithNothingOfferedStillKeepsItsTools() {
        // No pages at all (a backend with no settings): the tool screens alone still make every group but Code.
        assertEquals(
            listOf(HubGroup.Editor, HubGroup.Build, HubGroup.Extensions, HubGroup.System),
            hubPlacement(emptyList()).map { it.first },
        )
    }
}
