package com.zipcli.ke.domain

class CommandParseException(message: String) : Exception(message)

data class ParsedCommand(
    val name: String,
    val args: List<String>,
    val boolFlags: Set<String>,
    val valueFlags: Map<String, String>,
    val raw: String,
) {
    fun hasFlag(name: String): Boolean = name in boolFlags
    fun flagValue(name: String): String? = valueFlags[name]
    fun arg(i: Int): String? = args.getOrNull(i)
}