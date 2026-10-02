package dev.ide.vm.jdk

internal actual fun groupRange(match: MatchResult, group: Int): IntRange? = match.groups[group]?.range
