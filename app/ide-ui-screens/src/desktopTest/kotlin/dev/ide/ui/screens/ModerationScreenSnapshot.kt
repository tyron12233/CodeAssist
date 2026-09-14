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
import dev.ide.ui.backend.UiListingEdit
import dev.ide.ui.backend.UiModerationQueue
import dev.ide.ui.backend.UiPendingSubmission
import dev.ide.ui.backend.UiReportedContent
import dev.ide.ui.backend.UiSubmissionFile
import dev.ide.ui.backend.UiSubmissionListing
import dev.ide.ui.backend.UiSubmissionSubmitter
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders [ModerationScreen]: the queue with a submission's chips, screenshots, proposed listing edit and
 * the two decision buttons, and the report queue beside it.
 *
 * Every fixture goes through a backend that answers `isModerator()` true, because that is what the shipping
 * app answers for the accounts that see this screen. A snapshot taken through a backend that says false
 * would render the refusal and prove nothing about the page anyone actually uses — which is the same class
 * of mistake as the Explore snapshots that omitted `onAccount` and so never once drew the account button.
 *
 * The refusal is rendered too, in its own case, because it is reachable: the route can be opened by name
 * from a notification target, so it is a state the screen has to have rather than a branch nothing hits.
 */
class ModerationScreenSnapshot {

    private val submission = UiPendingSubmission(
        versionId = "v-1",
        version = "1.2.0",
        status = "pending",
        sizeBytes = 2_411_724,
        sha256 = "b".repeat(64),
        fileCount = 84,
        files = listOf(
            UiSubmissionFile("settings.gradle.kts", 214),
            UiSubmissionFile("app/src/main/kotlin/Main.kt", 1_882),
            UiSubmissionFile("app/src/main/res/values/strings.xml", 640),
        ),
        changelog = "Offline caching, and a fix for the crash on rotate.",
        screenshotPaths = listOf("uid/nimbus/1.2.0-shots/shot-0.png", "uid/nimbus/1.2.0-shots/shot-1.png"),
        iconPath = "uid/nimbus/1.2.0-icon/icon.png",
        edits = listOf(
            UiListingEdit("summary", "A weather app", "A weather app with offline forecasts"),
            UiListingEdit("tags", "weather", "weather, compose, ktor"),
        ),
        submitter = UiSubmissionSubmitter(
            userId = "u-1",
            handle = "nordlys",
            displayName = "Nordlys Labs",
            verified = true,
        ),
        listing = UiSubmissionListing(
            slug = "nimbus-weather-2d1c56",
            title = "Nimbus Weather",
            summary = "A weather app",
            description = "Forecasts, with a widget.",
            category = "compose",
            language = "Kotlin",
            tags = listOf("weather"),
            status = "approved",
            installs = 30,
        ),
    )

    /** A banned publisher's upload still reaches the queue, and the card has to say so before anything else. */
    private val fromBannedPublisher = submission.copy(
        versionId = "v-2",
        version = "2.0.0",
        edits = emptyList(),
        screenshotPaths = emptyList(),
        submitter = UiSubmissionSubmitter(userId = "u-2", handle = "spam-co", displayName = "Spam Co", banned = true),
        listing = submission.listing.copy(slug = "spam-app-99aa11", title = "Free Robux Generator"),
    )

    private val decided = submission.copy(
        versionId = "v-0",
        version = "1.1.0",
        status = "rejected",
        reviewNote = "The archive has no source, only a prebuilt APK.",
    )

    private val reports = listOf(
        UiReportedContent(
            reportId = "r-1",
            reason = "spam",
            detail = "Same text on five projects.",
            itemSlug = "nimbus-weather-2d1c56",
            itemTitle = "Nimbus Weather",
            isItemReport = false,
            reviewAuthorId = "u-9",
            reviewStars = 5,
            reviewText = "Best app ever!!! check out my channel for free coins",
        ),
        UiReportedContent(
            reportId = "r-2",
            reason = "copyright",
            itemSlug = "spam-app-99aa11",
            itemTitle = "Free Robux Generator",
            isItemReport = true,
        ),
    )

    /** A backend that moderates, so the snapshots draw the page the accounts that reach it actually see. */
    private class ModeratorBackend(
        private val queue: UiModerationQueue,
        private val reports: List<UiReportedContent>,
        private val moderator: Boolean = true,
        png: ByteArray = solidPng(),
    ) : StubBackend() {
        private val image = png
        override val store: StoreService = object : StoreService {
            override fun moderationAvailable() = true
            override fun isModerator() = moderator
            override suspend fun reviewQueue() = queue
            override suspend fun openReports() = reports
            // Submitted screenshots are in the private bucket, so they resolve through this and not
            // through the anonymous media cache. Stubbing both halves is what makes them render at all.
            override suspend fun submissionImageFile(storagePath: String) = "/cache/$storagePath"
        }

        override suspend fun imageBytes(path: String): ByteArray = image
    }

    @Test
    fun renderQueueDark() = snapshot(
        "moderation-queue-dark.png",
        ModeratorBackend(
            UiModerationQueue(
                pending = listOf(submission, fromBannedPublisher),
                recent = listOf(decided),
                pendingCount = 2,
            ),
            reports,
        ),
        dark = true,
    )

    @Test
    fun renderQueueLight() = snapshot(
        "moderation-queue-light.png",
        ModeratorBackend(
            UiModerationQueue(
                pending = listOf(submission, fromBannedPublisher),
                recent = listOf(decided),
                pendingCount = 2,
            ),
            reports,
        ),
        dark = false,
    )

    @Test
    fun renderEmptyQueue() = snapshot(
        "moderation-empty.png",
        ModeratorBackend(UiModerationQueue(pendingCount = 0), emptyList()),
        dark = true,
    )

    /**
     * The state a non-moderator reaches by opening the route by name.
     *
     * It says one thing and shows nothing about the queue, because the person reading it is not entitled
     * to know what is in it.
     */
    @Test
    fun renderNotAModerator() = snapshot(
        "moderation-refused.png",
        ModeratorBackend(UiModerationQueue(), emptyList(), moderator = false),
        dark = true,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, backend: StubBackend, dark: Boolean, height: Int = 1800) {
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ModerationScreen(backend = backend, onBack = {})
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
        const val WIDTH = 824
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
