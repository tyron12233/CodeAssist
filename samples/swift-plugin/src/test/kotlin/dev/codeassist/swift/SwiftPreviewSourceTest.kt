package dev.codeassist.swift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SwiftPreviewSourceTest {

    @Test
    fun previewMacrosBecomeDeclarationsOnTheSameLines() {
        val source = """
            import SwiftUI

            struct ContentView: View {
                var body: some View { Text("Hi") }
            }

            #Preview {
                ContentView()
            }

            #Preview("Dark", traits: .sizeThatFitsLayout) {
                ContentView().preferredColorScheme(.dark)
            }
        """.trimIndent()
        val r = SwiftPreviewSource.rewrite(source, "f0")
        assertEquals(listOf("__preview_f0_0", "__preview_f0_1"), r.previews)
        assertEquals(source.lines().size, r.text.lines().size)
        assertEquals("let __preview_f0_0 = _Preview {", r.text.lines()[6])
        assertEquals("let __preview_f0_1 = _Preview(\"Dark\", traits: .sizeThatFitsLayout) {", r.text.lines()[10])
    }

    @Test
    fun strings_comments_and_rawStringsAreLeftAlone() {
        val source = """
            // #Preview in a comment
            /* nested /* #Preview */ still comment */
            let a = "#Preview in a string"
            let b = #"raw "#Preview" string"#
            let c = ""${'"'}
              #Preview in a multi-line string
            ""${'"'}
            let d = "interpolated \(f("#Preview"))"
        """.trimIndent()
        val r = SwiftPreviewSource.rewrite(source, "f0")
        assertEquals(source, r.text)
        assertTrue(r.previews.isEmpty())
    }

    @Test
    fun previewableAndMainAreDroppedWithoutMovingAnything() {
        val source = "@main\nstruct App {}\n#Preview {\n    @Previewable @State var on = true\n    Toggle(\"x\", isOn: \$on)\n}\n"
        val r = SwiftPreviewSource.rewrite(source, "f1")
        assertEquals("     ", r.text.lines()[0])
        assertEquals("                 @State var on = true", r.text.lines()[3])
        assertEquals(source.length + "let __preview_f1_0 = _Preview".length - "#Preview".length, r.text.length)
    }

    @Test
    fun previewProvidersAreFound() {
        val source = "struct ContentView_Previews: PreviewProvider {\n static var previews: some View { Text(\"a\") }\n}\n" +
            "// struct Fake: PreviewProvider {}\n"
        val r = SwiftPreviewSource.rewrite(source, "f0")
        assertEquals(listOf("ContentView_Previews"), r.providers)
        assertTrue(SwiftPreviewSource.hasPreviews(source))
        assertFalse(SwiftPreviewSource.hasPreviews("struct A: View {}"))
    }

    @Test
    fun aBuildRunsThePreviewedFilesPreviewsWithTheWholeTarget(@TempDir root: Path) {
        Files.write(root.resolve("Package.swift"), "// swift-tools-version: 6.0\n".toByteArray())
        val target = Files.createDirectories(root.resolve("Sources/App"))
        val view = target.resolve("ContentView.swift")
        Files.write(view, "on disk\n".toByteArray())
        Files.write(target.resolve("Model.swift"), "struct Model {}\n#Preview { Text(\"other file\") }\n".toByteArray())
        Files.write(target.resolve("main.swift"), "print(1)\n".toByteArray())
        val live = "import SwiftUI\nstruct ContentView: View { var body: some View { Text(\"x\") } }\n#Preview { ContentView() }\n"
        val build = SwiftPreviewSource.prepare(view, live, root.resolve(".build/codeassist/preview/App"))
        val names = build.files.map { it.fileName.toString() }
        assertEquals(listOf("0_ContentView.swift", "1_Model.swift", "main.swift"), names)
        assertEquals(live.replace("#Preview", "let __preview_f0_0 = _Preview"), String(Files.readAllBytes(build.files[0])))
        assertTrue(String(Files.readAllBytes(build.files.last())).contains("_runPreviews([__preview_f0_0])"))
        assertEquals(view, build.origins[build.files[0]])
        assertEquals(1, build.previewCount)
    }
}
