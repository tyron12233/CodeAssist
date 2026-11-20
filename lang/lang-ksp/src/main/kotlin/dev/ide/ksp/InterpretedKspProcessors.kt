package dev.ide.ksp

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import dev.ide.jvm.AsmPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.PeerFactory
import dev.ide.jvm.ProgramResources
import dev.ide.jvm.Vm
import dev.ide.jvm.interpretedConstructors
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Runs KSP processors WITHOUT defining their classes: the processor jars (the code generator and its whole
 * closure, kotlinpoet, javapoet, guava, Dagger's own model...) are executed by the bytecode VM, while KSP
 * itself stays native. KSP is handed a peer that implements [SymbolProcessorProvider]; everything behind it is
 * interpreted.
 *
 * What this buys over loading the jars: any processor at any version can run (nothing has to be dexed, so
 * nothing has to ship with the app), two processors' overlapping closures cannot collide (the first jar wins,
 * as on a class path), and a processor's `META-INF/services` and resources are read straight from its jars.
 *
 * Pass [providers] as [KspSourceGenerator]'s `providerFactory`. One instance serves one run; [close] it after,
 * which releases the jars.
 *
 * The boundary: a class is the host's when it is part of what the host and the processor must agree on (the
 * JDK, the Kotlin runtime, KSP's API) AND the host can load it; everything else comes from the processor's
 * own jars. So a `javax.inject` or a `kotlin.metadata` the host lacks is interpreted, not missing.
 */
class InterpretedKspProcessors(
    /** Produces the real objects that let KSP call into interpreted code; a device host passes one that dexes. */
    private val peerFactory: PeerFactory = AsmPeerFactory(),
    private val host: ClassLoader = SymbolProcessorProvider::class.java.classLoader!!,
) : AutoCloseable {

    private val zips = ArrayList<Pair<Path, ZipFile>>()
    private val hostLoadable = ConcurrentHashMap<String, Boolean>()

    /** The VM of the last [providers] call, for a caller that reports how much it ran. */
    @Volatile var vm: Vm? = null
        private set

    /** Instantiates, in the VM, every provider [jars] declare in `META-INF/services`. */
    fun providers(jars: List<Path>): List<SymbolProcessorProvider> {
        jars.filter { Files.isRegularFile(it) }.forEach { zips += it to ZipFile(it.toFile()) }
        val machine = Vm(source = ClassBytesSource(::bytesFor), policy = InterpretPolicy(::interpreted), peerFactory = peerFactory)
        machine.programResources = ProgramResources(::resources)
        vm = machine
        val declared = resources(SERVICE).flatMap { url ->
            url.openConnection().apply { useCaches = false }.getInputStream().bufferedReader().useLines { lines ->
                lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
            }
        }.distinct()
        return declared.map { name ->
            val ctor = machine.interpretedConstructors(name).firstOrNull { it.paramDescriptors.isEmpty() }
                ?: error("KSP provider $name is not on the processor classpath or has no no-argument constructor")
            ctor.invoke(null, emptyList()) as SymbolProcessorProvider
        }
    }

    private fun interpreted(internalName: String): Boolean {
        if (ALWAYS_HOST.any { internalName.startsWith(it) }) return false
        if (SHARED.none { internalName.startsWith(it) }) return true
        return !hostLoadable.getOrPut(internalName) {
            try {
                Class.forName(internalName.replace('/', '.'), false, host)
                true
            } catch (_: ClassNotFoundException) {
                false
            } catch (_: LinkageError) {
                false
            }
        }
    }

    /** First jar wins, the way a class loader over them would resolve. */
    private fun bytesFor(internalName: String): ByteArray? {
        for ((_, zip) in zips) {
            val entry = zip.getEntry("$internalName.class") ?: continue
            return synchronized(zip) { zip.getInputStream(entry).use { it.readBytes() } }
        }
        return null
    }

    private fun resources(name: String) = zips.mapNotNull { (jar, zip) ->
        zip.getEntry(name)?.let { URI("jar:${jar.toUri()}!/$name").toURL() }
    }

    override fun close() {
        zips.forEach { runCatching { it.second.close() } }
        zips.clear()
    }

    private companion object {
        val SERVICE = "META-INF/services/${SymbolProcessorProvider::class.java.name}"

        /** The platform: never the processor's to supply. */
        val ALWAYS_HOST = listOf("java/", "jdk/", "sun/", "com/sun/", "android/", "dalvik/")

        /** What the host and the processor must see as ONE type when the host has it: values of these cross
         *  between KSP and the processor, or are the runtime both run on. */
        val SHARED = listOf("javax/", "kotlin/", "kotlinx/", "org/jetbrains/", "com/google/devtools/ksp/")
    }
}
