package dev.ide.core.backend

import dev.ide.vcs.VcsMessage
import dev.ide.vcs.VcsText
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Git engine writes its messages as [VcsMessage]s and the Git UI translates them by key from its string
 * resources. Nothing else ties the two together, so this does: every message has an English resource with the
 * same wording, and every translation has every message with the same placeholders.
 */
class VcsMessageResourcesTest {

    private val resources: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
        .resolve("services/vcs-ui/src/commonMain/composeResources")

    private fun strings(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(resources.resolve(folder).resolve("strings.xml").toFile())
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun placeholders(text: String): Set<String> = Regex("""%\d\${'$'}s""").findAll(text).map { it.value }.toSet()

    @Test
    fun `every message has an English resource worded exactly as the engine words it`() {
        val english = strings("values")
        for (message in VcsMessage.entries) {
            assertEquals(message.english, english[message.key], "values/strings.xml ${message.key}")
        }
        val stale = english.keys.filter { it.startsWith("vcs_msg_") } - VcsMessage.entries.map { it.key }.toSet()
        assertTrue(stale.isEmpty(), "resources for messages the engine no longer has: $stale")
    }

    @Test
    fun `every translation carries every message with the same placeholders`() {
        for (folder in listOf("values-ar", "values-zh")) {
            val translated = strings(folder)
            for (message in VcsMessage.entries) {
                val text = translated[message.key]
                assertTrue(!text.isNullOrBlank(), "$folder is missing ${message.key}")
                assertEquals(placeholders(message.english), placeholders(text), "$folder ${message.key}")
            }
        }
    }

    @Test
    fun `nested texts render into their English`() {
        val text = VcsText.of(VcsMessage.AUTH_FAILED, VcsText.of(VcsMessage.PUSH_FAILED, "origin"))
        assertEquals(
            "Could not push to origin: authentication failed. Sign in or check the saved credentials.",
            text.english,
        )
    }
}
