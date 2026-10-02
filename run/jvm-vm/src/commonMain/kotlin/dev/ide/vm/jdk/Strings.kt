package dev.ide.vm.jdk

import dev.ide.vm.*
import dev.ide.vm.Call
import dev.ide.vm.ClassDefs
import dev.ide.vm.NativeClassBuilder
import dev.ide.vm.Vm
import dev.ide.vm.VmObject
import dev.ide.vm.VmRefArray
import dev.ide.vm.define
import dev.ide.vm.javaDoubleToString
import dev.ide.vm.javaFloatToString
import dev.ide.vm.javaStringHash

/** The text of any `CharSequence`: a [String] as is, anything else through its `toString()`. */
internal fun Vm.charSequence(v: Any?): String = when (v) {
    null -> throwVm("java/lang/NullPointerException", "CharSequence is null")
    is String -> v
    is VmObject -> (v.native as? StringBuilder)?.toString() ?: vmToString(v)
    else -> vmToString(v)
}

private fun Call.str(n: Int): String = r(n) as String

internal fun ClassDefs.registerStrings() {
    define("java/lang/CharSequence") {
        asInterface()
        method("isEmpty", "()Z") { ret(vm.callVirtual(r(0), "length", "()I") as Int == 0) }
    }

    define("java/lang/String") {
        valueBacked()
        implements("java/io/Serializable", "java/lang/Comparable", "java/lang/CharSequence", "java/lang/constant/Constable")
        ctor("()V") { retRef("") }
        ctor("(Ljava/lang/String;)V") { retRef(str(1)) }
        ctor("([C)V") { retRef((r(1) as CharArray).concatToString()) }
        ctor("([CII)V") { val a = r(1) as CharArray; retRef(a.concatToString(i(2), i(2) + i(3))) }
        ctor("([B)V") { retRef((r(1) as ByteArray).decodeToString()) }
        ctor("([BII)V") { val a = r(1) as ByteArray; retRef(a.decodeToString(i(2), i(2) + i(3))) }
        ctor("([BLjava/nio/charset/Charset;)V") { retRef((r(1) as ByteArray).decodeToString()) }
        ctor("([BIILjava/nio/charset/Charset;)V") { val a = r(1) as ByteArray; retRef(a.decodeToString(i(2), i(2) + i(3))) }
        ctor("(Ljava/lang/StringBuilder;)V") { retRef(vm.charSequence(r(1))) }
        ctor("([III)V") {
            val cps = r(1) as IntArray
            val sb = StringBuilder()
            for (k in i(2) until i(2) + i(3)) appendCodePoint(sb, cps[k])
            retRef(sb.toString())
        }
        method("length", "()I") { ret(str(0).length) }
        method("isEmpty", "()Z") { ret(str(0).isEmpty()) }
        method("isBlank", "()Z") { ret(str(0).isBlank()) }
        method("charAt", "(I)C") {
            val s = str(0); val k = i(1)
            if (k < 0 || k >= s.length) vm.throwVm("java/lang/StringIndexOutOfBoundsException", "index $k, length ${s.length}")
            ret(s[k])
        }
        method("codePointAt", "(I)I") { ret(codePointAt(str(0), i(1))) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is String && str(0) == r(1)) }
        method("equalsIgnoreCase", "(Ljava/lang/String;)Z") { ret(str(0).equals(r(1) as String?, ignoreCase = true)) }
        method("contentEquals", "(Ljava/lang/CharSequence;)Z") { ret(str(0) == vm.charSequence(r(1))) }
        method("hashCode", "()I") { ret(javaStringHash(str(0))) }
        method("toString", "()Ljava/lang/String;") { retRef(str(0)) }
        method("compareTo", "(Ljava/lang/String;)I") { ret(str(0).compareTo(str(1))) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret(str(0).compareTo(str(1))) }
        method("compareToIgnoreCase", "(Ljava/lang/String;)I") { ret(str(0).compareTo(str(1), ignoreCase = true)) }
        method("indexOf", "(I)I") { ret(indexOfCodePoint(str(0), i(1), 0)) }
        method("indexOf", "(II)I") { ret(indexOfCodePoint(str(0), i(1), i(2))) }
        method("indexOf", "(Ljava/lang/String;)I") { ret(str(0).indexOf(str(1))) }
        method("indexOf", "(Ljava/lang/String;I)I") { ret(str(0).indexOf(str(1), i(2).coerceAtLeast(0))) }
        method("lastIndexOf", "(I)I") { ret(str(0).lastIndexOf(i(1).toChar())) }
        method("lastIndexOf", "(II)I") { ret(str(0).lastIndexOf(i(1).toChar(), i(2))) }
        method("lastIndexOf", "(Ljava/lang/String;)I") { ret(str(0).lastIndexOf(str(1))) }
        method("lastIndexOf", "(Ljava/lang/String;I)I") { ret(if (i(2) < 0) -1 else str(0).lastIndexOf(str(1), i(2))) }
        method("substring", "(I)Ljava/lang/String;") { retRef(substring(vm, str(0), i(1), str(0).length)) }
        method("substring", "(II)Ljava/lang/String;") { retRef(substring(vm, str(0), i(1), i(2))) }
        method("subSequence", "(II)Ljava/lang/CharSequence;") { retRef(substring(vm, str(0), i(1), i(2))) }
        method("startsWith", "(Ljava/lang/String;)Z") { ret(str(0).startsWith(str(1))) }
        method("startsWith", "(Ljava/lang/String;I)Z") { ret(i(2) >= 0 && str(0).startsWith(str(1), i(2))) }
        method("endsWith", "(Ljava/lang/String;)Z") { ret(str(0).endsWith(str(1))) }
        method("contains", "(Ljava/lang/CharSequence;)Z") { ret(str(0).contains(vm.charSequence(r(1)))) }
        method("concat", "(Ljava/lang/String;)Ljava/lang/String;") { retRef(str(0) + str(1)) }
        method("replace", "(CC)Ljava/lang/String;") { retRef(str(0).replace(c(1), c(2))) }
        method("replace", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;") {
            retRef(str(0).replace(vm.charSequence(r(1)), vm.charSequence(r(2))))
        }
        method("replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") {
            retRef(Regex(str(1)).replace(str(0), javaReplacement(str(2))))
        }
        method("replaceFirst", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") {
            retRef(Regex(str(1)).replaceFirst(str(0), javaReplacement(str(2))))
        }
        method("matches", "(Ljava/lang/String;)Z") { ret(Regex(str(1)).matches(str(0))) }
        method("split", "(Ljava/lang/String;)[Ljava/lang/String;") { retRef(vm.stringArray(javaSplit(str(0), str(1), 0))) }
        method("split", "(Ljava/lang/String;I)[Ljava/lang/String;") { retRef(vm.stringArray(javaSplit(str(0), str(1), i(2)))) }
        method("toLowerCase", "()Ljava/lang/String;") { retRef(str(0).lowercase()) }
        method("toLowerCase", "(Ljava/util/Locale;)Ljava/lang/String;") { retRef(str(0).lowercase()) }
        method("toUpperCase", "()Ljava/lang/String;") { retRef(str(0).uppercase()) }
        method("toUpperCase", "(Ljava/util/Locale;)Ljava/lang/String;") { retRef(str(0).uppercase()) }
        method("trim", "()Ljava/lang/String;") { retRef(str(0).trim { it <= ' ' }) }
        method("strip", "()Ljava/lang/String;") { retRef(str(0).trim()) }
        method("stripLeading", "()Ljava/lang/String;") { retRef(str(0).trimStart()) }
        method("stripTrailing", "()Ljava/lang/String;") { retRef(str(0).trimEnd()) }
        method("repeat", "(I)Ljava/lang/String;") { retRef(str(0).repeat(i(1))) }
        method("intern", "()Ljava/lang/String;") { retRef(vm.intern(str(0))) }
        method("toCharArray", "()[C") { retRef(str(0).toCharArray()) }
        method("getChars", "(II[CI)V") { str(0).toCharArray(r(3) as CharArray, i(4), i(1), i(2)) }
        method("getBytes", "()[B") { retRef(str(0).encodeToByteArray()) }
        method("getBytes", "(Ljava/nio/charset/Charset;)[B") { retRef(str(0).encodeToByteArray()) }
        method("getBytes", "(Ljava/lang/String;)[B") { retRef(str(0).encodeToByteArray()) }
        method("regionMatches", "(ILjava/lang/String;II)Z") { ret(str(0).regionMatches(i(1), str(2), i(3), i(4))) }
        method("regionMatches", "(ZILjava/lang/String;II)Z") { ret(str(0).regionMatches(i(2), str(3), i(4), i(5), z(1))) }
        method("codePoints", "()Ljava/util/stream/IntStream;") { throw dev.ide.vm.VmUnsupportedException("String.codePoints (streams)") }
        static("valueOf", "(Ljava/lang/Object;)Ljava/lang/String;") { retRef(vm.vmToString(r(0))) }
        static("valueOf", "(I)Ljava/lang/String;") { retRef(i(0).toString()) }
        static("valueOf", "(J)Ljava/lang/String;") { retRef(l(0).toString()) }
        static("valueOf", "(Z)Ljava/lang/String;") { retRef(z(0).toString()) }
        static("valueOf", "(C)Ljava/lang/String;") { retRef(c(0).toString()) }
        static("valueOf", "(F)Ljava/lang/String;") { retRef(javaFloatToString(f(0))) }
        static("valueOf", "(D)Ljava/lang/String;") { retRef(javaDoubleToString(d(0))) }
        static("valueOf", "([C)Ljava/lang/String;") { retRef((r(0) as CharArray).concatToString()) }
        static("valueOf", "([CII)Ljava/lang/String;") { val a = r(0) as CharArray; retRef(a.concatToString(i(1), i(1) + i(2))) }
        static("copyValueOf", "([C)Ljava/lang/String;") { retRef((r(0) as CharArray).concatToString()) }
        static("join", "(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;") {
            val sep = vm.charSequence(r(0))
            retRef((r(1) as VmRefArray).data.joinToString(sep) { vm.vmToString(it) })
        }
        static("join", "(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;") {
            val sep = vm.charSequence(r(0))
            retRef(vm.elementsOf(r(1)).joinToString(sep) { vm.vmToString(it) })
        }
        static("format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;") {
            retRef(vm.javaFormat(str(0), (r(1) as VmRefArray?)?.data ?: emptyArray()))
        }
        static("format", "(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;") {
            retRef(vm.javaFormat(str(1), (r(2) as VmRefArray?)?.data ?: emptyArray()))
        }
    }

    define("java/lang/StringBuilder") { stringBuilder("java/lang/StringBuilder") }
    define("java/lang/StringBuffer") { stringBuilder("java/lang/StringBuffer") }

    define("java/lang/Character") {
        valueBacked()
        implements("java/io/Serializable", "java/lang/Comparable")
        staticField("TYPE", "Ljava/lang/Class;")
        onInit { it.setStaticRef("TYPE", "Ljava/lang/Class;", vm.mirror(vm.primitiveClass('C'))) }
        ctor("(C)V") { retRef(c(1)) }
        method("charValue", "()C") { ret(r(0) as Char) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is Char && r(0) == r(1)) }
        method("hashCode", "()I") { ret((r(0) as Char).code) }
        method("toString", "()Ljava/lang/String;") { retRef((r(0) as Char).toString()) }
        method("compareTo", "(Ljava/lang/Character;)I") { ret((r(0) as Char) - (r(1) as Char)) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret((r(0) as Char) - (r(1) as Char)) }
        static("valueOf", "(C)Ljava/lang/Character;") { retRef(c(0)) }
        static("toString", "(C)Ljava/lang/String;") { retRef(c(0).toString()) }
        static("toString", "(I)Ljava/lang/String;") { retRef(StringBuilder().also { appendCodePoint(it, i(0)) }.toString()) }
        static("hashCode", "(C)I") { ret(c(0).code) }
        static("compare", "(CC)I") { ret(c(0) - c(1)) }
        static("isDigit", "(C)Z") { ret(c(0).isDigit()) }
        static("isDigit", "(I)Z") { ret(i(0) in 0..0xFFFF && i(0).toChar().isDigit()) }
        static("isLetter", "(C)Z") { ret(c(0).isLetter()) }
        static("isLetter", "(I)Z") { ret(i(0) in 0..0xFFFF && i(0).toChar().isLetter()) }
        static("isLetterOrDigit", "(C)Z") { ret(c(0).isLetterOrDigit()) }
        static("isLetterOrDigit", "(I)Z") { ret(i(0) in 0..0xFFFF && i(0).toChar().isLetterOrDigit()) }
        static("isAlphabetic", "(I)Z") { ret(i(0) in 0..0xFFFF && i(0).toChar().isLetter()) }
        static("isWhitespace", "(C)Z") { ret(javaWhitespace(c(0).code)) }
        static("isWhitespace", "(I)Z") { ret(javaWhitespace(i(0))) }
        static("isSpaceChar", "(C)Z") { ret(c(0) == ' ' || c(0) == ' ' || c(0).category.let { it == CharCategory.SPACE_SEPARATOR || it == CharCategory.LINE_SEPARATOR || it == CharCategory.PARAGRAPH_SEPARATOR }) }
        static("isUpperCase", "(C)Z") { ret(c(0).isUpperCase()) }
        static("isUpperCase", "(I)Z") { ret(i(0) in 0..0xFFFF && i(0).toChar().isUpperCase()) }
        static("isLowerCase", "(C)Z") { ret(c(0).isLowerCase()) }
        static("isLowerCase", "(I)Z") { ret(i(0) in 0..0xFFFF && i(0).toChar().isLowerCase()) }
        static("isISOControl", "(C)Z") { ret(c(0).isISOControl()) }
        static("isISOControl", "(I)Z") { ret(i(0) in 0..0x1F || i(0) in 0x7F..0x9F) }
        static("isHighSurrogate", "(C)Z") { ret(c(0).isHighSurrogate()) }
        static("isLowSurrogate", "(C)Z") { ret(c(0).isLowSurrogate()) }
        static("isSurrogate", "(C)Z") { ret(c(0).isSurrogate()) }
        static("isSurrogatePair", "(CC)Z") { ret(c(0).isHighSurrogate() && c(1).isLowSurrogate()) }
        static("isSupplementaryCodePoint", "(I)Z") { ret(i(0) in 0x10000..0x10FFFF) }
        static("isBmpCodePoint", "(I)Z") { ret(i(0) ushr 16 == 0) }
        static("isValidCodePoint", "(I)Z") { ret(i(0) in 0..0x10FFFF) }
        static("isJavaIdentifierStart", "(C)Z") { ret(c(0).isLetter() || c(0) == '_' || c(0) == '$') }
        static("isJavaIdentifierPart", "(C)Z") { ret(c(0).isLetterOrDigit() || c(0) == '_' || c(0) == '$') }
        static("isTitleCase", "(C)Z") { ret(c(0).isTitleCase()) }
        static("isDefined", "(C)Z") { ret(c(0).category != CharCategory.UNASSIGNED) }
        static("toUpperCase", "(C)C") { ret(c(0).uppercaseChar()) }
        static("toUpperCase", "(I)I") { ret(if (i(0) in 0..0xFFFF) i(0).toChar().uppercaseChar().code else i(0)) }
        static("toLowerCase", "(C)C") { ret(c(0).lowercaseChar()) }
        static("toLowerCase", "(I)I") { ret(if (i(0) in 0..0xFFFF) i(0).toChar().lowercaseChar().code else i(0)) }
        static("toTitleCase", "(C)C") { ret(c(0).titlecaseChar()) }
        static("digit", "(CI)I") { ret(c(0).digitToIntOrNull(i(1)) ?: -1) }
        static("digit", "(II)I") { ret(if (i(0) in 0..0xFFFF) i(0).toChar().digitToIntOrNull(i(1)) ?: -1 else -1) }
        static("forDigit", "(II)C") { val d = i(0); val radix = i(1); ret(if (d < 0 || d >= radix) 0.toChar() else d.digitToChar(radix).lowercaseChar()) }
        static("getNumericValue", "(C)I") { ret(c(0).digitToIntOrNull(36) ?: -1) }
        static("charCount", "(I)I") { ret(if (i(0) >= 0x10000) 2 else 1) }
        static("toCodePoint", "(CC)I") { ret(((c(0).code - 0xD800) shl 10) + (c(1).code - 0xDC00) + 0x10000) }
        static("highSurrogate", "(I)C") { ret(((i(0) ushr 10) + (0xD800 - (0x10000 ushr 10))).toChar()) }
        static("lowSurrogate", "(I)C") { ret(((i(0) and 0x3FF) + 0xDC00).toChar()) }
        static("codePointAt", "(Ljava/lang/CharSequence;I)I") { ret(codePointAt(vm.charSequence(r(0)), i(1))) }
        static("codePointAt", "([CI)I") { ret(codePointAt((r(0) as CharArray).concatToString(), i(1))) }
        static("toChars", "(I)[C") { retRef(StringBuilder().also { appendCodePoint(it, i(0)) }.toString().toCharArray()) }
        static("getType", "(C)I") { ret(c(0).category.ordinalJava()) }
        static("getType", "(I)I") { ret(if (i(0) in 0..0xFFFF) i(0).toChar().category.ordinalJava() else 0) }
        static("reverseBytes", "(C)C") { val v = c(0).code; ret((((v and 0xFF) shl 8) or (v ushr 8)).toChar()) }
    }
}

