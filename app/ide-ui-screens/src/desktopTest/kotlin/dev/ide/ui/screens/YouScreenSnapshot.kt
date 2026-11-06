package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiMyProfile
import dev.ide.ui.backend.UiPublishedItem
import dev.ide.ui.backend.UiPublisherProfile
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiSignInPhase
import dev.ide.ui.backend.UiStoreAccount
import dev.ide.ui.backend.UiStoreAuthState
import dev.ide.ui.backend.UiStoreSubmission
import dev.ide.ui.backend.UiSubmissionStatus
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders [YouScreen], signed in, at a phone viewport and on a tall canvas.
 *
 * The fixtures are shaped like a real publisher's account: three live listings, one of them with an older
 * live version behind the current one, a moderator entry with work waiting, and (in the busy case) a
 * submission in review and one sent back with a note, because those are the rows that carry actions.
 */
class YouScreenSnapshot {

    private val profile = UiMyProfile(
        handle = "tyronscott",
        displayName = "Tyron Scott",
        bio = "Building CodeAssist, an IDE that fits in your pocket.",
        location = "Manila",
        linkUrl = "github.com/tyron12233",
        verified = true,
        followers = 3,
        publishedCount = 3,
        pendingCount = 0,
        totalInstalls = 657,
        totalLikes = 41,
        isModerator = true,
        moderationQueue = 5,
    )

    private val published = listOf(
        UiPublishedItem("prism-1", "Prism", "approved", publishedVersion = "1.0.0", iconPath = "i/prism.png",
            summary = "A colour picker with palettes"),
        UiPublishedItem("slate-1", "Slate Tasks", "approved", publishedVersion = "1.0.0", iconPath = "i/slate.png",
            summary = "Tasks, lists and reminders"),
        UiPublishedItem("nimbus-1", "Nimbus Weather", "approved", publishedVersion = "1.0.1", iconPath = "i/nimbus.png",
            summary = "Forecasts with a widget"),
    )

    private val liveSubmissions = listOf(
        UiStoreSubmission("prism-1", "Prism", "1.0.0", UiSubmissionStatus.PUBLISHED),
        UiStoreSubmission("slate-1", "Slate Tasks", "1.0.0", UiSubmissionStatus.PUBLISHED),
        UiStoreSubmission("nimbus-1", "Nimbus Weather", "1.0.1", UiSubmissionStatus.PUBLISHED),
        UiStoreSubmission("nimbus-1", "Nimbus Weather", "1.0.0", UiSubmissionStatus.PUBLISHED),
    )

    private val busySubmissions = listOf(
        UiStoreSubmission("nimbus-1", "Nimbus Weather", "1.1.0", UiSubmissionStatus.SUBMITTED),
        UiStoreSubmission(
            "orbit-1", "Orbit Notes", "0.9.0", UiSubmissionStatus.CHANGES_REQUESTED,
            note = "The archive is missing the Gradle wrapper, so it cannot be built as uploaded.",
        ),
        UiStoreSubmission(
            "free-1", "Free Coins", "1.0.0", UiSubmissionStatus.REJECTED,
            note = "Listings that promise currency for other games are not accepted.",
        ),
    ) + liveSubmissions

    private class YouBackend(
        private val profile: UiMyProfile?,
        private val submissions: List<UiStoreSubmission>,
        private val published: List<UiPublishedItem>,
        png: ByteArray = solidPng(),
    ) : StubBackend() {
        private val image = png
        var publicProfile: UiPublisherProfile? = null
        override val store: StoreService = object : StoreService {
            private val auth = MutableStateFlow(
                UiStoreAuthState(UiSignInPhase.SignedIn, UiStoreAccount(userId = "u-1", handle = "tyronscott")),
            )
            override fun authState(): StateFlow<UiStoreAuthState> = auth
            override suspend fun myProfile() = profile
            override suspend fun mySubmissions() = submissions
            override suspend fun myPublishedItems() = published
            override suspend fun screenshotFile(storagePath: String): String = "/cache/$storagePath"
            override fun accountDeletionAvailable() = true
            override suspend fun publisherProfile(handle: String) = publicProfile
        }

        override suspend fun imageBytes(path: String): ByteArray = image
    }

    @Test
    fun renderLiveOnlyPhone() {
        snapshot("you-live-phone.png", YouBackend(profile, liveSubmissions, published), dark = true, height = 1780)
    }

    @Test
    fun renderLiveOnlyTall() {
        snapshot("you-live-tall.png", YouBackend(profile, liveSubmissions, published), dark = true, height = 3000)
    }

