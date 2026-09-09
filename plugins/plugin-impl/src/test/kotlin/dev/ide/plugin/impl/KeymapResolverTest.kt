package dev.ide.plugin.impl

import dev.ide.platform.PluginId
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.plugin.keymap.KEY_BINDING_EP
import dev.ide.plugin.keymap.KeyBinding
import dev.ide.plugin.keymap.KeyContext
import dev.ide.plugin.keymap.KeyModifier
import dev.ide.plugin.keymap.KeyPress
import dev.ide.plugin.keymap.KeyStroke
import dev.ide.plugin.keymap.Shortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PLUGIN = PluginId("keymap-test")

class KeymapResolverTest {

    /** A press as a keyboard reports it: [KeyModifier.Primary] stands for the platform's command key, which
     *  the test drives as Control (the resolver accepts either, exactly as the old conditions did). */
    private fun stroke(key: String, vararg mods: KeyModifier) = KeyPress(
        key = key,
        ctrl = KeyModifier.Primary in mods || KeyModifier.Ctrl in mods,
        shift = KeyModifier.Shift in mods,
        alt = KeyModifier.Alt in mods,
        meta = KeyModifier.Meta in mods,
    )

    private fun resolver(
        vararg bindings: KeyBinding,
        user: Map<String, String> = emptyMap(),
    ): KeymapResolver {
        val registry = ExtensionRegistryImpl()
        for (b in bindings) registry.register(KEY_BINDING_EP, b, PLUGIN)
        return KeymapResolver(registry) { user }
    }

    private fun binding(
        actionId: String,
        spec: String,
        context: KeyContext = KeyContext.Global,
        order: Int = 0,
    ) = KeyBinding(actionId, requireNotNull(Shortcut.parse(spec)) { spec }, context, order)

    // ---- the text form ----

    @Test
    fun aShortcutRoundTripsThroughItsTextForm() {
        for (spec in listOf("primary+alt+L", "primary+shift+K", "F2", "shift+F6", "alt+shift+Up", "Escape")) {
            val parsed = requireNotNull(Shortcut.parse(spec)) { spec }
            assertEquals(spec, parsed.toString(), "the canonical form is stable")
            assertEquals(parsed, Shortcut.parse(parsed.toString()))
        }
    }

    @Test
    fun modifierOrderIsCanonicalSoTwoSpellingsOfOneComboAreOneString() {
        assertEquals(
            Shortcut.parse("shift+alt+primary+K"),
            Shortcut.parse("primary+alt+shift+K"),
            "the same combination written two ways is the same shortcut",
        )
        assertEquals("primary+alt+shift+K", Shortcut.parse("shift+alt+primary+K").toString())
    }

    @Test
    fun theSpellingsAMacUserWritesAreAccepted() {
        assertEquals(setOf(KeyModifier.Meta), Shortcut.parse("cmd+S")!!.strokes.single().modifiers)
        assertEquals(setOf(KeyModifier.Alt), Shortcut.parse("option+S")!!.strokes.single().modifiers)
        assertEquals(setOf(KeyModifier.Primary), Shortcut.parse("mod+S")!!.strokes.single().modifiers)
    }

    @Test
    fun keyNamesAreNormalizedIncludingThePunctuationAUserTypes() {
        assertEquals("Slash", Shortcut.parse("primary+/")!!.strokes.single().key)
        assertEquals("Up", Shortcut.parse("alt+arrowup")!!.strokes.single().key)
        assertEquals("PageDown", Shortcut.parse("pgdn")!!.strokes.single().key)
        assertEquals("L", Shortcut.parse("primary+l")!!.strokes.single().key, "a letter is upper-cased")
        assertEquals("F7", Shortcut.parse("f7")!!.strokes.single().key)
    }

    @Test
    fun anUnparseableSpecIsNullRatherThanAThrow() {
        // A spec comes from a settings file or a plugin manifest; the right answer to a typo is to drop that
        // one binding, not to fail whatever was reading it.
        assertNull(Shortcut.parse(""))
        assertNull(Shortcut.parse("   "))
        assertNull(Shortcut.parse("hyper+L"), "an unknown modifier is not a key name")
        assertNull(Shortcut.parse("primary+"), "a dangling separator must not read the modifier as the key")
        assertNull(Shortcut.parse("shift"), "a bare modifier name is not a key that can be pressed")
        assertNull(Shortcut.parse("primary++L"))
    }

    @Test
    fun aKeyThisBuildDoesNotKnowParsesAndSimplyNeverMatches() {
        val shortcut = requireNotNull(Shortcut.parse("primary+MediaPlay"))
        assertEquals("MediaPlay", shortcut.strokes.single().key)
        val resolver = resolver(KeyBinding("x", shortcut))
        assertIs<KeymapResolver.Result.None>(
            resolver.resolve(stroke("L", KeyModifier.Primary), KeyContext.Editor),
        )
    }

    // ---- resolution ----

