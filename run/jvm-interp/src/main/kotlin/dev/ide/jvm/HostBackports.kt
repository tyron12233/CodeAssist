package dev.ide.jvm

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.Optional
import java.util.stream.Stream

/**
 * Library methods newer than the oldest runtime the IDE runs on, answered here when the host has no such method.
 *
 * A bridged call runs on the host's own class library. On a phone that is the device's Android release, so
 * `list.getFirst()` (Java 21, Android 15) or `stream.toList()` (Java 16, Android 14) compiled fine and then
 * stopped the program with "no method" on anything older. Each entry reimplements one method on top of what
 * every supported release has. They are consulted only after the host's own lookup came up empty, so a
 * runtime that has the method always runs its own.
 *
 * Deliberately a short list of what everyday programs call, not a compatibility layer: collection factories,
 * the sequenced-collection accessors on a `List`, the common `String`, `Stream`, `Optional`, `Math` and
 * stream-copying additions, and the Java 21 thread API ([EmulatedThreads]).
 */
internal object HostBackports {

    /** Test hook: answer from here even when the host has the method, so a desktop JVM exercises the entries. */
    @Volatile var preferBackports: Boolean = false

    /** The stand-in for static `owner.name descriptor`, taking the real (marshalled) arguments, or null. */
    fun static(owner: String, name: String, descriptor: String): ((Array<Any?>) -> Any?)? = when (owner) {
        "java/lang/Math" -> if (name != "clamp") null else when (descriptor) {
            "(JII)I" -> { a -> clampInt(a[0] as Long, a[1] as Int, a[2] as Int) }
            "(JJJ)J" -> { a -> ordered(a[1] as Long, a[2] as Long); (a[0] as Long).coerceIn(a[1] as Long, a[2] as Long) }
            "(DDD)D" -> { a -> ordered(a[1] as Double, a[2] as Double); (a[0] as Double).coerceIn(a[1] as Double, a[2] as Double) }
            "(FFF)F" -> { a -> ordered(a[1] as Float, a[2] as Float); (a[0] as Float).coerceIn(a[1] as Float, a[2] as Float) }
            else -> null
        }
        "java/util/List" -> when (name) {
            "of" -> { a -> listOf(flat(descriptor, a)) }
            "copyOf" -> { a -> listOf((a[0] as Collection<*>).toList()) }
            else -> null
        }
        "java/util/Set" -> when (name) {
            "of" -> { a -> setOf(flat(descriptor, a)) }
            "copyOf" -> { a -> Collections.unmodifiableSet(LinkedHashSet(nonNull((a[0] as Collection<*>).toList()))) }
            else -> null
        }
        "java/util/Map" -> when (name) {
            "of" -> { a -> mapOf(a.toList().chunked(2).map { it[0] to it[1] }) }
            "entry" -> { a -> java.util.AbstractMap.SimpleImmutableEntry(nonNull(listOf(a[0]))[0], nonNull(listOf(a[1]))[0]) }
            "ofEntries" -> { a -> mapOf((a[0] as Array<*>).map { (it as Map.Entry<*, *>).key to it.value }) }
            "copyOf" -> { a -> mapOf((a[0] as Map<*, *>).entries.map { it.key to it.value }) }
            else -> null
        }
        // No virtual threads on such a runtime: the API is there, on platform threads (see [EmulatedThreads]).
        "java/lang/Thread" -> when (name) {
            "ofVirtual" -> { _ -> EmulatedThreads.Builder(isVirtual = true) }
            "ofPlatform" -> { _ -> EmulatedThreads.Builder(isVirtual = false) }
            "startVirtualThread" -> { a -> EmulatedThreads.newVirtual(a[0] as Runnable, null).also { it.start() } }
            else -> null
        }
        "java/util/stream/Stream" -> if (name == "iterate" && descriptor == "(Ljava/lang/Object;Ljava/util/function/Predicate;Ljava/util/function/UnaryOperator;)Ljava/util/stream/Stream;") {
            { a ->
                @Suppress("UNCHECKED_CAST") val hasNext = a[1] as java.util.function.Predicate<Any?>
                @Suppress("UNCHECKED_CAST") val next = a[2] as java.util.function.UnaryOperator<Any?>
                streamOf(object : Iterator<Any?> {
                    private var current = a[0]
                    private var started = false
                    private fun upcoming() = if (started) next.apply(current) else current
                    private var peeked = false
                    private var peek: Any? = null
                    override fun hasNext(): Boolean {
                        if (!peeked) { peek = upcoming(); peeked = true }
                        return hasNext.test(peek)
                    }
                    override fun next(): Any? {
                        if (!hasNext()) throw NoSuchElementException()
                        current = peek; started = true; peeked = false
                        return current
                    }
                })
            }
        } else null
        "java/util/stream/Collectors" -> if (name == "teeing") {
            { a ->
                @Suppress("UNCHECKED_CAST") val one = a[0] as java.util.stream.Collector<Any?, Any?, Any?>
                @Suppress("UNCHECKED_CAST") val two = a[1] as java.util.stream.Collector<Any?, Any?, Any?>
                @Suppress("UNCHECKED_CAST") val merge = a[2] as java.util.function.BiFunction<Any?, Any?, Any?>
                java.util.stream.Collector.of<Any?, Array<Any?>, Any?>(
                    { arrayOf(one.supplier().get(), two.supplier().get()) },
                    { acc, e -> one.accumulator().accept(acc[0], e); two.accumulator().accept(acc[1], e) },
                    { x, y -> arrayOf(one.combiner().apply(x[0], y[0]), two.combiner().apply(x[1], y[1])) },
                    { acc -> merge.apply(one.finisher().apply(acc[0]), two.finisher().apply(acc[1])) },
                )
            }
        } else null
        "java/util/concurrent/Executors" -> when (name) {
            "newVirtualThreadPerTaskExecutor" -> { _ -> EmulatedThreads.PerTaskExecutor { EmulatedThreads.newVirtual(it, null) } }
            "newThreadPerTaskExecutor" -> { a -> EmulatedThreads.PerTaskExecutor(a[0] as java.util.concurrent.ThreadFactory) }
            else -> null
        }
        else -> null
    }