private fun NativeClassBuilder.stringBuilder(self: String) {
    implements("java/io/Serializable", "java/lang/Comparable", "java/lang/CharSequence", "java/lang/Appendable")
    val ret = "L$self;"
    fun Call.sb(): StringBuilder = self().native as StringBuilder
    ctor("()V") { self().native = StringBuilder() }
    ctor("(I)V") { self().native = StringBuilder(i(1).coerceAtLeast(0)) }
    ctor("(Ljava/lang/String;)V") { self().native = StringBuilder(r(1) as String) }
    ctor("(Ljava/lang/CharSequence;)V") { self().native = StringBuilder(vm.charSequence(r(1))) }
    for (owner in listOf(ret, "Ljava/lang/Appendable;", "Ljava/lang/AbstractStringBuilder;")) {
        method("append", "(Ljava/lang/CharSequence;)$owner") { sb().append(if (r(1) == null) "null" else vm.charSequence(r(1))); retRef(r(0)) }
        method("append", "(Ljava/lang/CharSequence;II)$owner") { sb().append(if (r(1) == null) "null" else vm.charSequence(r(1)), i(2), i(3)); retRef(r(0)) }
        method("append", "(C)$owner") { sb().append(c(1)); retRef(r(0)) }
    }
    method("append", "(Ljava/lang/String;)$ret") { sb().append((r(1) as String?) ?: "null"); retRef(r(0)) }
    method("append", "(Ljava/lang/Object;)$ret") { sb().append(vm.vmToString(r(1))); retRef(r(0)) }
    method("append", "(Ljava/lang/StringBuffer;)$ret") { sb().append(if (r(1) == null) "null" else vm.charSequence(r(1))); retRef(r(0)) }
    method("append", "([C)$ret") { sb().append((r(1) as CharArray).concatToString()); retRef(r(0)) }
    method("append", "([CII)$ret") { val a = r(1) as CharArray; sb().append(a.concatToString(i(2), i(2) + i(3))); retRef(r(0)) }
    method("append", "(Z)$ret") { sb().append(z(1)); retRef(r(0)) }
    method("append", "(I)$ret") { sb().append(i(1)); retRef(r(0)) }
    method("append", "(J)$ret") { sb().append(l(1)); retRef(r(0)) }
    method("append", "(F)$ret") { sb().append(javaFloatToString(f(1))); retRef(r(0)) }
    method("append", "(D)$ret") { sb().append(javaDoubleToString(d(1))); retRef(r(0)) }
    method("appendCodePoint", "(I)$ret") { appendCodePoint(sb(), i(1)); retRef(r(0)) }
    method("toString", "()Ljava/lang/String;") { retRef(sb().toString()) }
    method("length", "()I") { ret(sb().length) }
    method("capacity", "()I") { ret(sb().length + 16) }
    method("isEmpty", "()Z") { ret(sb().isEmpty()) }
    method("charAt", "(I)C") {
        val b = sb(); val k = i(1)
        if (k < 0 || k >= b.length) vm.throwVm("java/lang/StringIndexOutOfBoundsException", "index $k,length ${b.length}")
        ret(b[k])
    }
    method("setCharAt", "(IC)V") { sb()[i(1)] = c(2) }
    method("setLength", "(I)V") {
        val b = sb(); val n = i(1)
        if (n < b.length) b.setLength(n) else while (b.length < n) b.append('\u0000')
    }
    method("ensureCapacity", "(I)V") {}
    method("trimToSize", "()V") {}
    method("insert", "(ILjava/lang/String;)$ret") { sb().insert(i(1), (r(2) as String?) ?: "null"); retRef(r(0)) }
    method("insert", "(ILjava/lang/Object;)$ret") { sb().insert(i(1), vm.vmToString(r(2))); retRef(r(0)) }
    method("insert", "(ILjava/lang/CharSequence;)$ret") { sb().insert(i(1), vm.charSequence(r(2))); retRef(r(0)) }
    method("insert", "(IC)$ret") { sb().insert(i(1), c(2)); retRef(r(0)) }
    method("insert", "(II)$ret") { sb().insert(i(1), i(2).toString()); retRef(r(0)) }
    method("insert", "(IJ)$ret") { sb().insert(i(1), l(2).toString()); retRef(r(0)) }
    method("insert", "(IZ)$ret") { sb().insert(i(1), z(2).toString()); retRef(r(0)) }
    method("insert", "(I[C)$ret") { sb().insert(i(1), (r(2) as CharArray).concatToString()); retRef(r(0)) }
    method("reverse", "()$ret") { val s = sb().reverse().toString(); sb().setLength(0); sb().append(s); retRef(r(0)) }
    method("deleteCharAt", "(I)$ret") { sb().deleteAt(i(1)); retRef(r(0)) }
    method("delete", "(II)$ret") { val b = sb(); b.deleteRange(i(1), i(2).coerceAtMost(b.length)); retRef(r(0)) }
    method("replace", "(IILjava/lang/String;)$ret") { val b = sb(); b.setRange(i(1), i(2).coerceAtMost(b.length), r(3) as String); retRef(r(0)) }
    method("indexOf", "(Ljava/lang/String;)I") { ret(sb().indexOf(r(1) as String)) }
    method("indexOf", "(Ljava/lang/String;I)I") { ret(sb().indexOf(r(1) as String, i(2))) }
    method("lastIndexOf", "(Ljava/lang/String;)I") { ret(sb().lastIndexOf(r(1) as String)) }
    method("lastIndexOf", "(Ljava/lang/String;I)I") { ret(sb().lastIndexOf(r(1) as String, i(2))) }
    method("substring", "(I)Ljava/lang/String;") { retRef(sb().substring(i(1))) }
    method("substring", "(II)Ljava/lang/String;") { retRef(sb().substring(i(1), i(2))) }
    method("subSequence", "(II)Ljava/lang/CharSequence;") { retRef(sb().substring(i(1), i(2))) }
    method("getChars", "(II[CI)V") { sb().toString().toCharArray(r(3) as CharArray, i(4), i(1), i(2)) }
    method("codePointAt", "(I)I") { ret(codePointAt(sb().toString(), i(1))) }
    method("compareTo", "(L$self;)I") { ret(sb().toString().compareTo(vm.charSequence(r(1)))) }
    method("compareTo", "(Ljava/lang/Object;)I") { ret(sb().toString().compareTo(vm.charSequence(r(1)))) }
}

