package dev.codeassist.dart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DartPreviewsTest {

    @TempDir
    lateinit var dir: Path

    private val source = """
        import 'package:flutter/material.dart';
        import 'package:flutter/widget_previews.dart';

        // @Preview(name: 'commented out')
        @Preview(name: 'Top level')
        Widget topLevel() => const Text('a');

        @Preview(size: Size(200, 100))
        @override
        WidgetBuilder builderPreview() => (context) => const Text('b');

        @Preview()
        Widget _private() => const Text('c');

        class Card extends StatelessWidget {
          @Preview(name: 'Default card')
          const Card({super.key});

          @Preview(name: 'Named')
          const Card.compact({super.key, this.label = 'x'});

          final String label;

          @Preview(group: 'Statics')
          static Widget statically() => const Card();

          @Preview()
          Widget instanceMethod() => const Text('no');

          @override
          Widget build(BuildContext context) => const Text("@Preview() in a string");
        }
    """.trimIndent()

    @Test
    fun previewsAreTheDeclarationsAPreviewCanBe() {
        val found = DartPreviews.scan(source)
        assertEquals(
            listOf("topLevel()", "builderPreview()", "Card()", "Card.compact()", "Card.statically()"),
            found.map { it.call },
        )
        assertEquals(listOf(false, true, false, false, false), found.map { it.builder })
        assertEquals("name: 'Top level'", found[0].arguments)
        assertEquals("size: Size(200, 100)", found[1].arguments)
        assertEquals(6, found[0].line)
    }

    @Test
    fun theGalleryImportsEachLibraryAndCallsEachPreview() {
        Files.createDirectories(dir.resolve("lib/widgets"))
        Files.write(dir.resolve("lib/widgets/card.dart"), source.toByteArray())
        Files.write(dir.resolve("lib/part.dart"), "part of 'main.dart';\n@Preview()\nWidget inAPart() => const Text('');\n".toByteArray())
        val found = DartPreviews.find(dir, "app")
        assertEquals(5, found.size, "a part file is not a library the gallery can import")
        assertEquals("package:app/widgets/card.dart", found.first().library)
        val gallery = DartPreviews.gallery(found)
        assertTrue("import 'package:app/widgets/card.dart' as p0;" in gallery, gallery)
        assertTrue("_Entry('topLevel', 'widgets/card.dart', 6, const Preview(name: 'Top level'), (context) => p0.topLevel())," in gallery, gallery)
        assertTrue("(context) => p0.builderPreview()(context))," in gallery, gallery)
        assertTrue("(context) => p0.Card.compact())," in gallery, gallery)
        assertTrue("void main() => runApp(const _Gallery());" in gallery)
    }
}
