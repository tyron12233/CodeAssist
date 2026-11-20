package dev.ide.jvm

import java.util.Collections
import java.util.Enumeration
import java.util.Locale
import java.util.Optional
import java.util.Properties
import java.util.ResourceBundle
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream

/**
 * The two platform lookups that search "the caller's class path" and so never saw the program's: both are
 * answered by the host class loader, which holds the IDE and none of the jars the program runs from.
 *
 *  - `ServiceLoader.load(Service.class)`: the providers a library declares in `META-INF/services` (an SLF4J
 *    binding, a JDBC driver, a Jetty extension). The real loader found none, so SLF4J printed "No SLF4J
 *    providers were found" with `slf4j-simple` sitting on the classpath.
 *  - `ResourceBundle.getBundle("a.b.Messages")`: a library's message catalogue, a `.properties` file in its jar
 *    (`jakarta.servlet.LocalStrings`, without which the servlet API fails in its class initializer).
 *
 * Both are read from [Vm.programResources] instead.
 */
internal class InterpretedServices(private val vm: Vm) {

    private val bundles = ConcurrentHashMap<String, ResourceBundle>()

    /** Handles a static call to `ServiceLoader` or `ResourceBundle`; [NOT_HANDLED] leaves it to the host. */
    fun intercept(resources: ProgramResources, owner: String, name: String, args: List<Any?>): Any? = when {
        owner == "java/util/ServiceLoader" && (name == "load" || name == "loadInstalled") -> serviceLoader(resources, args)
        owner == "java/util/ResourceBundle" && name == "getBundle" -> bundle(resources, args)
        else -> NOT_HANDLED
    }

    // ---- ServiceLoader -------------------------------------------------------------------------------

    private fun serviceLoader(resources: ProgramResources, args: List<Any?>): Any? {
        val service = args.getOrNull(0) as? Class<*> ?: return NOT_HANDLED
        val interpreted = vm.peerFactory.interpretedNameOf(service)
        val serviceName = interpreted?.replace('/', '.') ?: service.name
        val declared = LinkedHashSet<String>()
        for (url in runCatching { resources.find("META-INF/services/$serviceName") }.getOrDefault(emptyList())) {
            runCatching {
                url.openConnection().apply { useCaches = false }.getInputStream().bufferedReader().useLines { lines ->
                    lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach(declared::add)
                }
            }
        }
        // A real service with nothing on the program's classpath is the host's business alone.
        if (declared.isEmpty() && interpreted == null) return NOT_HANDLED
        val fromProgram: List<() -> Any?> = declared.map { provider -> { instantiate(provider, serviceName) } }
        // A real service can also have providers the platform itself supplies; they follow the program's.
        val fromHost: List<() -> Any?> = if (interpreted != null) emptyList() else runCatching {
            ServiceLoader.load(service).filter { it.javaClass.name !in declared }.map { p -> { p } }
        }.getOrDefault(emptyList())
        return VmServiceLoader(serviceName, declared.toList(), fromProgram + fromHost) { n -> classOf(n) }
    }

    private fun instantiate(provider: String, service: String): Any? =
        try {
            val internal = provider.replace('.', '/')
            if (vm.resolve(internal) != null) {
                val ctor = vm.interpretedConstructors(provider).firstOrNull { it.paramDescriptors.isEmpty() }
                    ?: throw NoSuchMethodException("$provider has no no-argument constructor")
                ctor.invoke(null, emptyList())
            } else {
                vm.loadReal(internal).getDeclaredConstructor().newInstance()
            }
        } catch (e: VmInterruptedException) {
            throw e
        } catch (e: Throwable) {
            throw ServiceConfigurationError("$service: Provider $provider could not be instantiated", e)
        }

    private fun classOf(name: String): Class<*>? =
        vm.classForInterpreted(name.replace('.', '/')) ?: runCatching { vm.loadReal(name.replace('.', '/')) }.getOrNull()

    // ---- ResourceBundle ------------------------------------------------------------------------------

    private fun bundle(resources: ProgramResources, args: List<Any?>): Any? {
        val base = args.getOrNull(0) as? String ?: return NOT_HANDLED
        val locale = args.firstOrNull { it is Locale } as? Locale ?: Locale.getDefault()
        bundles["$base|$locale"]?.let { return it }
        // Most specific first: `Messages_fr_CA`, `Messages_fr`, `Messages`.
        fun chain(l: Locale): List<String> = buildList {
            if (l.country.isNotEmpty() && l.variant.isNotEmpty()) add("${base}_${l.language}_${l.country}_${l.variant}")
            if (l.language.isNotEmpty() && l.country.isNotEmpty()) add("${base}_${l.language}_${l.country}")
            if (l.language.isNotEmpty()) add("${base}_${l.language}")
            add(base)
        }
        fun present(candidates: List<String>) = candidates.mapNotNull { level(resources, it) }
        // The requested locale's bundles over the root's; the default locale's only when the requested one has
        // none of its own, as the platform falls back.
        val levels = present(chain(locale)).takeIf { it.size > 1 || locale == Locale.ROOT }
            ?: present(chain(Locale.getDefault())).takeIf { it.isNotEmpty() }
            ?: present(listOf(base))
        // Not a bundle of the program's at all: the host answers (or reports it missing).
        if (levels.isEmpty()) return NOT_HANDLED
        return ChainBundle(levels, locale).also { bundles["$base|$locale"] = it }
    }

