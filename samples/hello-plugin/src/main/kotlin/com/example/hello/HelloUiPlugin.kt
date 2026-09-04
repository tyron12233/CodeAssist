package com.example.hello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.ide.plugin.ui.EditorPreview
import dev.ide.plugin.ui.EditorPreviewContext
import dev.ide.plugin.ui.ToolWindow
import dev.ide.plugin.ui.ToolWindowAnchor
import dev.ide.plugin.ui.UiContext
import dev.ide.plugin.ui.UiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * The UI facet named by `uiEntryPoints` in `res/raw/codeassist_plugin.toml`: a tool window on the editor's
 * left rail, with a body that is ordinary Compose.
 *
 * The engine facet ([HelloPlugin]) and this one are separate classes because a `@Composable` body cannot live
 * in the engine module, not because they are separate programs. They load off this APK on the same
 * classloader, so [HelloState] below is one object to both of them: the panel shows what the palette command
 * did, and its own button does the same thing the command does.
 *
 * What a UI facet gets is deliberately small ([UiContext]: where the user is, and how to open a file or
 * another screen). Anything more (the project model, files, indexes, analysis) belongs in the engine facet,
 * which has the whole engine SPI and is a plain function call away.
 */
class HelloUiPlugin : UiPlugin {

    // The packaged manifest's id is authoritative; this must agree with it.
    override val id = "com.example.hello"

    override fun contribute(ui: UiRegistration) {
        ui.toolWindow(
            ToolWindow(
                id = "com.example.hello.panel",
                title = "Hello",
                // An id in the IDE's icon registry: a plugin has no Context for its own package, so it
                // cannot point at a drawable of its own.
                iconId = "sparkle",
                anchor = ToolWindowAnchor.LEFT,
            ) { ctx -> HelloPanel(ctx) },
        )

        // A preview pane for the files this plugin can show something for. The IDE's own four panes (Compose
        // previews, Android layouts, resources, Markdown) are tried first, so a claim here only decides what
        // happens for files none of them handle.
        ui.editorPreview(
            EditorPreview(
                id = "com.example.hello.preview",
                title = "Greeting",
                appliesTo = { path -> path.endsWith(".hello.kt") },
            ) { ctx -> GreetingPreview(ctx) },
        )
    }
}

@Composable
private fun HelloPanel(ctx: UiContext) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hello Plugin", style = MaterialTheme.typography.titleMedium)

        // Written by the engine facet's palette command and by the button below: one object, two facets.
        Text(
            when (val source = HelloState.lastSource) {
                null -> "Nothing said yet."
                else -> "Said hello ${HelloState.greetings} time(s), last from $source."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        // Reading the context is enough to follow the editor: the panel recomposes on a tab switch.
        Text(
            ctx.activeFilePath?.let { "Editing ${it.substringAfterLast('/')}" } ?: "No file open",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = { HelloState.greeted("the panel") }) { Text("Say hello") }
    }
}

/**
 * The preview pane for a `*.hello.kt` file: it runs the file's `greeting()` function through the IDE's
 * interpreter and shows what it returned, updating as the user types.
 *
 * This is the point of a plugin-contributed pane. The IDE has no idea what a `.hello.kt` file means, and this
 * is not a description of the code or a rendering of its syntax: it is the value the user's own function
 * actually produced, with no compile step in between.
 */
@Composable
private fun GreetingPreview(ctx: EditorPreviewContext) {
    // Off the composition thread, and keyed on the buffer so a keystroke re-runs it and nothing else does.
    // Interpretation is bounded but not free, and a preview that runs it inline janks the editor with it.
    val state by produceState<HelloInterpreter.Outcome>(
        HelloInterpreter.Outcome.Waiting("Running..."),
        ctx.path,
        ctx.text,
    ) {
        value = withContext(Dispatchers.Default) { HelloInterpreter.greeting(ctx.path, ctx.text) }
    }
    // Read once into a local: a delegated property cannot be smart-cast in the `when` below.
    val outcome = state

    // Problems belong in the host's chip rather than drawn over the content, and reporting on every pass is
    // what clears the previous one.
    ctx.reportProblems(
        when (outcome) {
            is HelloInterpreter.Outcome.Problem -> outcome.reasons
            else -> emptyList()
        }
    )

    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("greeting()", style = MaterialTheme.typography.labelMedium)
        Text(
            when (outcome) {
                is HelloInterpreter.Outcome.Value -> outcome.text
                is HelloInterpreter.Outcome.Waiting -> outcome.why
                is HelloInterpreter.Outcome.Problem -> "Could not run it."
                HelloInterpreter.Outcome.Unsupported -> "This IDE has no interpreter."
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            ctx.path.substringAfterLast('/'),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The panel in the editor's preview pane, with no IDE running and nothing installed. `UiContext.preview()`
 * stands in for the host, so the two states worth looking at are two previews rather than two installs.
 */
@Preview
@Composable
private fun HelloPanelPreview() {
    HelloPanel(UiContext.preview(activeFilePath = "MainActivity.kt"))
}

@Preview
@Composable
private fun HelloPanelNoFilePreview() {
    HelloPanel(UiContext.preview())
}