    /** The stand-in for `receiver.name descriptor`, taking the receiver and the real arguments, or null. */
    fun virtual(receiver: Any, name: String, descriptor: String): ((Any, Array<Any?>) -> Any?)? {
        val key = name + descriptor
        return when (receiver) {
            is String -> when (key) {
                "formatted([Ljava/lang/Object;)Ljava/lang/String;" -> { r, a -> String.format(r as String, *(a[0] as Array<*>)) }
                "isBlank()Z" -> { r, _ -> (r as String).all { Character.isWhitespace(it) } }
                "strip()Ljava/lang/String;" -> { r, _ -> (r as String).trim { Character.isWhitespace(it) } }
                "stripLeading()Ljava/lang/String;" -> { r, _ -> (r as String).trimStart { Character.isWhitespace(it) } }
                "stripTrailing()Ljava/lang/String;" -> { r, _ -> (r as String).trimEnd { Character.isWhitespace(it) } }
                "repeat(I)Ljava/lang/String;" -> { r, a ->
                    val n = a[0] as Int
                    if (n < 0) throw IllegalArgumentException("count is negative: $n")
                    (r as String).repeat(n)
                }
                "lines()Ljava/util/stream/Stream;" -> { r, _ -> linesOf(r as String).stream() }
                "stripIndent()Ljava/lang/String;" -> { r, _ -> stripIndent(r as String) }
                "transform(Ljava/util/function/Function;)Ljava/lang/Object;" -> { r, a ->
                    @Suppress("UNCHECKED_CAST") (a[0] as java.util.function.Function<Any?, Any?>).apply(r)
                }
                "splitWithDelimiters(Ljava/lang/String;I)[Ljava/lang/String;" -> { r, a -> splitWithDelimiters(r as String, a[0] as String, a[1] as Int) }
                else -> null
            }
            is StringBuilder -> when (key) {
                "repeat(Ljava/lang/CharSequence;I)Ljava/lang/StringBuilder;" -> { r, a ->
                    val n = a[1] as Int
                    if (n < 0) throw IllegalArgumentException("count is negative: $n")
                    (r as StringBuilder).apply { repeat(n) { append(a[0] as CharSequence?) } }
                }
                else -> null
            }
            is Stream<*> -> when (key) {
                "toList()Ljava/util/List;" -> { r, _ -> Collections.unmodifiableList(ArrayList<Any?>((r as Stream<*>).toArray().asList())) }
                "mapMulti(Ljava/util/function/BiConsumer;)Ljava/util/stream/Stream;" -> { r, a ->
                    @Suppress("UNCHECKED_CAST") val mapper = a[0] as java.util.function.BiConsumer<Any?, java.util.function.Consumer<Any?>>
                    (r as Stream<*>).flatMap { element ->
                        val buffer = ArrayList<Any?>()
                        mapper.accept(element, java.util.function.Consumer { buffer.add(it) })
                        buffer.stream()
                    }
                }
                "takeWhile(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;" -> { r, a ->
                    @Suppress("UNCHECKED_CAST") val keep = a[0] as java.util.function.Predicate<Any?>
                    // Reads only as far as the first element that fails, so an infinite source still ends.
                    val taken = ArrayList<Any?>()
                    val elements = (r as Stream<*>).iterator()
                    while (elements.hasNext()) {
                        val e = elements.next()
                        if (!keep.test(e)) break
                        taken += e
                    }
                    taken.stream()
                }
                "dropWhile(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;" -> { r, a ->
                    @Suppress("UNCHECKED_CAST") val drop = a[0] as java.util.function.Predicate<Any?>
                    val elements = (r as Stream<*>).iterator()
                    var first: Any? = null
                    var any = false
                    while (elements.hasNext()) {
                        val e = elements.next()
                        if (!drop.test(e)) { first = e; any = true; break }
                    }
                    if (!any) Stream.empty<Any?>() else Stream.concat(Stream.of(first), streamOf(elements))
                }
                else -> null
            }
            is Optional<*> -> when (key) {
                "isEmpty()Z" -> { r, _ -> !(r as Optional<*>).isPresent }
                "orElseThrow()Ljava/lang/Object;" -> { r, _ -> (r as Optional<*>).orElseThrow { NoSuchElementException("No value present") } }
                else -> null
            }
            is InputStream -> when (key) {
                "readAllBytes()[B" -> { r, _ -> ByteArrayOutputStream().also { copy(r as InputStream, it) }.toByteArray() }
                "transferTo(Ljava/io/OutputStream;)J" -> { r, a -> copy(r as InputStream, a[0] as OutputStream) }
                else -> null
            }
            is Thread -> when (key) {
                "isVirtual()Z" -> { r, _ -> EmulatedThreads.isVirtual(r as Thread) }
                "threadId()J" -> { r, _ -> @Suppress("DEPRECATION") (r as Thread).id }
                else -> null
            }
            is java.util.concurrent.ExecutorService -> when (key) {
                "close()V" -> { r, _ -> EmulatedThreads.closeExecutor(r as java.util.concurrent.ExecutorService); null }
                else -> null
            }
            is MutableList<*> -> sequenced(key)
            else -> null
        }
    }