private fun substring(vm: Vm, s: String, begin: Int, end: Int): String {
    if (begin < 0 || end > s.length || begin > end) {
        vm.throwVm("java/lang/StringIndexOutOfBoundsException", "begin $begin, end $end, length ${s.length}")
    }
    return s.substring(begin, end)
}

private fun indexOfCodePoint(s: String, cp: Int, from: Int): Int {
    if (cp < 0x10000) return s.indexOf(cp.toChar(), from.coerceAtLeast(0))
    val sb = StringBuilder()
    appendCodePoint(sb, cp)
    return s.indexOf(sb.toString(), from.coerceAtLeast(0))
}

internal fun appendCodePoint(sb: StringBuilder, cp: Int) {
    if (cp < 0x10000) sb.append(cp.toChar())
    else {
        val v = cp - 0x10000
        sb.append((0xD800 + (v ushr 10)).toChar())
        sb.append((0xDC00 + (v and 0x3FF)).toChar())
    }
}

private fun codePointAt(s: String, index: Int): Int {
    val high = s[index]
    if (high.isHighSurrogate() && index + 1 < s.length && s[index + 1].isLowSurrogate()) {
        return ((high.code - 0xD800) shl 10) + (s[index + 1].code - 0xDC00) + 0x10000
    }
    return high.code
}

