package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.*
import dev.ide.ui.fakeAdController
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** An installed store project with a newer version: the listing, the edited-copy choice, and Home's Updates. */
class UpdateFlowSnapshot {
    private val out = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/showcase").apply { mkdirs() }
    private val item = UiStoreItem(id = "nimbus-weather-2d1c56", kind = UiStoreItemKind.Community, title = "Nimbus Weather",
        summary = "A weather app built with Jetpack Compose", category = "kotlin", author = "Tyron Scott", verified = true,
        rating = 4.6f, ratingCount = 12, installs = 340, version = "1.3.0", downloadBytes = 812_000)
    private val upd = UiStoreUpdate(item, "1.2.0", "1.3.0", "/projects/Nimbus-Weather")

    private inner class B : StubBackend() {
        override val store: StoreService = object : StoreService {
            override fun installProgress(): StateFlow<Map<String, UiInstallProgress>> =
                MutableStateFlow(mapOf(item.id to UiInstallProgress(item.id, UiInstallState.INSTALLED, 1f, rootPath = "/projects/Nimbus-Weather")))
            override suspend fun updateFor(itemId: String) = upd
            override suspend fun availableUpdates() = listOf(upd, upd.copy(item = item.copy(id = "prism-1", title = "Prism"), installedVersion = "2.0.1", availableVersion = "2.1.0"))
            override suspend fun localChanges(itemId: String) = UiLocalChanges(
                modified = listOf("app/src/main/kotlin/com/nimbus/MainActivity.kt", "app/src/main/res/values/strings.xml", "app/build.gradle.kts"),
                added = listOf("app/src/main/kotlin/com/nimbus/MyWidget.kt"),
                canMerge = true,
            )
            override suspend fun mergeUpdate(itemId: String) = UiStoreMergeResult(
                success = true, message = "Updated. 1 file(s) need you to choose between your change and the new one",
                conflicts = listOf("app/src/main/res/values/strings.xml"), rootPath = "/projects/Nimbus-Weather",
            )
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun shoot(name: String, tap: String?, content: @Composable () -> Unit) =
        shootTaps(name, listOfNotNull(if (tap == "Update and keep") "Update to" else null, tap), content)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun shootTaps(name: String, taps: List<String>, content: @Composable () -> Unit) {
        val backend = B()
        val scene = ImageComposeScene(822, 1780, Density(2f)) {
            CodeAssistTheme(dark = true) {
                CompositionLocalProvider(LocalAds provides fakeAdController(backend)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        var t = 0L
        fun settle() { repeat(30) { Thread.sleep(20); t += 40_000_000L; scene.render(t) } }
        try {
            scene.render(); settle()
            for (tap in taps) {
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                val node = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    .first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(tap) } == true }
                val at = node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, at); scene.sendPointerEvent(PointerEventType.Release, at); settle()
            }
            File(out, name).writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    @Test fun listing() = shoot("13-update-listing.png", null) { StoreItemScreen(backend = B(), item = item, onBack = {}, onCreateFromTemplate = {}, onOpenInstalled = {}) }
    @Test fun conflict() = shoot("14-update-conflict.png", "Update to") { StoreItemScreen(backend = B(), item = item, onBack = {}, onCreateFromTemplate = {}, onOpenInstalled = {}) }
    @Test fun merged() = shoot("16-merge-result.png", "Update and keep") { StoreItemScreen(backend = B(), item = item, onBack = {}, onCreateFromTemplate = {}, onOpenInstalled = {}) }
    @Test fun home() = shoot("15-home-updates.png", "Updates") {
        val b = B()
        ProjectsHomeScreen(projects = listOf(dev.ide.ui.backend.ProjectInfo("Nimbus Weather", "/projects/Nimbus-Weather", 1, isAndroid = true)),
            onOpen = {}, onNewProject = {}, updates = listOf(upd, upd.copy(item = item.copy(id = "prism-1", title = "Prism"), installedVersion = "2.0.1", availableVersion = "2.1.0")))
    }
}
