package dev.ide.ui.editor

import androidx.compose.ui.input.key.Key
import dev.ide.ui.ext.EDITOR_KEY_DEFAULTS
import dev.ide.ui.ext.EditorKeyFallback
import dev.ide.ui.ext.keymapKeyName
import dev.ide.ui.ext.KeymapHost
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's keymap: that the shortcuts survived being moved off the `if` chain into data, that the key
 * names the keymap speaks map to the keys the toolkit reports, and that an installed keymap outranks the
 * built-in defaults.
 *
 * The parity checks are the ones that matter. A command with no shortcut and a shortcut with no command are
 * both silent failures: the user presses a key and nothing happens, or a command exists that nothing can
 * reach.
 */
class EditorKeymapTest {

    private var installed: dev.ide.ui.ext.Registration? = null

    @AfterTest
    fun cleanUp() {
        installed?.dispose()
        installed = null
    }

    // ---- the shortcuts the editor shipped with, still reachable ----

    @Test
    fun everyDefaultShortcutResolvesBackToItsCommand() {
        for (default in EDITOR_KEY_DEFAULTS) {
            val resolved = EditorKeyFallback.resolve(
                key = default.key,
                ctrl = default.primary,
                shift = default.shift,
                alt = default.alt,
                meta = false,
            )
            assertEquals(default.actionId, resolved, "${default.spec} must reach ${default.actionId}")
        }
    }

    @Test
    fun aPrimaryShortcutWorksWithEitherCommandModifier() {
        // What `isCtrlPressed || isMetaPressed` meant everywhere before this was data.
        assertEquals(
            "editor.save",
            EditorKeyFallback.resolve("S", ctrl = true, shift = false, alt = false, meta = false),
        )
        assertEquals(
            "editor.save",
            EditorKeyFallback.resolve("S", ctrl = false, shift = false, alt = false, meta = true),
        )
        assertNull(EditorKeyFallback.resolve("S", ctrl = false, shift = false, alt = false, meta = false))
    }

    @Test
    fun theModifierVariantsAreSeparateCommands() {
        // Each was a branch on a modifier inside one handler; each is now independently rebindable.
        assertEquals("editor.goToDeclaration", EditorKeyFallback.resolve("B", true, false, false, false))
        assertEquals("editor.goToImplementation", EditorKeyFallback.resolve("B", true, false, true, false))
        assertEquals("editor.goToTypeDeclaration", EditorKeyFallback.resolve("B", true, true, false, false))
        assertEquals("editor.nextDiagnostic", EditorKeyFallback.resolve("F8", false, false, false, false))
        assertEquals("editor.previousDiagnostic", EditorKeyFallback.resolve("F8", false, true, false, false))
        assertEquals("editor.toggleComment", EditorKeyFallback.resolve("Slash", true, false, false, false))
        assertEquals("editor.toggleBlockComment", EditorKeyFallback.resolve("Slash", true, true, false, false))
    }

    @Test
    fun anExtraModifierIsADifferentShortcut() {
        // Stricter than the conditions this replaces, most of which never checked for an absent modifier.
        assertEquals("editor.duplicateLine", EditorKeyFallback.resolve("D", true, false, false, false))
        assertNull(
            EditorKeyFallback.resolve("D", ctrl = true, shift = true, alt = false, meta = false),
            "primary+shift+D is not primary+D",
        )
        assertNull(EditorKeyFallback.resolve("D", ctrl = true, shift = false, alt = true, meta = false))
    }

    // ---- parity between the table and the dispatch ----

    @Test
    fun everyDeclaredCommandHasADefaultShortcut() {
        val bound = EDITOR_KEY_DEFAULTS.mapTo(HashSet()) { it.actionId }
        val declared = declaredCommandIds()

        assertEquals(
            emptySet(),
            declared - bound,
            "a command with no shortcut is a command the keyboard cannot reach",
        )
    }

    @Test
    fun everyDefaultShortcutBindsADeclaredCommand() {
        val bound = EDITOR_KEY_DEFAULTS.mapTo(HashSet()) { it.actionId }
        val declared = declaredCommandIds()

        assertEquals(
            emptySet(),
            bound - declared,
            "a shortcut bound to no command is a key press that does nothing",
        )
    }

    @Test
    fun noTwoDefaultsClaimTheSameKeys() {
        val bySpec = EDITOR_KEY_DEFAULTS.groupBy { it.spec }.filterValues { it.size > 1 }
        assertTrue(bySpec.isEmpty(), "contested defaults: ${bySpec.mapValues { it.value.map { d -> d.actionId } }}")
    }

    /** The ids [EditorCommands] declares, read off the object so the test cannot drift from it. */
    private fun declaredCommandIds(): Set<String> =
        EditorCommands::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { field ->
                field.isAccessible = true
                field.get(EditorCommands) as? String
            }
            .toSet()

