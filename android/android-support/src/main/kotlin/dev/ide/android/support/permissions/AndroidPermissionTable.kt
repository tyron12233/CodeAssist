package dev.ide.android.support.permissions

import dev.ide.lang.resolve.PermissionRequirement
import dev.ide.lang.resolve.PlatformPermissions

/**
 * The bundled `@RequiresPermission` table (`android-permissions.txt`), generated from a platform's
 * `data/annotations.zip` by `:android-sdk-metadata:generatePermissions`.
 *
 * One line per method: `owner \t name \t arity \t all|any \t perm,perm \t minApi \t maxApi`, the owner dotted
 * and a constructor named `<init>`. Overloads of one arity share a line (the first wins), the way the docs pack
 * keys them; a lookup that knows only the arity cannot tell them apart anyway.
 */
class AndroidPermissionTable private constructor(
    private val byKey: Map<String, PermissionRequirement>,
) : PlatformPermissions {

    val size: Int get() = byKey.size

    override fun requirement(ownerClass: String, name: String, arity: Int): PermissionRequirement? =
        byKey[key(ownerClass.replace('$', '.'), name, arity)]

    /** Method names, and constructors' class simple names. */
    private val names: Set<String> = byKey.keys.mapTo(HashSet()) { k ->
        val (owner, name) = k.split('#')
        if (name == "<init>") owner.substringAfterLast('.') else name
    }

    override fun mayRequire(name: String): Boolean = name in names

    /** One table row, for [write]. */
    class Entry(val owner: String, val name: String, val arity: Int, val requirement: PermissionRequirement)

    companion object {
        private const val RESOURCE = "/android-permissions.txt"

        private val bundled: AndroidPermissionTable? by lazy {
            AndroidPermissionTable::class.java.getResourceAsStream(RESOURCE)
                ?.use { read(it.readBytes().decodeToString()) }
        }

        /** The table shipped with the Android support, or null when the resource is missing. */
        fun bundled(): AndroidPermissionTable? = bundled

        fun read(text: String): AndroidPermissionTable {
            val map = HashMap<String, PermissionRequirement>()
            for (line in text.lineSequence()) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val f = line.split('\t')
                if (f.size < 7) continue
                val req = PermissionRequirement(
                    permissions = f[4].split(',').filter { it.isNotEmpty() },
                    allRequired = f[3] == "all",
                    minApi = f[5].toIntOrNull() ?: 0,
                    maxApi = f[6].toIntOrNull() ?: 0,
                )
                if (req.permissions.isEmpty()) continue
                map.putIfAbsent(key(f[0], f[1], f[2].toIntOrNull() ?: continue), req)
            }
            return AndroidPermissionTable(map)
        }

        fun write(platformLevel: Int, entries: List<Entry>): String = buildString {
            append("# @RequiresPermission facts of android-$platformLevel's annotations.zip\n")
            for (e in entries.sortedWith(compareBy({ it.owner }, { it.name }, { it.arity }))) {
                val r = e.requirement
                append(e.owner).append('\t').append(e.name).append('\t').append(e.arity).append('\t')
                append(if (r.allRequired) "all" else "any").append('\t').append(r.permissions.joinToString(","))
                append('\t').append(r.minApi).append('\t').append(r.maxApi).append('\n')
            }
        }

        private fun key(owner: String, name: String, arity: Int) = "$owner#$name#$arity"
    }
}
