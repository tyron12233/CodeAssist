package dev.ide.interp

import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import java.util.concurrent.ConcurrentHashMap

/** What the preview sandbox can restrict. [id] is the stable settings key suffix; [label] the human phrase
 *  findings/messages use. Mirrors the console run sandbox's guard categories, adapted to interpretation. */
enum class SandboxCategory(val id: String, val label: String) {
    FILE_IO("fileIo", "file access"),
    NETWORK("network", "network access"),
    ANDROID_SYSTEM("androidSystem", "Android system access"),
    PROCESS_CONTROL("processControl", "process/reflection access");

    companion object {
        fun fromId(id: String): SandboxCategory? = entries.firstOrNull { it.id == id }
    }
}

/** One blocked operation, deduped by [member] (the `owner.name` the code tried). */
data class SandboxFinding(val category: SandboxCategory, val member: String)

/**
 * The default [InterpreterHooks] policy behind the Compose preview sandbox: classifies each escape —
 * a library call, a reflective property read/write, a singleton class init — into a [SandboxCategory] by the
 * receiver's runtime class hierarchy and the callee's owner FQN, and blocks it when that category is
 * [restricted]. Like the console run sandbox (which mediates the program's calls at the bytecode VM's bridge)
 * this covers a CURATED set of common entry points, not every conceivable path — a call-boundary guard, not a
 * hardened sandbox.
 *
 * Two modes, matching the host's gap tolerance:
 *  - **stub** ([stubOnDeny] = true, the editor preview): a blocked call yields `null` (a write is skipped)
 *    and a [SandboxFinding] is recorded for the preview's problem chip — the rest still renders.
 *  - **strict** ([stubOnDeny] = false, lessons/authored snippets): a blocked call throws
 *    [InterpreterSecurityException], failing the render loudly with the reason.
 *
 * Classification is memoized per (owner class, member) — the same members are re-checked every
 * recomposition, and the Android-`Context` detection walks a class hierarchy. Thread-safe: the interpreter
 * runs on the composition thread and the suspend-bridge thread at once.
 */