    @Test
    fun aBoundPressResolvesToItsAction() {
        val resolver = resolver(binding("editor.reformat", "primary+alt+L", KeyContext.Editor))

        val result = resolver.resolve(stroke("L", KeyModifier.Primary, KeyModifier.Alt), KeyContext.Editor)
        assertEquals("editor.reformat", assertIs<KeymapResolver.Result.Command>(result).actionId)
    }

    @Test
    fun anUnboundPressIsNotACommand() {
        val resolver = resolver(binding("editor.reformat", "primary+alt+L"))
        assertIs<KeymapResolver.Result.None>(resolver.resolve(stroke("Q"), KeyContext.Editor))
    }

    @Test
    fun aGlobalBindingAlsoFiresInTheEditorButNotTheOtherWayAround() {
        val resolver = resolver(
            binding("app.palette", "primary+P", KeyContext.Global),
            binding("editor.duplicate", "primary+D", KeyContext.Editor),
        )

        assertIs<KeymapResolver.Result.Command>(
            resolver.resolve(stroke("P", KeyModifier.Primary), KeyContext.Editor),
        )
        assertIs<KeymapResolver.Result.Command>(
            resolver.resolve(stroke("D", KeyModifier.Primary), KeyContext.Editor),
        )
        assertIs<KeymapResolver.Result.None>(
            resolver.resolve(stroke("D", KeyModifier.Primary), KeyContext.Global),
        )
    }

    @Test
    fun primaryIsSatisfiedByEitherCommandModifier() {
        val resolver = resolver(binding("editor.save", "primary+S"))

        // What the old `isCtrlPressed || isMetaPressed` conditions meant, preserved: a Mac user pressing
        // Command and everyone else pressing Control both get Save.
        assertIs<KeymapResolver.Result.Command>(
            resolver.resolve(KeyPress("S", ctrl = true), KeyContext.Editor),
        )
        assertIs<KeymapResolver.Result.Command>(
            resolver.resolve(KeyPress("S", meta = true), KeyContext.Editor),
        )
        assertIs<KeymapResolver.Result.None>(
            resolver.resolve(KeyPress("S"), KeyContext.Editor),
            "the modifier is still required",
        )
    }

    @Test
    fun shiftAndAltMustMatchExactlyInBothDirections() {
        val resolver = resolver(
            binding("editor.duplicate", "primary+D"),
            binding("editor.moveUp", "alt+shift+Up"),
        )

        // Stricter than the code this replaces, where most conditions never checked for an absent modifier:
        // primary+shift+D is a different shortcut from primary+D and must not fire it.
        assertIs<KeymapResolver.Result.None>(
            resolver.resolve(KeyPress("D", ctrl = true, shift = true), KeyContext.Editor),
        )
        assertIs<KeymapResolver.Result.None>(
            resolver.resolve(KeyPress("D", ctrl = true, alt = true), KeyContext.Editor),
        )
        assertIs<KeymapResolver.Result.Command>(
            resolver.resolve(KeyPress("Up", alt = true, shift = true), KeyContext.Editor),
        )
        assertIs<KeymapResolver.Result.None>(
            resolver.resolve(KeyPress("Up", alt = true), KeyContext.Editor),
            "the binding asked for Shift too",
        )
    }

    @Test
    fun anExplicitCtrlBindingIsNotSatisfiedByCommand() {
        val resolver = resolver(binding("terminal.interrupt", "ctrl+C"))

        assertIs<KeymapResolver.Result.Command>(
            resolver.resolve(KeyPress("C", ctrl = true), KeyContext.Editor),
        )
        assertIs<KeymapResolver.Result.None>(
            resolver.resolve(KeyPress("C", meta = true), KeyContext.Editor),
            "ctrl means the physical key, which is the reason to write it instead of primary",
        )
    }

    // ---- chords ----

    @Test
    fun aChordCompletesOnItsSecondStroke() {
        val resolver = resolver(binding("editor.deleteLine", "primary+K primary+D", KeyContext.Editor))

        val first = resolver.resolve(stroke("K", KeyModifier.Primary), KeyContext.Editor)
        assertEquals(1, assertIs<KeymapResolver.Result.Pending>(first).prefix.size)
        assertEquals(listOf(stroke("K", KeyModifier.Primary)), resolver.pendingStrokes)

        val second = resolver.resolve(stroke("D", KeyModifier.Primary), KeyContext.Editor)
        assertEquals("editor.deleteLine", assertIs<KeymapResolver.Result.Command>(second).actionId)
        assertTrue(resolver.pendingStrokes.isEmpty(), "a completed chord clears the pending state")
    }

    @Test
    fun aSinglePressBindingIsNotSwallowedByAChordThatStartsWithTheSameKey() {
        val resolver = resolver(
            binding("editor.comment", "primary+K", KeyContext.Editor),
            binding("editor.deleteLine", "primary+K primary+D", KeyContext.Editor),
        )

        // The exact match is checked before the prefix, so the one-stroke binding still wins its own key.
        val result = resolver.resolve(stroke("K", KeyModifier.Primary), KeyContext.Editor)
        assertEquals("editor.comment", assertIs<KeymapResolver.Result.Command>(result).actionId)
    }

