package com.zipcli.ke.domain

class CommandRegistry(specs: List<CommandSpec>) {
    private val byName: Map<String, CommandSpec> =
        specs.flatMap { spec -> spec.names.map { it.lowercase() to spec } }.toMap()

    fun find(name: String): CommandSpec? = byName[name.lowercase()]

    fun all(): List<CommandSpec> =
        byName.values.distinctBy { it.name }.sortedBy { it.name }

    /** Every name and alias, for completion and "did you mean". */
    fun names(): Set<String> = byName.keys

    fun complete(prefix: String): List<String> =
        byName.keys.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
}
