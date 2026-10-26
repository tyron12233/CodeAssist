package dev.ide.android.support.metadata.gen

import dev.ide.android.support.permissions.AndroidPermissionTable
import dev.ide.lang.resolve.PermissionRequirement
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Generates the bundled permission table (`android-permissions.txt`) from a platform's `data/annotations.zip`:
 * every method and constructor carrying `@RequiresPermission`, minus the `conditional` ones (needed only in some
 * cases the annotation cannot say, so lint does not report them either).
 *
 * The table is keyed by parameter COUNT, which is all a call site reliably knows, so `android.jar` is read for
 * the overloads: a count shared with an overload that needs nothing, or with one that needs something else,
 * cannot be answered by count and is left out rather than reported against the wrong overload.
 *
 * Usage:
 * ```
 * ./gradlew :android-sdk-metadata:generatePermissions --args \
 *   "$SDK/platforms/android-36/data/annotations.zip $SDK/platforms/android-36/android.jar \
 *    android/android-support/src/main/resources/android-permissions.txt 36"
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: <annotations.zip> <android.jar> <output.txt> <api>")
        return
    }
    val overloads = overloadCounts(Path.of(args[1]))
    val entries = ArrayList<AndroidPermissionTable.Entry>()
    var skipped = 0
    ZipFile(args[0]).use { zip ->
        for (entry in zip.entries()) {
            if (!entry.name.endsWith("annotations.xml")) continue
            val doc = zip.getInputStream(entry).use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
            val items = doc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val item = items.item(i) as Element
                val annotation = childElements(item, "annotation")
                    .firstOrNull { it.getAttribute("name") == "androidx.annotation.RequiresPermission" } ?: continue
                val target = methodOf(item.getAttribute("name")) ?: continue
                val requirement = requirementOf(annotation)
                if (requirement == null) { skipped++; continue }
                entries += AndroidPermissionTable.Entry(target.first, target.second, target.third, requirement)
            }
        }
    }
    // One row per (owner, name, count), kept only when every overload of that count needs the same thing.
    val groups = entries.groupBy { Triple(it.owner, it.name, it.arity) }
    val kept = groups.values.mapNotNull { group ->
        val (owner, name, arity) = Triple(group[0].owner, group[0].name, group[0].arity)
        val same = group.all { it.requirement == group[0].requirement }
        val all = (overloads["$owner#$name#$arity"] ?: 0) <= group.size
        group[0].takeIf { same && all }
    }
    val out = Path.of(args[2])
    out.parent?.let { Files.createDirectories(it) }
    Files.writeString(out, AndroidPermissionTable.write(args[3].toInt(), kept))
    println(
        "Wrote ${out.toAbsolutePath()}: ${kept.size} methods ($skipped conditional or unreadable, " +
            "${groups.size - kept.size} ambiguous by parameter count, left out)"
    )
}

/** How many public or protected overloads `android.jar` has per `owner#name#count`, owners dotted. */
private fun overloadCounts(androidJar: Path): Map<String, Int> {
    val counts = HashMap<String, Int>()
    ZipFile(androidJar.toFile()).use { zip ->
        for (entry in zip.entries()) {
            if (!entry.name.endsWith(".class")) continue
            val reader = ClassReader(zip.getInputStream(entry).use { it.readBytes() })
            val owner = reader.className.replace('/', '.').replace('$', '.')
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, n: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor? {
                    if (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) return null
                    if (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return null
                    counts.merge("$owner#$n#${Type.getArgumentTypes(desc).size}", 1, Int::plus)
                    return null
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
    }
    return counts
}

/**
 * `(owner, name, arity)` of an item naming a method (`Owner Ret name(A, B<C, D>)`) or a constructor
 * (`Owner Simple(A)`); null for a field, a class, or a parameter (`… name(A) 0`).
 */
private fun methodOf(itemName: String): Triple<String, String, Int>? {
    val open = itemName.indexOf('(')
    val close = itemName.lastIndexOf(')')
    if (open < 0 || close < open || itemName.substring(close + 1).isNotBlank()) return null
    val owner = itemName.substringBefore(' ')
    val head = itemName.substring(0, open).trim().split(' ')
    val name = head.last()
    val isConstructor = head.size == 2 && name == owner.substringAfterLast('.')
    return Triple(owner, if (isConstructor) "<init>" else name, arity(itemName.substring(open + 1, close)))
}

/** Parameters in a list whose generic arguments hold commas of their own. */
private fun arity(params: String): Int {
    if (params.isBlank()) return 0
    var depth = 0
    var count = 1
    for (c in params) when (c) {
        '<' -> depth++
        '>' -> depth--
        ',' -> if (depth == 0) count++
    }
    return count
}

private fun requirementOf(annotation: Element): PermissionRequirement? {
    val values = childElements(annotation, "val").associate { it.getAttribute("name") to it.getAttribute("val") }
    if (values["conditional"] == "true") return null
    val (raw, all) = values["value"]?.let { it to true }
        ?: values["allOf"]?.let { it to true }
        ?: values["anyOf"]?.let { it to false }
        ?: return null
    val written = Regex("\"([^\"]*)\"").findAll(raw).map { it.groupValues[1] }.toList()
    val permissions = written.mapNotNull(::permissionName)
    // Any one of a list would do, so dropping an alternative it cannot read would make the check stricter than
    // the platform: leave such an entry out. A dropped member of an all-of list only makes it more lenient.
    if (permissions.isEmpty() || (!all && permissions.size != written.size)) return null
    val apis = values["apis"]?.trim('"').orEmpty()
    return PermissionRequirement(
        permissions = permissions,
        allRequired = all,
        minApi = apis.substringBefore("..", "").toIntOrNull() ?: 0,
        maxApi = apis.substringAfter("..", "").toIntOrNull() ?: 0,
    )
}

/** A permission's full name: `android.permission.X` as written, a bare `X` qualified, prose (`carrier privileges`) null. */
private fun permissionName(written: String): String? = when {
    ' ' in written || written.isEmpty() -> null
    '.' in written -> written
    written.all { it.isUpperCase() || it.isDigit() || it == '_' } -> "android.permission.$written"
    else -> null
}

private fun childElements(parent: Element, tag: String): List<Element> {
    val out = ArrayList<Element>()
    val children = parent.childNodes
    for (i in 0 until children.length) (children.item(i) as? Element)?.takeIf { it.tagName == tag }?.let(out::add)
    return out
}
