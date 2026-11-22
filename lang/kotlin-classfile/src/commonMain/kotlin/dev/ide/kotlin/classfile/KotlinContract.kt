package dev.ide.kotlin.classfile

/** A constant a contract compares with or returns: `returns(true)`, `returns(null)`. */
enum class KotlinContractConstant { TRUE, FALSE, NULL }

/**
 * The condition on the right of `implies`, as metadata.proto's `Expression` records it.
 *
 * A parameter is named by its position: 0 is the receiver (`this`), 1 the first value parameter, and so on,
 * which is the compiler's own numbering.
 */
sealed class KotlinContractExpression {
    /** A Boolean parameter used as the condition (`returns() implies value`), or its negation. */
    class BooleanParameter(val parameter: Int, val negated: Boolean) : KotlinContractExpression()

    /** `x == null`, or, [negated], `x != null`. */
    class NullCheck(val parameter: Int, val negated: Boolean) : KotlinContractExpression()

    /**
     * `x is T` (`!is` when [negated]). [typeName] is a class FQN, or a type parameter's name when
     * [isTypeParameter] (`assertIs<T>(value)` says `value is T`).
     */
    class IsInstance(
        val parameter: Int,
        val typeName: String,
        val isTypeParameter: Boolean,
        val nullable: Boolean,
        val negated: Boolean,
    ) : KotlinContractExpression()

    class Constant(val value: KotlinContractConstant) : KotlinContractExpression()
    class And(val operands: List<KotlinContractExpression>) : KotlinContractExpression()
    class Or(val operands: List<KotlinContractExpression>) : KotlinContractExpression()
}

/**
 * One effect of a function's `contract { }`.
 *
 * [Kind.RETURNS] is `returns()` when [returns] is null and `returns(value)` otherwise; with
 * [Kind.RETURNS_NOT_NULL] it is `returnsNotNull()`. Either carries the [conclusion] it implies. A
 * [Kind.CALLS_IN_PLACE] effect names the lambda [parameter] and how many times it runs.
 */
class KotlinContractEffect(
    val kind: Kind,
    val returns: KotlinContractConstant? = null,
    val conclusion: KotlinContractExpression? = null,
    val parameter: Int = -1,
    val invocation: Invocation? = null,
) {
    enum class Kind { RETURNS, RETURNS_NOT_NULL, CALLS_IN_PLACE }

    /** The numbers are the protobuf enum's. Null on a `callsInPlace` that names no kind (UNKNOWN). */
    enum class Invocation { AT_MOST_ONCE, EXACTLY_ONCE, AT_LEAST_ONCE }
}

/**
 * A compact text form of a contract, for an index to store with a symbol and read back without the class
 * file. Effects are separated by `;`:
 *
 *  - `R>cond`, `Rt>cond`, `Rf>cond`, `Rn>cond`: `returns()`, `returns(true|false|null)` implies `cond`
 *  - `N>cond`: `returnsNotNull()` implies `cond`
 *  - `C<i><kind>`: `callsInPlace(param i, kind)`, kind one of `m` (at most once), `e` (exactly), `l` (at
 *    least), `u` (unknown)
 *
 * A condition is `p<i>` (Boolean parameter), `z<i>` (`== null`), `i<i>:<type>` (`is`; a leading `@` on
 * the type marks a type parameter, a trailing `?` a nullable type), `k<t|f|n>` (a constant), each optionally
 * preceded by `!`, or `&(a,b,…)` / `|(a,b,…)`.
 */
object KotlinContractCodec {
    fun encode(effects: List<KotlinContractEffect>): String = effects.joinToString(";") { e ->
        when (e.kind) {
            KotlinContractEffect.Kind.RETURNS -> "R" + (e.returns?.let(::constant) ?: "") + ">" + expr(e.conclusion)
            KotlinContractEffect.Kind.RETURNS_NOT_NULL -> "N>" + expr(e.conclusion)
            KotlinContractEffect.Kind.CALLS_IN_PLACE -> "C" + e.parameter + when (e.invocation) {
                KotlinContractEffect.Invocation.AT_MOST_ONCE -> "m"
                KotlinContractEffect.Invocation.EXACTLY_ONCE -> "e"
                KotlinContractEffect.Invocation.AT_LEAST_ONCE -> "l"
                null -> "u"
            }
        }
    }

