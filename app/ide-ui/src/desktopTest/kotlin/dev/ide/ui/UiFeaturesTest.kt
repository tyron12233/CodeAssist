package dev.ide.ui

import dev.ide.ui.backend.UiFeatures
import kotlin.test.Test
import kotlin.test.assertEquals

class UiFeaturesTest {
    @Test
    fun namesAreSnakeCased() {
        assertEquals("screen_icon_manager", UiFeatures.screen("IconManager"))
        assertEquals("screen_sdk_manager", UiFeatures.screen("SdkManager"))
        assertEquals("screen_editor", UiFeatures.screen("Editor"))
        assertEquals("nav_type_declaration", UiFeatures.navigation("TYPE_DECLARATION"))
        assertEquals("nav_usages", UiFeatures.navigation("USAGES"))
        assertEquals("tool_dev_codeassist_ndk_console", UiFeatures.toolWindow("dev.codeassist.ndk.console"))
        assertEquals("tool_files", UiFeatures.toolWindow("files"))
    }
}