    @Test
    fun renderBusyTall() {
        snapshot(
            "you-busy-tall.png",
            YouBackend(profile.copy(pendingCount = 1), busySubmissions, published),
            dark = true,
            height = 3600,
        )
    }

    @Test
    fun renderBusyLight() {
        snapshot(
            "you-busy-light.png",
            YouBackend(profile.copy(pendingCount = 1, isModerator = false), busySubmissions, published),
            dark = false,
            height = 3600,
        )
    }

    @Test
    fun renderNewPublisher() {
        snapshot(
            "you-new-phone.png",
            YouBackend(
                profile.copy(verified = false, bio = null, location = null, linkUrl = null, followers = 0,
                    publishedCount = 0, totalInstalls = 0, isModerator = false),
                emptyList(),
                emptyList(),
            ),
            dark = true,
            height = 1780,
        )
    }

    private fun publicBackend(): YouBackend = YouBackend(profile, emptyList(), emptyList()).apply {
        publicProfile = UiPublisherProfile(
            handle = "nordlys",
            displayName = "Nordlys Labs",
            bio = "Small, careful Android apps. Weather, notes and a colour picker.",
            location = "Tromsø",
            linkUrl = "https://nordlys.dev",
            verified = true,
            followers = 128,
            projectCount = 4,
            totalInstalls = 4_210,
            totalLikes = 312,
            averageRating = 4.6f,
            joinedAt = "2026-09-02T08:00:00.000000+00:00",
            items = listOf(
                publicItem("libgdx", "CodeAssist libGDX", "Make 2D games with libGDX, right on your phone.", installs = 693,
                    likes = 4, rating = 4.7f, ratingCount = 12, published = "2026-09-05T00:00:00Z", icon = "i/prism.png"),
                publicItem("strings", "Hardcoded Compose Strings", "Finds Text(\"...\") literals and moves them to resources.",
                    installs = 142, likes = 3, rating = 5f, ratingCount = 4, published = "2026-10-28T00:00:00Z", plugin = true),
                publicItem("colorpicker", "ColorPicker Plugin", "A colour picker in the editor gutter.", installs = 120,
                    likes = 6, rating = 4.6f, ratingCount = 5, published = "2026-10-01T00:00:00Z", plugin = true),
                publicItem("nav3", "Nav3 Graph Visualizer", "Draws your Navigation 3 graph.", installs = 90,
                    likes = 0, rating = 4f, ratingCount = 2, published = "2026-10-10T00:00:00Z", plugin = true),
            ),
        )
    }

    private fun publicItem(
        id: String, title: String, summary: String, installs: Int, likes: Int, rating: Float, ratingCount: Int,
        published: String, plugin: Boolean = false, icon: String? = null,
    ) = UiStoreItem(
        id = id, kind = UiStoreItemKind.Community, title = title, summary = summary,
        category = if (plugin) "build" else "games", language = "Kotlin", author = "Nordlys Labs",
        iconPath = icon, rating = rating, ratingCount = ratingCount, installs = installs, likes = likes,
        publishedAt = published, downloadBytes = 2_400_000L, available = true,
    )

    @Test
    fun renderPublicProfileTall() {
        snapshot("public-profile-tall.png", publicBackend(), dark = true, height = 3000) { b ->
            PublisherProfileScreen(backend = b, handle = "nordlys", onBack = {}, onOpenItem = {}, onNeedSignIn = {})
        }
    }

    @Test
    fun renderPublicProfile() {
        snapshot("public-profile-phone.png", publicBackend(), dark = true, height = 1780) { b ->
            PublisherProfileScreen(backend = b, handle = "nordlys", onBack = {}, onOpenItem = {}, onNeedSignIn = {})
        }
    }

    @Test
    fun renderPublicProfileLight() {
        snapshot("public-profile-light.png", publicBackend(), dark = false, height = 1780) { b ->
            PublisherProfileScreen(backend = b, handle = "nordlys", onBack = {}, onOpenItem = {}, onNeedSignIn = {})
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(
        name: String,
        backend: StubBackend,
        dark: Boolean,
        height: Int,
        content: @androidx.compose.runtime.Composable (StubBackend) -> Unit = { b ->
            YouScreen(backend = b, onBack = {}, onPublish = {}, onUpdateListing = {}, onModerate = {})
        },
    ) {
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark, accent = CaAccent.Orange) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content(backend)
                }
            }
        }
        try {
            scene.render()
            for (frame in 1..20) scene.render(frame * 50_000_000L)
            val png = scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }
            out.writeBytes(png)
            assertTrue(png.size > 5_000, "$name rendered as ${png.size} bytes, which is a blank page")
            println("wrote snapshot: ${out.absolutePath} (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 822
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
