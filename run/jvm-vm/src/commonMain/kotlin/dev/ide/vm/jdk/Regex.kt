package dev.ide.vm.jdk

import dev.ide.vm.*

/** `java.util.regex` over Kotlin's [Regex], whose syntax is Java's on every platform it runs on. */
internal class PatternState(val source: String, val flags: Int) {
    val regex: Regex = Regex(source, buildSet {
        if (flags and 2 != 0) add(RegexOption.IGNORE_CASE)
        if (flags and 8 != 0) add(RegexOption.MULTILINE)
        if (flags and 16 != 0) add(RegexOption.LITERAL)
        if (flags and 4 != 0) add(RegexOption.COMMENTS)
        if (flags and 32 != 0) add(RegexOption.DOT_MATCHES_ALL)
        if (flags and 1 != 0) add(RegexOption.UNIX_LINES)
        if (flags and 128 != 0) add(RegexOption.CANON_EQ)
    })
}

internal class MatcherState(var pattern: PatternState, var input: String) {
    var match: MatchResult? = null
    var searchFrom = 0
    var appendPosition = 0
}

internal fun ClassDefs.registerRegex() {
    define("java/util/regex/Pattern") {
        implements("java/io/Serializable")
        fun Call.p(): PatternState = self().native as PatternState
        static("compile", "(Ljava/lang/String;)Ljava/util/regex/Pattern;") { retRef(vm.pattern(r(0) as String, 0)) }
        static("compile", "(Ljava/lang/String;I)Ljava/util/regex/Pattern;") { retRef(vm.pattern(r(0) as String, i(1))) }
        static("matches", "(Ljava/lang/String;Ljava/lang/CharSequence;)Z") { ret(Regex(r(0) as String).matches(vm.charSequence(r(1)))) }
        static("quote", "(Ljava/lang/String;)Ljava/lang/String;") { retRef(Regex.escape(r(0) as String)) }
        method("matcher", "(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;") {
            val m = vm.newVmObject("java/util/regex/Matcher"); m.native = MatcherState(p(), vm.charSequence(r(1))); retRef(m)
        }
        method("pattern", "()Ljava/lang/String;") { retRef(p().source) }
        method("toString", "()Ljava/lang/String;") { retRef(p().source) }
        method("flags", "()I") { ret(p().flags) }
        method("split", "(Ljava/lang/CharSequence;)[Ljava/lang/String;") {
            retRef(vm.stringArray(javaSplitRegex(p().regex, vm.charSequence(r(1)), 0)))
        }
        method("split", "(Ljava/lang/CharSequence;I)[Ljava/lang/String;") {
            retRef(vm.stringArray(javaSplitRegex(p().regex, vm.charSequence(r(1)), i(2))))
        }
    }
    define("java/util/regex/MatchResult") { asInterface() }
    define("java/util/regex/Matcher") {
        implements("java/util/regex/MatchResult")
        fun Call.m(): MatcherState = self().native as MatcherState
        fun Call.current(): MatchResult = m().match ?: vm.throwVm("java/lang/IllegalStateException", "No match found")
        method("matches", "()Z") { val s = m(); s.match = s.pattern.regex.matchEntire(s.input); ret(s.match != null) }
        method("lookingAt", "()Z") {
            val s = m()
            s.match = s.pattern.regex.find(s.input)?.takeIf { it.range.first == 0 }
            ret(s.match != null)
        }
        method("find", "()Z") {
            val s = m()
            if (s.searchFrom > s.input.length) { s.match = null; ret(false); return@method }
            s.match = s.pattern.regex.find(s.input, s.searchFrom)
            s.match?.let { s.searchFrom = if (it.range.isEmpty()) it.range.first + 1 else it.range.last + 1 }
            ret(s.match != null)
        }
        method("find", "(I)Z") {
            val s = m()
            s.match = s.pattern.regex.find(s.input, i(1))
            s.match?.let { s.searchFrom = if (it.range.isEmpty()) it.range.first + 1 else it.range.last + 1 }
            ret(s.match != null)
        }
        method("group", "()Ljava/lang/String;") { retRef(current().value) }
        method("group", "(I)Ljava/lang/String;") { retRef(current().groups[i(1)]?.value) }
        method("group", "(Ljava/lang/String;)Ljava/lang/String;") { retRef((current().groups as? MatchNamedGroupCollection)?.get(r(1) as String)?.value) }
        method("groupCount", "()I") { ret(m().match?.groupValues?.size?.minus(1) ?: groupCountOf(m().pattern.source)) }
        method("start", "()I") { ret(current().range.first) }
        method("end", "()I") { ret(current().range.last + 1) }
        method("start", "(I)I") { ret(if (i(1) == 0) current().range.first else groupRange(current(), i(1))?.first ?: -1) }
        method("end", "(I)I") { ret(if (i(1) == 0) current().range.last + 1 else groupRange(current(), i(1))?.let { it.last + 1 } ?: -1) }
        method("hitEnd", "()Z") { ret(m().match?.let { it.range.last + 1 >= m().input.length } ?: true) }
        method("reset", "()Ljava/util/regex/Matcher;") { val s = m(); s.match = null; s.searchFrom = 0; s.appendPosition = 0; retRef(r(0)) }
        method("reset", "(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;") {
            val s = m(); s.input = vm.charSequence(r(1)); s.match = null; s.searchFrom = 0; s.appendPosition = 0; retRef(r(0))
        }
        method("usePattern", "(Ljava/util/regex/Pattern;)Ljava/util/regex/Matcher;") { m().pattern = (r(1) as VmObject).native as PatternState; retRef(r(0)) }
        method("replaceAll", "(Ljava/lang/String;)Ljava/lang/String;") { val s = m(); retRef(s.pattern.regex.replace(s.input, r(1) as String)) }
        method("replaceFirst", "(Ljava/lang/String;)Ljava/lang/String;") { val s = m(); retRef(s.pattern.regex.replaceFirst(s.input, r(1) as String)) }
        method("pattern", "()Ljava/util/regex/Pattern;") { val p = vm.newVmObject("java/util/regex/Pattern"); p.native = m().pattern; retRef(p) }
    }
    define("java/util/regex/PatternSyntaxException") {
        superName = "java/lang/IllegalArgumentException"
        ctor("(Ljava/lang/String;Ljava/lang/String;I)V") { self().native = ThrowableState(r(1) as String?) }
    }
}

