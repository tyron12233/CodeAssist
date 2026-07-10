package dev.ide.plugin.action

/**
 * Where an action can appear. An open, string-backed set so a plugin (or a new UI surface) can introduce its
 * own place without a change here. The built-in places are in [ActionPlaces].
 */
@JvmInline
value class ActionPlace(val id: String)