    private fun constant(c: KotlinContractConstant) = when (c) {
        KotlinContractConstant.TRUE -> "t"
        KotlinContractConstant.FALSE -> "f"
        KotlinContractConstant.NULL -> "n"
    }

    private fun expr(e: KotlinContractExpression?): String = when (e) {
        null -> ""
        is KotlinContractExpression.BooleanParameter -> (if (e.negated) "!" else "") + "p" + e.parameter
        is KotlinContractExpression.NullCheck -> (if (e.negated) "!" else "") + "z" + e.parameter
        is KotlinContractExpression.IsInstance -> (if (e.negated) "!" else "") + "i" + e.parameter + ":" +
            (if (e.isTypeParameter) "@" else "") + e.typeName + (if (e.nullable) "?" else "")
        is KotlinContractExpression.Constant -> "k" + constant(e.value)
        is KotlinContractExpression.And -> e.operands.joinToString(",", "&(", ")") { expr(it) }
        is KotlinContractExpression.Or -> e.operands.joinToString(",", "|(", ")") { expr(it) }
    }

    /** The effects [text] encodes. Anything it cannot read is dropped, never guessed at. */
    fun decode(text: String?): List<KotlinContractEffect> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split(';').mapNotNull { runCatching { effect(it) }.getOrNull() }
    }

    private fun effect(s: String): KotlinContractEffect? {
        if (s.startsWith("C")) {
            val kind = when (s.last()) {
                'm' -> KotlinContractEffect.Invocation.AT_MOST_ONCE
                'e' -> KotlinContractEffect.Invocation.EXACTLY_ONCE
                'l' -> KotlinContractEffect.Invocation.AT_LEAST_ONCE
                else -> null
            }
            return KotlinContractEffect(
                KotlinContractEffect.Kind.CALLS_IN_PLACE,
                parameter = s.substring(1, s.length - 1).toInt(),
                invocation = kind,
            )
        }
        val arrow = s.indexOf('>')
        if (arrow < 0) return null
        val head = s.substring(0, arrow)
        val cursor = Cursor(s, arrow + 1)
        val conclusion = if (cursor.atEnd()) null else cursor.expression()
        return when (head) {
            "N" -> KotlinContractEffect(KotlinContractEffect.Kind.RETURNS_NOT_NULL, conclusion = conclusion)
            "R" -> KotlinContractEffect(KotlinContractEffect.Kind.RETURNS, null, conclusion)
            "Rt" -> KotlinContractEffect(KotlinContractEffect.Kind.RETURNS, KotlinContractConstant.TRUE, conclusion)
            "Rf" -> KotlinContractEffect(KotlinContractEffect.Kind.RETURNS, KotlinContractConstant.FALSE, conclusion)
            "Rn" -> KotlinContractEffect(KotlinContractEffect.Kind.RETURNS, KotlinContractConstant.NULL, conclusion)
            else -> null
        }
    }

    private class Cursor(val s: String, var i: Int) {
        fun atEnd() = i >= s.length

        fun expression(): KotlinContractExpression {
            val negated = s[i] == '!'
            if (negated) i++
            return when (val c = s[i++]) {
                '&', '|' -> {
                    check(s[i++] == '(')
                    val operands = ArrayList<KotlinContractExpression>()
                    while (true) {
                        operands += expression()
                        when (s[i++]) {
                            ',' -> continue
                            ')' -> break
                            else -> error("bad contract operand list")
                        }
                    }
                    if (c == '&') KotlinContractExpression.And(operands) else KotlinContractExpression.Or(operands)
                }

                'p' -> KotlinContractExpression.BooleanParameter(int(), negated)
                'z' -> KotlinContractExpression.NullCheck(int(), negated)
                'i' -> {
                    val parameter = int()
                    check(s[i++] == ':')
                    val start = i
                    while (i < s.length && s[i] != ',' && s[i] != ')') i++
                    var type = s.substring(start, i)
                    val isParam = type.startsWith("@")
                    if (isParam) type = type.substring(1)
                    val nullable = type.endsWith("?")
                    KotlinContractExpression.IsInstance(parameter, type.removeSuffix("?"), isParam, nullable, negated)
                }

                'k' -> KotlinContractExpression.Constant(
                    when (s[i++]) {
                        't' -> KotlinContractConstant.TRUE
                        'f' -> KotlinContractConstant.FALSE
                        else -> KotlinContractConstant.NULL
                    },
                )

                else -> error("bad contract expression")
            }
        }

        private fun int(): Int {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i).toInt()
        }
    }
}