    // ---- the key-name table ----

    @Test
    fun theKeysAShortcutCanNameMapToToolkitKeys() {
        assertEquals("L", keymapKeyName(Key.L))
        assertEquals("F8", keymapKeyName(Key.F8))
        assertEquals("Slash", keymapKeyName(Key.Slash))
        assertEquals("Up", keymapKeyName(Key.DirectionUp))
        assertEquals("Space", keymapKeyName(Key.Spacebar))
        assertEquals("Enter", keymapKeyName(Key.Enter))
        assertEquals("Period", keymapKeyName(Key.Period))
        assertEquals("0", keymapKeyName(Key.Zero))
    }

    @Test
    fun bothFormsOfAKeyWithANumpadTwinAnswerTheSameName() {
        // A shortcut written `primary+Equals` should work whichever key the user's keyboard sends.
        assertEquals(keymapKeyName(Key.Equals), keymapKeyName(Key.NumPadAdd))
        assertEquals(keymapKeyName(Key.Minus), keymapKeyName(Key.NumPadSubtract))
        assertEquals(keymapKeyName(Key.Enter), keymapKeyName(Key.NumPadEnter))
    }

    @Test
    fun everyKeyADefaultNamesIsAKeyTheToolkitCanReport() {
        val named = EDITOR_KEY_DEFAULTS.mapTo(HashSet()) { it.key }
        val known = KNOWN_KEYS.mapNotNull { keymapKeyName(it) }.toSet()
        assertEquals(
            emptySet(),
            named - known,
            "a default names a key the toolkit table cannot produce, so it could never fire",
        )
    }

    // ---- the recorder and the resolver must agree ----

    @Test
    fun aRecordedSpecResolvesBackToTheKeysThatProducedIt() {
        // The recorder's output shape, spelled out: primary for either command modifier, then alt, then
        // shift, then the key name. Every default is already in that shape, which is what makes a recorded
        // shortcut and a shipped one indistinguishable.
        for (default in EDITOR_KEY_DEFAULTS) {
            val recorded = buildString {
                if (default.primary) append("primary+")
                if (default.alt) append("alt+")
                if (default.shift) append("shift+")
                append(default.key)
            }
            assertEquals(default.spec, recorded, "the recorder's spelling must match ${default.actionId}'s")
        }
    }

    // ---- an installed keymap outranks the defaults ----

    @Test
    fun anInstalledKeymapWinsOverTheBuiltInDefaults() {
        installed = KeymapHost.install(
            resolver = { key, _, _, _, _, _ ->
                if (key == "S") KeymapHost.Outcome.Command("plugin.somethingElse") else KeymapHost.Outcome.None
            },
            labeler = { null },
        )

        val outcome = KeymapHost.resolve("S", ctrl = true, inEditor = true)
        assertEquals("plugin.somethingElse", assertIs<KeymapHost.Outcome.Command>(outcome).actionId)
        // And a key the installed keymap does not claim is NOT quietly answered from the defaults: the user's
        // keymap is the keymap, or rebinding would be advisory.
        assertIs<KeymapHost.Outcome.None>(KeymapHost.resolve("L", ctrl = true, alt = true, inEditor = true))
    }

    @Test
    fun theDefaultsAnswerWhenNoKeymapIsInstalled() {
        assertTrue(!KeymapHost.isInstalled)
        val outcome = KeymapHost.resolve("L", ctrl = true, alt = true, inEditor = true)
        assertEquals("editor.reformat", assertIs<KeymapHost.Outcome.Command>(outcome).actionId)
    }

    @Test
    fun aPressOutsideTheEditorIsNotAnEditorCommand() {
        assertIs<KeymapHost.Outcome.None>(
            KeymapHost.resolve("L", ctrl = true, alt = true, inEditor = false),
            "an editor default must not fire from the file tree",
        )
    }

    @Test
    fun aThrowingKeymapFallsBackRatherThanBreakingTheKeyboard() {
        installed = KeymapHost.install(resolver = { _, _, _, _, _, _ -> error("boom") }, labeler = { null })

        val outcome = KeymapHost.resolve("L", ctrl = true, alt = true, inEditor = true)
        assertEquals("editor.reformat", assertIs<KeymapHost.Outcome.Command>(outcome).actionId)
    }

    private companion object {
        /** The keys the defaults could possibly name, for the reachability check above. */
        val KNOWN_KEYS = listOf(
            Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
            Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
            Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
            Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
            Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
            Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
            Key.Enter, Key.Escape, Key.Tab, Key.Spacebar, Key.Backspace, Key.Delete, Key.Insert,
            Key.Slash, Key.Backslash, Key.Minus, Key.Equals, Key.Comma, Key.Period,
            Key.Semicolon, Key.Apostrophe, Key.Grave, Key.LeftBracket, Key.RightBracket,
        )
    }
}