private fun Vm.pattern(source: String, flags: Int): VmObject {
    val state = try {
        PatternState(source, flags).also { it.regex }
    } catch (e: IllegalArgumentException) {
        throwVm("java/util/regex/PatternSyntaxException", e.message)
    }
    val p = newVmObject("java/util/regex/Pattern")
    p.native = state
    return p
}

/** Capturing groups in a pattern that has not matched yet: unescaped `(` not followed by `?`. */
private fun groupCountOf(source: String): Int {
    var n = 0
    var i = 0
    var inClass = false
    while (i < source.length) {
        val c = source[i]
        when {
            c == '\\' -> i++
            c == '[' -> inClass = true
            c == ']' -> inClass = false
            c == '(' && !inClass && (i + 1 >= source.length || source[i + 1] != '?' ||
                (i + 2 < source.length && source[i + 2] == '<' && i + 3 < source.length && source[i + 3] != '=' && source[i + 3] != '!')) -> n++
        }
        i++
    }
    return n
}

private fun javaSplitRegex(r: Regex, s: String, limit: Int): List<String> {
    if (s.isEmpty()) return listOf("")
    val out = ArrayList<String>()
    var last = 0
    for (m in r.findAll(s)) {
        if (limit > 0 && out.size == limit - 1) break
        if (m.value.isEmpty() && m.range.first == 0) continue
        out.add(s.substring(last, m.range.first))
        last = m.range.last + 1
    }
    out.add(s.substring(last))
    if (limit == 0) while (out.size > 1 && out.last().isEmpty()) out.removeAt(out.size - 1)
    return out
}

internal expect fun groupRange(match: MatchResult, group: Int): IntRange?
