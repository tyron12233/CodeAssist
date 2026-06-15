package dev.ide.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SaveActionTest {

    @Test
    fun servicesSaveWritesBufferToDisk() {
        val dir = Files.createTempDirectory("ide-save")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            val main = ide.sourceRoots(app).first().resolve("com/example/app/Main.java")
            val edited = Files.readString(main) + "\n// saved-marker\n"
            ide.save(main, edited)
            assertEquals(edited, Files.readString(main), "IdeServices.save must persist the buffer to disk")
        }
        dir.toFile().deleteRecursively()
    }
}
