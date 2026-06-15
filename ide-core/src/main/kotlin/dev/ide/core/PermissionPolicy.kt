package dev.ide.core

import dev.ide.build.engine.GuardCategory
import dev.ide.ui.backend.UiPermissionDecision
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the run sandbox's permission decisions: "always allow" categories persisted per project (in
 * [permissionsFile]), plus per-run allow/deny that resets each run. Pure and thread-safe; the blocking UI
 * prompt lives in `IdeServices`.
 */
internal class PermissionPolicy(private val permissionsFile: Path) {
    private val alwaysAllowed = ConcurrentHashMap.newKeySet<GuardCategory>().apply { addAll(load()) }
    private val runAllowed = ConcurrentHashMap.newKeySet<GuardCategory>()
    private val runDenied = ConcurrentHashMap.newKeySet<GuardCategory>()

    /** `true` = allow, `false` = deny, `null` = undecided (a prompt is needed). */
    fun decided(category: GuardCategory): Boolean? = when {
        category in alwaysAllowed || category in runAllowed -> true
        category in runDenied -> false
        else -> null
    }

    /** Apply [decision] for [category]; returns whether to allow this call. */
    fun apply(category: GuardCategory, decision: UiPermissionDecision): Boolean = when (decision) {
        UiPermissionDecision.ALLOW_ALWAYS -> { alwaysAllowed.add(category); persist(); true }
        UiPermissionDecision.ALLOW_RUN -> { runAllowed.add(category); true }
        UiPermissionDecision.ALLOW_ONCE -> true
        UiPermissionDecision.DENY -> { runDenied.add(category); false }
    }

    /** Clear per-run decisions (call at each run start); persisted "always" choices survive. */
    fun resetRun() { runAllowed.clear(); runDenied.clear() }

    private fun load(): Set<GuardCategory> = buildSet {
        runCatching {
            if (Files.exists(permissionsFile)) {
                val p = Properties()
                Files.newInputStream(permissionsFile).use { p.load(it) }
                GuardCategory.values().forEach { if (p.getProperty(it.name) == "always") add(it) }
            }
        }
    }

    private fun persist() {
        runCatching {
            permissionsFile.parent?.let { Files.createDirectories(it) }
            val p = Properties()
            alwaysAllowed.forEach { p.setProperty(it.name, "always") }
            Files.newOutputStream(permissionsFile).use { p.store(it, "CodeAssist run permissions (always-allowed categories)") }
        }
    }
}
