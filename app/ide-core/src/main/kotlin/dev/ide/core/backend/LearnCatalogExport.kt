package dev.ide.core.backend

import java.nio.file.Files
import java.nio.file.Paths

/**
 * `./gradlew :ide-core:exportLearnCatalog`: writes the bundled catalog as the JSON the server stores, plus
 * a `.sha256` beside it. The publish script uploads both. Before publishing, run the solution check
 * (`./gradlew :ide-core:test --tests '*LearnSolutionsTest' -Plearn.validateSolutions=true`) so a lesson
 * whose own solution fails can never reach users.
 */
internal object LearnCatalogExport {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = Paths.get(args.firstOrNull() ?: "build/learn/catalog.json").toAbsolutePath()
        val catalog = LearnContent.catalog
        val json = LearnCatalogCodec.encode(catalog)
        // Never export something this build could not read back.
        val back = LearnCatalogCodec.decode(json)
        check(back == catalog) { "catalog does not survive a round trip through the codec" }
        Files.createDirectories(out.parent)
        Files.write(out, json.toByteArray(Charsets.UTF_8))
        val hash = LearnCatalogStore.sha256(json)
        Files.write(out.resolveSibling(out.fileName.toString() + ".sha256"), hash.toByteArray(Charsets.UTF_8))
        val lessons = catalog.tracks.sumOf { it.lessons.size }
        println("Learn catalog ${catalog.version}: ${catalog.tracks.size} tracks, $lessons lessons, ${json.length / 1024} KB")
        println("  $out")
        println("  sha256 $hash")
    }
}