    /** The `SequencedCollection` members of a `List`. `reversed()` is a live view ([Reversed]), as on the platform. */
    @Suppress("UNCHECKED_CAST")
    private fun sequenced(key: String): ((Any, Array<Any?>) -> Any?)? = when (key) {
        "getFirst()Ljava/lang/Object;" -> { r, _ -> (r as List<Any?>).let { if (it.isEmpty()) throw NoSuchElementException() else it[0] } }
        "getLast()Ljava/lang/Object;" -> { r, _ -> (r as List<Any?>).let { if (it.isEmpty()) throw NoSuchElementException() else it[it.size - 1] } }
        "removeFirst()Ljava/lang/Object;" -> { r, _ -> (r as MutableList<Any?>).let { if (it.isEmpty()) throw NoSuchElementException() else it.removeAt(0) } }
        "removeLast()Ljava/lang/Object;" -> { r, _ -> (r as MutableList<Any?>).let { if (it.isEmpty()) throw NoSuchElementException() else it.removeAt(it.size - 1) } }
        "addFirst(Ljava/lang/Object;)V" -> { r, a -> (r as MutableList<Any?>).add(0, a[0]); null }
        "addLast(Ljava/lang/Object;)V" -> { r, a -> (r as MutableList<Any?>).add(a[0]); null }
        "reversed()Ljava/util/List;" -> { r, _ -> if (r is Reversed) r.base else Reversed(r as MutableList<Any?>) }
        else -> null
    }

    /** `List.reversed()`: [base] read and written back to front, so a change through either shows in the other. */
    private class Reversed(val base: MutableList<Any?>) : java.util.AbstractList<Any?>() {
        override val size: Int get() = base.size
        override fun get(index: Int): Any? = base[at(index)]
        override fun set(index: Int, element: Any?): Any? = base.set(at(index), element)
        override fun add(index: Int, element: Any?) {
            if (index < 0 || index > base.size) throw IndexOutOfBoundsException("Index: $index, Size: ${base.size}")
            base.add(base.size - index, element)
        }
        override fun removeAt(index: Int): Any? = base.removeAt(at(index))