    /**
     * The bundle named exactly [name] (`a.b.Messages_fr`), or null: a class of the program's when there is one
     * (a `ListResourceBundle` it wrote, created by the interpreter and handed out as its peer, which IS a real
     * `ResourceBundle`), else a `.properties` file. A class comes first, as the platform orders them.
     */
    private fun level(resources: ProgramResources, name: String): ResourceBundle? {
        if (vm.resolve(name.replace('.', '/')) != null) {
            val made = runCatching {
                vm.interpretedConstructors(name).firstOrNull { it.paramDescriptors.isEmpty() }?.invoke(null, emptyList())
            }.getOrNull()
            if (made is ResourceBundle) return made
        }
        val url = runCatching { resources.find(name.replace('.', '/') + ".properties") }.getOrNull()?.firstOrNull() ?: return null
        val loaded = Properties()
        runCatching { url.openConnection().apply { useCaches = false }.getInputStream().reader(Charsets.UTF_8).use(loaded::load) }
            .getOrElse { return null }
        val entries = HashMap<String, Any>()
        for (key in loaded.stringPropertyNames()) entries[key] = loaded.getProperty(key)
        return MapBundle(entries)
    }

    private class MapBundle(private val entries: Map<String, Any>) : ResourceBundle() {
        override fun handleGetObject(key: String): Any? = entries[key]
        override fun getKeys(): Enumeration<String> = Collections.enumeration(entries.keys)
    }

    /** A locale's bundles as one, the most specific answering first. They are chained here, not through
     *  `setParent`, which is not ours to call on a bundle another class made. */
    private class ChainBundle(private val levels: List<ResourceBundle>, private val forLocale: Locale) : ResourceBundle() {
        override fun handleGetObject(key: String): Any? {
            for (level in levels) if (level.containsKey(key)) return level.getObject(key)
            return null
        }
        override fun getKeys(): Enumeration<String> =
            Collections.enumeration(levels.flatMapTo(LinkedHashSet()) { it.keySet() })
        override fun getLocale(): Locale = forLocale
    }

    companion object {
        val NOT_HANDLED: Any = InterpretedReflection.NOT_HANDLED
    }
}

/**
 * What interpreted code holds in place of a `java.util.ServiceLoader` (a final class that can only search a
 * real class loader). It is used through `ServiceLoader`'s own method names, which the bridge resolves on
 * whatever object it is handed, so the program cannot tell; the providers are created on first use and kept,
 * as the real one does. An interpreted provider is handed out as its peer, which re-enters interpreted code as
 * the instance itself.
 */
class VmServiceLoader internal constructor(
    private val service: String,
    private val names: List<String>,
    private val factories: List<() -> Any?>,
    private val typeOf: (String) -> Class<*>?,
) : Iterable<Any?> {
    private val created = arrayOfNulls<Any?>(factories.size)
    private val done = BooleanArray(factories.size)

    private fun at(index: Int): Any? = synchronized(this) {
        if (!done[index]) {
            created[index] = factories[index]()
            done[index] = true
        }
        created[index]
    }

    override fun iterator(): Iterator<Any?> = object : Iterator<Any?> {
        private var next = 0
        override fun hasNext(): Boolean = next < factories.size
        override fun next(): Any? {
            if (next >= factories.size) throw NoSuchElementException()
            return at(next++)
        }
    }

    fun findFirst(): Optional<Any> = if (factories.isEmpty()) Optional.empty() else Optional.ofNullable(at(0))

    /** `ServiceLoader.stream()`: one lazy [Provider] per service, in declaration order. */
    fun stream(): Stream<Provider> = factories.indices.map { Provider(this, it) }.stream()

    fun reload() = synchronized(this) { done.fill(false); created.fill(null) }

    override fun toString(): String = "java.util.ServiceLoader[$service]"

    /** Stands for a `ServiceLoader.Provider`, answering its `get()` and `type()`. */
    class Provider internal constructor(private val owner: VmServiceLoader, private val index: Int) {
        fun get(): Any? = owner.at(index)
        fun type(): Class<*>? = owner.names.getOrNull(index)?.let(owner.typeOf) ?: owner.at(index)?.javaClass
    }
}
