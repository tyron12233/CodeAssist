package dev.ide.plugin

import dev.ide.platform.ExtensionRegistry

interface Plugin {

    fun register(extensions: ExtensionRegistry)
}