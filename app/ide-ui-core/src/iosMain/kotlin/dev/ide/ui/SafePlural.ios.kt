package dev.ide.ui

/** iOS takes its locale from the system preferences, which the process cannot clear; nothing to repair. */
internal actual fun repairEmptyDefaultLocale() {}