        private fun at(index: Int): Int {
            if (index < 0 || index >= base.size) throw IndexOutOfBoundsException("Index: $index, Size: ${base.size}")
            return base.size - 1 - index
        }
    }

    private fun clampInt(value: Long, min: Int, max: Int): Int {
        if (min > max) throw IllegalArgumentException("$min > $max")
        return value.coerceIn(min.toLong(), max.toLong()).toInt()
    }

    private fun <T : Comparable<T>> ordered(min: T, max: T) {
        if (min > max) throw IllegalArgumentException("$min > $max")
    }

    /** The elements of an `of(...)` call: the one array of the varargs form, else the arguments themselves. */
    private fun flat(descriptor: String, args: Array<Any?>): List<Any?> =
        if (descriptor.startsWith("([Ljava/lang/Object;)")) (args[0] as Array<*>).toList() else args.toList()

    private fun nonNull(elements: List<Any?>): List<Any> = elements.map { it ?: throw NullPointerException() }

    private fun listOf(elements: List<Any?>): List<Any> = Collections.unmodifiableList(ArrayList(nonNull(elements)))

    private fun setOf(elements: List<Any?>): Set<Any> {
        val out = LinkedHashSet<Any>()
        for (e in nonNull(elements)) if (!out.add(e)) throw IllegalArgumentException("duplicate element: $e")
        return Collections.unmodifiableSet(out)
    }

    private fun mapOf(pairs: List<Pair<Any?, Any?>>): Map<Any, Any> {
        val out = LinkedHashMap<Any, Any>()
        for ((k, v) in pairs) {
            if (k == null || v == null) throw NullPointerException()
            if (out.put(k, v) != null) throw IllegalArgumentException("duplicate key: $k")
        }
        return Collections.unmodifiableMap(out)
    }

    private fun copy(input: InputStream, out: OutputStream): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            out.write(buffer, 0, n)
            total += n
        }
    }

    private fun streamOf(elements: Iterator<Any?>): Stream<Any?> =
        java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(elements, java.util.Spliterator.ORDERED), false)

    /** `String.splitWithDelimiters(regex, limit)`: `split`, with each matched delimiter kept between the pieces. */
    private fun splitWithDelimiters(s: String, regex: String, limit: Int): Array<String> {
        val matcher = java.util.regex.Pattern.compile(regex).matcher(s)
        val out = ArrayList<String>()
        var index = 0
        var matches = 0
        while (matcher.find()) {
            if (limit > 0 && matches >= limit - 1) break
            // A zero-width match at the very start yields no leading empty piece.
            if (index == 0 && matcher.start() == 0 && matcher.end() == 0) continue
            out += s.substring(index, matcher.start())
            out += matcher.group()
            index = matcher.end()
            matches++
        }
        if (index == 0) return arrayOf(s)
        out += s.substring(index)
        if (limit == 0) while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.toTypedArray()
    }

    /** `String.lines()`: split at `\n`, `\r` or `\r\n`, with no empty line after a final terminator. */
    private fun linesOf(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\n' || c == '\r') {
                out += s.substring(start, i)
                if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    /** `String.stripIndent()`: the common leading white space of the non-blank lines (and of the last line,
     *  blank or not) is removed from every line, trailing white space too; a final terminator opts out of the
     *  indentation part and is kept. */
    private fun stripIndent(s: String): String {
        if (s.isEmpty()) return ""
        val optOut = s.last() == '\n' || s.last() == '\r'
        val lines = linesOf(s)
        fun leading(line: String) = line.indexOfFirst { !Character.isWhitespace(it) }.let { if (it < 0) line.length else it }
        var outdent = 0
        if (!optOut) {
            outdent = Int.MAX_VALUE
            lines.forEachIndexed { i, line ->
                val lead = leading(line)
                if (lead != line.length || i == lines.lastIndex) outdent = minOf(outdent, lead)
            }
        }
        val body = lines.joinToString("\n") { line ->
            val first = minOf(outdent, leading(line))
            val last = line.indexOfLast { !Character.isWhitespace(it) } + 1
            if (first >= last) "" else line.substring(first, last)
        }
        return if (optOut) body + "\n" else body
    }
}