private fun javaWhitespace(cp: Int): Boolean = when (cp) {
    0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F -> true
    0x00A0, 0x2007, 0x202F -> false
    else -> cp in 0..0xFFFF && cp.toChar().category.let {
        it == CharCategory.SPACE_SEPARATOR || it == CharCategory.LINE_SEPARATOR || it == CharCategory.PARAGRAPH_SEPARATOR
    }
}

/** `java.lang.Character.getType` numbering, which is not Kotlin's [CharCategory] order. */
private fun CharCategory.ordinalJava(): Int = when (this) {
    CharCategory.UNASSIGNED -> 0
    CharCategory.UPPERCASE_LETTER -> 1
    CharCategory.LOWERCASE_LETTER -> 2
    CharCategory.TITLECASE_LETTER -> 3
    CharCategory.MODIFIER_LETTER -> 4
    CharCategory.OTHER_LETTER -> 5
    CharCategory.NON_SPACING_MARK -> 6
    CharCategory.ENCLOSING_MARK -> 7
    CharCategory.COMBINING_SPACING_MARK -> 8
    CharCategory.DECIMAL_DIGIT_NUMBER -> 9
    CharCategory.LETTER_NUMBER -> 10
    CharCategory.OTHER_NUMBER -> 11
    CharCategory.SPACE_SEPARATOR -> 12
    CharCategory.LINE_SEPARATOR -> 13
    CharCategory.PARAGRAPH_SEPARATOR -> 14
    CharCategory.CONTROL -> 15
    CharCategory.FORMAT -> 16
    CharCategory.PRIVATE_USE -> 18
    CharCategory.SURROGATE -> 19
    CharCategory.DASH_PUNCTUATION -> 20
    CharCategory.START_PUNCTUATION -> 21
    CharCategory.END_PUNCTUATION -> 22
    CharCategory.CONNECTOR_PUNCTUATION -> 23
    CharCategory.OTHER_PUNCTUATION -> 24
    CharCategory.MATH_SYMBOL -> 25
    CharCategory.CURRENCY_SYMBOL -> 26
    CharCategory.MODIFIER_SYMBOL -> 27
    CharCategory.OTHER_SYMBOL -> 28
    CharCategory.INITIAL_QUOTE_PUNCTUATION -> 29
    CharCategory.FINAL_QUOTE_PUNCTUATION -> 30
}