    @Test
    fun aMistypedChordCostsOneKeyRatherThanWedgingTheKeyboard() {
        val resolver = resolver(
            binding("editor.deleteLine", "primary+K primary+D", KeyContext.Editor),
            binding("editor.save", "primary+S", KeyContext.Editor),
        )

        assertIs<KeymapResolver.Result.Pending>(resolver.resolve(stroke("K", KeyModifier.Primary), KeyContext.Editor))
        // The user reaches for Save instead of finishing the chord: it fires, rather than being eaten.
        val result = resolver.resolve(stroke("S", KeyModifier.Primary), KeyContext.Editor)
        assertEquals("editor.save", assertIs<KeymapResolver.Result.Command>(result).actionId)
        assertTrue(resolver.pendingStrokes.isEmpty())
    }

    @Test
    fun aChordInProgressCanBeAbandoned() {
        val resolver = resolver(binding("editor.deleteLine", "primary+K primary+D", KeyContext.Editor))
        resolver.resolve(stroke("K", KeyModifier.Primary), KeyContext.Editor)
        assertTrue(resolver.pendingStrokes.isNotEmpty())

        resolver.clearPending() // focus moved, or a dialog opened
        assertTrue(resolver.pendingStrokes.isEmpty())
        assertIs<KeymapResolver.Result.None>(resolver.resolve(stroke("D", KeyModifier.Primary), KeyContext.Editor))
    }

    // ---- the user's authority ----

    @Test
    fun aUserBindingBeatsEveryContribution() {
        val resolver = resolver(
            binding("editor.reformat", "primary+alt+L", order = 0),
            binding("plugin.shouty", "primary+alt+L", order = 100),
            user = mapOf("editor.reformat" to "primary+shift+F"),
        )

        val rebound = resolver.resolve(stroke("F", KeyModifier.Primary, KeyModifier.Shift), KeyContext.Editor)
        assertEquals("editor.reformat", assertIs<KeymapResolver.Result.Command>(rebound).actionId)
        assertEquals("primary+shift+F", resolver.shortcutFor("editor.reformat").toString())

        // The old key is left to whoever else claims it, rather than still firing the rebound action.
        val old = resolver.resolve(stroke("L", KeyModifier.Primary, KeyModifier.Alt), KeyContext.Editor)
        assertEquals("plugin.shouty", assertIs<KeymapResolver.Result.Command>(old).actionId)
    }

    @Test
    fun aUserCanRemoveAShortcutAndTheDefaultDoesNotComeBack() {
        val resolver = resolver(
            binding("editor.reformat", "primary+alt+L"),
            user = mapOf("editor.reformat" to ""),
        )

        assertNull(resolver.shortcutFor("editor.reformat"))
        assertIs<KeymapResolver.Result.None>(
            resolver.resolve(stroke("L", KeyModifier.Primary, KeyModifier.Alt), KeyContext.Editor),
        )
    }

    @Test
    fun anUnparseableUserBindingLeavesTheActionWithoutOneRatherThanBreakingTheKeymap() {
        val resolver = resolver(
            binding("editor.reformat", "primary+alt+L"),
            binding("editor.save", "primary+S"),
            user = mapOf("editor.reformat" to "hyper+nonsense"),
        )

        // The nonsense entry is dropped, which leaves the action unbound (the user did ask for it to change),
        // and every other binding keeps working.
        assertNull(resolver.shortcutFor("editor.reformat"))
        assertIs<KeymapResolver.Result.Command>(resolver.resolve(stroke("S", KeyModifier.Primary), KeyContext.Editor))
    }

    @Test
    fun theHighestOrderContributionWinsAContestedShortcut() {
        val resolver = resolver(
            binding("builtin.thing", "primary+alt+L", order = 0),
            binding("plugin.thing", "primary+alt+L", order = 10),
        )

        val result = resolver.resolve(stroke("L", KeyModifier.Primary, KeyModifier.Alt), KeyContext.Editor)
        assertEquals("plugin.thing", assertIs<KeymapResolver.Result.Command>(result).actionId)
    }

    // ---- conflicts ----

    @Test
    fun aContestedShortcutIsReportedWithTheWinnerFirst() {
        val resolver = resolver(
            binding("builtin.thing", "primary+alt+L", order = 0),
            binding("plugin.thing", "primary+alt+L", order = 10),
            binding("editor.save", "primary+S"),
        )

        val conflicts = resolver.conflicts()
        assertEquals(setOf("primary+alt+L"), conflicts.keys, "only the contested shortcut is reported")
        assertEquals(listOf("plugin.thing", "builtin.thing"), conflicts.getValue("primary+alt+L"))
    }

    @Test
    fun noConflictsIsAnEmptyReport() {
        val resolver = resolver(binding("a", "primary+A"), binding("b", "primary+B"))
        assertTrue(resolver.conflicts().isEmpty())
    }

    @Test
    fun anEmptyKeymapResolvesNothing() {
        val resolver = resolver()
        assertIs<KeymapResolver.Result.None>(resolver.resolve(stroke("A", KeyModifier.Primary), KeyContext.Editor))
        assertTrue(resolver.bindings().isEmpty())
        assertNull(resolver.shortcutFor("anything"))
    }
}