class PreviewSandboxPolicy(
    private val restricted: Set<SandboxCategory>,
    private val stubOnDeny: Boolean = true,
) : InterpreterHooks {

    companion object {
        /** Build from stored settings ids (unknown ids ignored, so a stale pref can't crash the preview). */
        fun fromIds(ids: Collection<String>, stubOnDeny: Boolean = true) =
            PreviewSandboxPolicy(ids.mapNotNullTo(HashSet()) { SandboxCategory.fromId(it) }, stubOnDeny)

        /** Findings kept per policy — enough for a problem chip; a runaway loop can't grow it unbounded. */
        private const val MAX_FINDINGS = 32

        // -- FILE_IO ------------------------------------------------------------------------------------
        /** Owners whose EVERY member/constructor is filesystem I/O. `Files`/`FilesKt` statics included —
         *  `FilesKt__…` covers the multifile facade parts the resolver may record as the owner. */
        private val FILE_OWNERS = setOf(
            "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader", "java.io.FileWriter",
            "java.io.RandomAccessFile", "java.nio.file.Files",
        )
        private val FILE_OWNER_PREFIXES = listOf("kotlin.io.FilesKt")

        /** `java.io.File` members that are PURE path arithmetic (no disk touch) — everything else on a File
         *  receiver (exists/read/write/delete/list/mkdir…) is I/O. Normalized names (getter prefix dropped). */
        private val FILE_PURE_MEMBERS = setOf(
            "name", "path", "parent", "parentfile", "absolutepath", "absolutefile", "isabsolute",
            "tostring", "topath", "touri", "tourl", "compareto", "equals", "hashcode", "separator",
        )
        /** Pure path helpers on the `FilesKt` facade (extension properties/functions) that must stay usable
         *  even with file I/O restricted — `file.extension`, `resolve`, `relativeTo` do no disk work. */
        private val FILE_FACADE_PURE = setOf(
            "extension", "namewithoutextension", "invariantseparatorspath", "resolve", "resolvesibling",
            "normalize", "torelativestring", "relativeto", "relativetoornull", "relativetoorself",
            "startswith", "endswith",
        )

        // -- NETWORK ------------------------------------------------------------------------------------
        private val NET_OWNERS = setOf(
            "java.net.Socket", "java.net.ServerSocket", "java.net.DatagramSocket", "java.net.MulticastSocket",
            "java.net.URLConnection", "java.net.URLClassLoader",
            "java.nio.channels.SocketChannel", "java.nio.channels.ServerSocketChannel",
            "java.nio.channels.DatagramChannel",
        )
        private val NET_OWNER_PREFIXES = listOf("java.net.http.", "javax.net.", "okhttp3.", "io.ktor.client.", "retrofit2.")

        /** `URL`/`URI` construction and accessors are pure parsing; only the members that actually open a
         *  connection (plus the `kotlin.io` read extensions on a URL receiver) are network. */
        private val URL_NET_MEMBERS = setOf("openconnection", "openstream", "content", "readtext", "readbytes")
        private val INET_NET_MEMBERS = setOf("byname", "allbyname", "localhost")

        // -- ANDROID_SYSTEM -----------------------------------------------------------------------------
        /** Context members that reach out of the preview (launch/bind/broadcast/system services/storage).
         *  The preview legitimately needs a Context for resources/density/theme, so this is member-level —
         *  `context.resources` stays usable while `context.startActivity(...)` is blocked. */
        private val CONTEXT_DENIED_MEMBERS = setOf(
            "startactivity", "startactivities", "startservice", "startforegroundservice", "stopservice",
            "bindservice", "unbindservice", "sendbroadcast", "sendorderedbroadcast", "sendstickybroadcast",
            "registerreceiver", "unregisterreceiver", "systemservice", "contentresolver", "sharedpreferences",
            "openfileinput", "openfileoutput", "deletefile", "openorcreatedatabase", "deletedatabase",
            "databaselist", "filelist", "setwallpaper", "startinstrumentation",
        )
        /** Owners any use of which is a system side effect (the getSystemService products are belt-and-braces:
         *  normally unreachable once `getSystemService` itself is blocked). */
        private val ANDROID_OWNER_PREFIXES = listOf(
            "android.content.ContentResolver", "android.content.SharedPreferences", "android.widget.Toast",
            "android.app.NotificationManager", "android.os.Vibrator", "android.content.ClipboardManager",
            "android.database.sqlite.", "android.hardware.", "android.telephony.", "android.location.",
            "android.media.",
        )

        // -- PROCESS_CONTROL ----------------------------------------------------------------------------
        private val RUNTIME_DENIED_MEMBERS = setOf("exec", "exit", "halt", "load", "loadlibrary", "addshutdownhook")
        private val SYSTEM_DENIED_MEMBERS = setOf(
            "exit", "load", "loadlibrary", "setproperty", "clearproperty", "setsecuritymanager",
            "setout", "seterr", "setin", "getenv",
        )
        private val CLASS_DENIED_MEMBERS = setOf("forname", "newinstance")
        private val CLASSLOADER_DENIED_MEMBERS = setOf("loadclass", "defineclass")
        private val PROCESS_OWNER_PREFIXES = listOf(
            "java.lang.ProcessBuilder", "java.lang.reflect.", "kotlin.reflect.full.", "kotlin.reflect.jvm.",
            "dalvik.system.",
        )
    }

    private val findings = LinkedHashMap<String, SandboxFinding>()

    /** Snapshot of what was blocked so far (deduped, insertion-ordered) — the problem-chip feed. */
    fun findings(): List<SandboxFinding> = synchronized(findings) { findings.values.toList() }

    /** Reset recorded findings. The host clears on a buffer edit so the chip tracks the CURRENT text — a
     *  still-present blocked call re-records on the next render pass (recording isn't memoized). */
    fun clearFindings() = synchronized(findings) { findings.clear() }

    /** Memoized classification per `ownerClass|owner|member` — NONE marks the (common) unrestricted answer. */
    private val decisionCache = ConcurrentHashMap<String, Any>()
    private val none = Any()

    override fun beforeCall(call: RNode.Call, receiver: Any?, args: List<Any?>): HookDecision {
        val ownerFqn = (call.callee as? ResolvedCallable.Library)?.ownerFqn
        // A constructor's "member" is the type itself (`FileInputStream(...)`); classify it as owner + <init>.
        val member = if (call.dispatch == DispatchKind.CONSTRUCTOR) "<init>" else call.callee.displayName
        return decide(receiver, ownerFqn, member)
    }

    override fun beforePropertyRead(ownerFqn: String?, name: String, receiver: Any?): HookDecision =
        decide(receiver, ownerFqn, name)

    override fun beforePropertyWrite(name: String, receiver: Any?): HookDecision =
        decide(receiver, ownerFqn = null, member = name)

    override fun beforeClassInit(fqn: String): Boolean {
        // Only owner-wide rules apply to `<clinit>` (there is no member); most inits are benign singletons.
        val cat = classifyOwner(fqn, "<clinit>", "<clinit>") ?: return true
        if (cat !in restricted) return true
        record(cat, "$fqn.<clinit>")
        return false
    }

    private fun decide(receiver: Any?, ownerFqn: String?, member: String): HookDecision {
        val recvClass = receiver?.javaClass
        val key = "${recvClass?.name ?: ""}|${ownerFqn ?: ""}|$member"
        val cached = decisionCache[key]
        val cat: SandboxCategory? = when {
            cached === none -> null
            cached != null -> cached as SandboxCategory
            else -> classify(recvClass, ownerFqn, member).also { decisionCache[key] = it ?: none }
        }
        if (cat == null || cat !in restricted) return HookDecision.Proceed
        val detail = "${recvClass?.name ?: ownerFqn}.$member"
        record(cat, detail)
        return if (stubOnDeny) HookDecision.Replace(null)
        else HookDecision.Deny("the preview sandbox blocked ${cat.label}: `$detail`")
    }

    private fun record(cat: SandboxCategory, detail: String) {
        synchronized(findings) {
            if (findings.size < MAX_FINDINGS) findings.putIfAbsent(detail, SandboxFinding(cat, detail))
        }
    }

    /** The two spellings a member arrives in: the plain lowercase (`startactivity`, `setout`, a property
     *  read's `contentresolver`) and, for an explicit accessor call (`getSystemService`), the getter-stripped
     *  form (`systemservice`). Tables store whichever spelling is canonical; matching tries BOTH — plain
     *  alone would miss `getContentResolver()` vs the `contentResolver` read, and blind stripping would turn
     *  a `setOut` call into `out` and wrongly hit the (benign, allowed) `System.out` read. */
    private fun memberForms(member: String): Pair<String, String> {
        val plain = member.lowercase()
        val stripped =
            if (member.length > 3 && (member.startsWith("get") || member.startsWith("set")) && member[3].isUpperCase())
                member.substring(3).lowercase() else plain
        return plain to stripped
    }

    /** The category of ([recvClass] hierarchy | [ownerFqn]) + [member], or null when unrestricted. The
     *  receiver's RUNTIME hierarchy is checked first (it is ground truth for member/extension dispatch —
     *  `file.readText()`'s owner is the `FilesKt` facade but its receiver is the `File`); the static owner
     *  covers top-level/constructor/static calls with no receiver. */
    private fun classify(recvClass: Class<*>?, ownerFqn: String?, member: String): SandboxCategory? {
        val (plain, stripped) = memberForms(member)
        if (recvClass != null) {
            for (name in hierarchyNames(recvClass)) classifyOwner(name, plain, stripped)?.let { return it }
        }
        return ownerFqn?.let { classifyOwner(it, plain, stripped) }
    }

    /** Superclasses + (transitive) interfaces — `Activity` reaches `android.content.Context`, an impl class
     *  reaches the `SharedPreferences` interface. Bounded by real hierarchies (no cycles in class graphs). */
    private fun hierarchyNames(cls: Class<*>): List<String> {
        val out = ArrayList<String>(8)
        val queue = ArrayDeque<Class<*>>()
        queue.add(cls)
        val seen = HashSet<Class<*>>()
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            out.add(c.name)
            c.superclass?.let { queue.add(it) }
            c.interfaces.forEach { queue.add(it) }
        }
        return out
    }

    /** One owner name against every category table, with the member in both its spellings ([plain] and
     *  getter-[stripped] — see [memberForms]). `<clinit>` (class init) matches only the owner-wide rules —
     *  member tables can't apply without a member. */
    private fun classifyOwner(owner: String, plain: String, stripped: String): SandboxCategory? {
        fun inTable(table: Set<String>) = plain in table || stripped in table

        // FILE_IO
        if (owner in FILE_OWNERS) return SandboxCategory.FILE_IO
        if (FILE_OWNER_PREFIXES.any { owner.startsWith(it) })
            return if (inTable(FILE_FACADE_PURE) || plain == "<clinit>") null else SandboxCategory.FILE_IO
        if (owner == "java.io.File" && plain != "<clinit>" && plain != "<init>" && !inTable(FILE_PURE_MEMBERS))
            return SandboxCategory.FILE_IO

        // NETWORK
        if (owner in NET_OWNERS) return SandboxCategory.NETWORK
        if (NET_OWNER_PREFIXES.any { owner.startsWith(it) }) return SandboxCategory.NETWORK
        if (owner == "java.net.URL" && inTable(URL_NET_MEMBERS)) return SandboxCategory.NETWORK
        if (owner == "java.net.InetAddress" && inTable(INET_NET_MEMBERS)) return SandboxCategory.NETWORK

        // ANDROID_SYSTEM
        if (owner == "android.content.Context" && inTable(CONTEXT_DENIED_MEMBERS)) return SandboxCategory.ANDROID_SYSTEM
        if (ANDROID_OWNER_PREFIXES.any { owner.startsWith(it) }) return SandboxCategory.ANDROID_SYSTEM

        // PROCESS_CONTROL
        if (owner == "java.lang.Runtime" && inTable(RUNTIME_DENIED_MEMBERS)) return SandboxCategory.PROCESS_CONTROL
        if (owner == "java.lang.System" && inTable(SYSTEM_DENIED_MEMBERS)) return SandboxCategory.PROCESS_CONTROL
        if (owner == "java.lang.Class" && inTable(CLASS_DENIED_MEMBERS)) return SandboxCategory.PROCESS_CONTROL
        if (owner == "java.lang.ClassLoader" && inTable(CLASSLOADER_DENIED_MEMBERS)) return SandboxCategory.PROCESS_CONTROL
        if (PROCESS_OWNER_PREFIXES.any { owner.startsWith(it) }) return SandboxCategory.PROCESS_CONTROL

        return null
    }
}
