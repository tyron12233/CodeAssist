package dev.ide.embedded.gen

import dev.ide.embedded.EmbeddedEngineHost
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.remote.RemoteCalls
import dev.ide.ui.backend.remote.remoteIdeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.isSubclassOf
import kotlin.system.exitProcess

/**
 * The embedded engine walked the way the iOS app walks it, on a desktop JVM: started as a whole backend, a
 * project made from each template, and every member of every service that takes no argument asked for once.
 *
 * It exists to be run under native-image's tracing agent (`./gradlew :ide-embedded:traceRemoteBackend`), whose
 * record of what the engine reached by reflection is what the image compiled ahead of time is built with: a
 * path the agent never saw is a path that fails on the phone. `main(<empty work folder>)`.
 */
object RemoteBackendSweep {
    private val unsafe = Regex(
        "delete|remove|clear|reset|signOut|signIn|stop|cancel|backup|export|uninstall|close|restore|exit|crash|wipe|" +
            "abort|discard|revert|rollback|init|commit|push|pull|fetch|merge|stash|checkout|ignore|publish|submit|send|upload",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Version control is asked only what reads. Its other members act on whatever repository encloses the
     * project, and swept once inside this repository they reset its working tree.
     */
    private val vcsReads = setOf("refresh", "stashes", "remotes", "identity", "credentialHosts", "defaultBranch", "supported", "state")

    /** The one member that is not carried is the plugin UI, which nothing here asks for. */
    private fun nothing(face: Class<*>): Any = java.lang.reflect.Proxy.newProxyInstance(face.classLoader, arrayOf(face)) { _, method, _ ->
        when {
            method.name == "uiPlugins" -> emptyList<Any>()
            method.returnType.isInterface && method.returnType.name.startsWith("dev.ide.ui.backend.") -> nothing(method.returnType)
            else -> error("${method.name} is not carried")
        }
    }
    private val nothingLocal = nothing(IdeBackend::class.java) as IdeBackend

    @JvmStatic
    fun main(args: Array<String>) {
        val work = File(args[0]).apply { mkdirs() }
        check(generateSequence(work.absoluteFile) { it.parentFile }.none { File(it, ".git").exists() }) {
            "$work is inside a git repository, which the engine's version control would act on"
        }
        val started = EmbeddedEngineHost.start(File(work, "Projects").path)
        check(started.isEmpty()) { started }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val near = remoteIdeBackend(RemoteCalls({ EmbeddedEngineHost.call(it) }, scope, Dispatchers.IO), local = nothingLocal)
        var failures = 0
        runBlocking {
            for (template in near.projects.projectTemplates()) {
                val name = "T" + template.id.filter { it.isLetterOrDigit() }.take(24)
                val before = near.projects.projectEpoch.value
                val made = runCatching { near.projects.createProject(template.id, mapOf("name" to name, "packageName" to "com.example.${name.lowercase()}")) }
                println("create ${template.id}: ${made.getOrNull()?.let { if (it.success) "ok" else it.message } ?: made.exceptionOrNull()}")
                if (made.getOrNull()?.success != true) { failures++; continue }
                withTimeoutOrNull(60_000) { near.projects.projectEpoch.first { it != before } }
                failures += sweep(near)
                fun TreeNode.all(): List<TreeNode> = listOf(this) + children.flatMap { it.all() }
                val files = near.files.fileTree(TreeViewMode.Project).all().mapNotNull { it.filePath }
                    .filter { it.endsWith(".java") || it.endsWith(".kt") || it.endsWith(".xml") }.take(6)
                for (path in files) {
                    val text = File(path).readText()
                    near.editor.updateDocument(path, text)
                    val asked = runCatching {
                        near.editor.analyze(path, text)
                        near.editor.complete(path, text, text.length / 2)
                    }
                    println("  edit ${File(path).name}: ${asked.exceptionOrNull() ?: "ok"}")
                    if (asked.isFailure) failures++
                }
            }
        }
        println("sweep done, $failures failed")
        exitProcess(0)
    }

    /** Every member with no argument, of every service, once. Returns how many failed. */
    private suspend fun sweep(backend: IdeBackend): Int {
        var failures = 0
        val services = IdeBackend::class.members.filterIsInstance<KProperty<*>>()
            .mapNotNull { runCatching { it.getter.call(backend) }.getOrNull() }
            .filter { it::class.java.interfaces.any { i -> i.name.startsWith("dev.ide.ui.backend.") } }
        for (service in services + backend) {
            val face = service::class.java.interfaces.first { it.name.startsWith("dev.ide.ui.backend.") }.kotlin
            for (member in face.members) {
                if (member.name in setOf("equals", "hashCode", "toString") || unsafe.containsMatchIn(member.name)) continue
                if (face.simpleName == "VcsService" && member.name !in vcsReads) continue
                if (member.parameters.any { it.kind == KParameter.Kind.VALUE }) continue
                val result = runCatching {
                    val value = withTimeoutOrNull(30_000) { if (member.isSuspend) member.callSuspend(service) else member.call(service) }
                    if (value is Flow<*> && value::class.isSubclassOf(kotlinx.coroutines.flow.StateFlow::class)) (value as kotlinx.coroutines.flow.StateFlow<*>).value
                }
                val cause = result.exceptionOrNull()?.let { it.cause ?: it }
                if (cause != null) {
                    failures++
                    println("  ${face.simpleName}.${member.name}: ${cause::class.simpleName}: ${cause.message?.take(160)}")
                }
            }
        }
        return failures
    }
}
