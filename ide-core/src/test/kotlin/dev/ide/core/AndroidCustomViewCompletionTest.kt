package dev.ide.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end proof (through the real engine) that a PROJECT-SOURCE custom `View` subclass is offered as a
 * layout tag AND inherits its framework superclass's `android:` attributes — the `SourceCustomViewResolver`
 * (SubtypeIndex BFS) → `AndroidSdkMetadata.withCustomHierarchy` (framework-attr bridge) → completion path.
 * No Android SDK needed: framework attrs come from the bundled SDK metadata; the source class is indexed by
 * the resolution-free source subtype indexer (`extends Button` → android.widget.Button via the import).
 */
class AndroidCustomViewCompletionTest {

    private val root = createTempDirectory("custom-view")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private fun awaitIndexed(ide: IdeServices, timeoutMs: Long = 180_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @Test
    fun projectSourceCustomViewIsOfferedAndInheritsFrameworkAttributes() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        // A custom View subclass in the app's OWN source (uncompiled), extending a framework widget.
        val srcDir = root.resolve("app/src/main/java/com/example/app")
        Files.createDirectories(srcDir)
        Files.writeString(
            srcDir.resolve("MyButton.java"),
            """
            package com.example.app;
            import android.content.Context;
            import android.widget.Button;
            public class MyButton extends Button {
                public MyButton(Context c) { super(c); }
            }
            """.trimIndent(),
        )
        s.reindex()
        awaitIndexed(s)

        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        Files.createDirectories(layout.parent)

        // 1) The source custom view is offered as a tag (matched by its simple name, inserted fully-qualified).
        val tagText = "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">\n  <MyBu\n</LinearLayout>"
        val tagOffset = tagText.indexOf("<MyBu") + "<MyBu".length
        val tagLabels = runBlocking { s.complete(layout, tagText, tagOffset) }.items.map { it.insertText }
        assertTrue(tagLabels.any { it == "com.example.app.MyButton" }, "source custom view tag expected; got $tagLabels")

        // 2) On the custom view, android: attributes inherited from Button/TextView/View are offered.
        val attrText = "<com.example.app.MyButton android:\n"
        val attrOffset = attrText.indexOf("android:") + "android:".length
        val attrLabels = runBlocking { s.complete(layout, attrText, attrOffset) }.items.map { it.label }
        assertTrue("android:text" in attrLabels, "inherited android:text expected on the source custom view; got $attrLabels")
    }
}