/** Java's replacement syntax (`$1`, `\$`) matches Kotlin's on every platform Kotlin's [Regex] runs on. */
private fun javaReplacement(s: String): String = s

/** `String.split`: a limit of 0 drops trailing empty strings; a leading empty string from a zero-width match is dropped. */
private fun javaSplit(s: String, regex: String, limit: Int): List<String> {
    if (s.isEmpty()) return listOf("")
    val parts: MutableList<String> = if (regex.length == 1 && ".$|()[{^?*+\\".indexOf(regex[0]) < 0) {
        s.split(regex[0], limit = if (limit > 0) limit else 0).toMutableList()
    } else {
        val r = Regex(regex)
        val out = ArrayList<String>()
        var last = 0
        for (m in r.findAll(s)) {
            if (limit > 0 && out.size == limit - 1) break
            if (m.range.last + 1 == 0 && m.value.isEmpty()) continue
            if (m.value.isEmpty() && m.range.first == 0) continue
            out.add(s.substring(last, m.range.first))
            last = m.range.last + 1
        }
        out.add(s.substring(last))
        out
    }
    if (limit == 0) while (parts.size > 1 && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
    if (limit == 0 && parts.size == 1 && parts[0].isEmpty() && s.isNotEmpty()) return emptyList()
    return parts
}

internal fun Vm.stringArray(items: List<String>): VmRefArray = newRefArray("java/lang/String", items.toTypedArray<Any?>())

/** `String.format` for the conversions UI code uses: `%s %d %x %X %o %f %e %g %c %b %% %n`, flags and widths. */
internal fun Vm.javaFormat(format: String, args: Array<Any?>): String {
    val out = StringBuilder()
    var argIndex = 0
    var i = 0
    while (i < format.length) {
        val ch = format[i]
        if (ch != '%') { out.append(ch); i++; continue }
        var j = i + 1
        var explicit = -1
        val start = j
        while (j < format.length && format[j].isDigit()) j++
        if (j < format.length && format[j] == '$' && j > start) { explicit = format.substring(start, j).toInt() - 1; j++ } else j = start
        val flagsStart = j
        while (j < format.length && format[j] in "-#+ 0,(") j++
        val flags = format.substring(flagsStart, j)
        val widthStart = j
        while (j < format.length && format[j].isDigit()) j++
        val width = if (j > widthStart) format.substring(widthStart, j).toInt() else -1
        var precision = -1
        if (j < format.length && format[j] == '.') {
            j++
            val ps = j
            while (j < format.length && format[j].isDigit()) j++
            precision = format.substring(ps, j).toInt()
        }
        val conv = format[j]
        j++
        val text: String = when (conv) {
            '%' -> "%"
            'n' -> "\n"
            else -> {
                val arg = args.getOrNull(if (explicit >= 0) explicit else argIndex++)
                when (conv) {
                    's', 'S' -> vmToString(arg).let { if (precision >= 0) it.take(precision) else it }.let { if (conv == 'S') it.uppercase() else it }
                    'd' -> formatInteger(arg, flags)
                    'x', 'X' -> (when (arg) { is Long -> arg.toULong().toString(16); is Int -> arg.toUInt().toString(16); else -> vmToString(arg) }).let { if (conv == 'X') it.uppercase() else it }
                    'o' -> when (arg) { is Long -> arg.toULong().toString(8); is Int -> arg.toUInt().toString(8); else -> vmToString(arg) }
                    'c' -> when (arg) { is Char -> arg.toString(); is Int -> StringBuilder().also { appendCodePoint(it, arg) }.toString(); else -> vmToString(arg) }
                    'b', 'B' -> (if (arg is Boolean) arg else arg != null).toString()
                    'f' -> formatFixed((arg as? Number)?.toDouble() ?: 0.0, if (precision < 0) 6 else precision, flags)
                    'e', 'E' -> formatScientific((arg as? Number)?.toDouble() ?: 0.0, if (precision < 0) 6 else precision).let { if (conv == 'E') it.uppercase() else it }
                    'g', 'G' -> formatFixed((arg as? Number)?.toDouble() ?: 0.0, if (precision < 0) 6 else precision, flags)
                    else -> throw dev.ide.vm.VmUnsupportedException("String.format conversion %$conv")
                }
            }
        }
        val padded = if (width > text.length) {
            if ('-' in flags) text.padEnd(width) else if ('0' in flags && conv in "dxXof") {
                if (text.startsWith("-")) "-" + text.substring(1).padStart(width - 1, '0') else text.padStart(width, '0')
            } else text.padStart(width)
        } else text
        out.append(padded)
        i = j
    }
    return out.toString()
}

private fun formatInteger(arg: Any?, flags: String): String {
    val v = when (arg) { is Number -> arg.toLong(); is Char -> arg.code.toLong(); else -> 0L }
    var s = v.toString()
    if (',' in flags) {
        val neg = s.startsWith("-")
        val digits = if (neg) s.substring(1) else s
        s = (if (neg) "-" else "") + digits.reversed().chunked(3).joinToString(",").reversed()
    }
    if ('+' in flags && v >= 0) s = "+$s"
    return s
}

internal fun formatFixed(v: Double, precision: Int, flags: String = ""): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    val neg = v < 0 || (v == 0.0 && 1.0 / v < 0)
    val a = kotlin.math.abs(v)
    var scale = 1.0
    repeat(precision) { scale *= 10.0 }
    val rounded = kotlin.math.round(a * scale)
    val whole = (rounded / scale).toLong().let { if ((rounded / scale) < it) it - 1 else it }
    var frac = (rounded - whole * scale).toLong()
    if (frac < 0) frac = 0
    val fracText = if (precision == 0) "" else "." + frac.toString().padStart(precision, '0').take(precision)
    var wholeText = whole.toString()
    if (',' in flags) wholeText = wholeText.reversed().chunked(3).joinToString(",").reversed()
    val body = wholeText + fracText
    return (if (neg && (whole != 0L || frac != 0L)) "-" else if ('+' in flags) "+" else "") + body
}

private fun formatScientific(v: Double, precision: Int): String {
    if (v == 0.0) return formatFixed(0.0, precision) + "e+00"
    var exp = 0
    var m = kotlin.math.abs(v)
    while (m >= 10.0) { m /= 10.0; exp++ }
    while (m < 1.0) { m *= 10.0; exp-- }
    val sign = if (v < 0) "-" else ""
    return sign + formatFixed(m, precision) + "e" + (if (exp < 0) "-" else "+") + kotlin.math.abs(exp).toString().padStart(2, '0')
}
