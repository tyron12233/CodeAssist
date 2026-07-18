package dev.ide.android.support.index

import dev.ide.android.support.resources.ResourceType
import dev.ide.android.support.resources.StdlibResourceModel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `<string>` with an UNESCAPED apostrophe (`'#'`) is valid XML (only invalid for aapt2), as is one with a
 * backslash escape (`\'#\'`) — so both the resource index scanner and the repository parser must extract every
 * string, including ones declared AFTER it. Guards against a parse-fragility regression ("broken xml strings").
 */
class ApostropheStringResourceTest {

    private val unescaped = """<resources>
    <string name="rgb_instruction">
        Enter two hexadecimal characters (0–9 or A–F) for each RGB channel, with or without the '#' prefix.
    </string>
    <string name="added_after">hello</string>
</resources>"""

    private val escaped = """<resources>
    <string name="rgb_instruction">
        Enter two hexadecimal characters (0–9 or A–F) for each RGB channel, with or without the \'#\' prefix.
    </string>
    <string name="added_after">hello</string>
    <string name="added_third">world</string>
</resources>"""

    @Test
    fun indexScannerExtractsAllStrings() {
        for ((label, text) in listOf("unescaped" to unescaped, "escaped" to escaped)) {
            val names = ResourceFileScanner.scan("values", "/p/res/values/strings.xml", text)
                .filter { it.type == "string" }.map { it.name }.toSet()
            assertTrue("rgb_instruction" in names && "added_after" in names, "$label: index dropped strings: $names")
        }
    }

    @Test
    fun repositoryParserExtractsAllStrings() {
        for ((label, text) in listOf("unescaped" to unescaped, "escaped" to escaped)) {
            val dir = Files.createTempDirectory("res")
            Files.writeString(Files.createDirectories(dir.resolve("values")).resolve("strings.xml"), text)
            val names = StdlibResourceModel.parse(listOf(dir)).names(ResourceType.STRING).toSet()
            assertTrue("rgb_instruction" in names && "added_after" in names, "$label: repository dropped strings: $names")
        }
    }
}
