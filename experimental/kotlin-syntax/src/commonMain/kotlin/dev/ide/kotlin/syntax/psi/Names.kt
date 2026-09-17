package dev.ide.kotlin.syntax.psi

/**
 * `Name` and `FqName`, the two wrappers the compiler's PSI hands back instead of strings.
 *
 * The facade returned plain strings at first, which is the better API and the wrong one. `importedFqName`,
 * `shortName`, `packageFqName` and `nameAsName` are read at about 130 call sites in the editor backend and
 * every one of them writes `.asString()` — so a string-returning facade turns an import swap into 130 hand
 * edits, each of which is a chance to change behaviour while the compiler stays quiet. These exist so those
 * call sites are untouched.
 *
 * Only what is actually read is here. Upstream `Name` also carries special names (`<init>`, `<anonymous>`),
 * which come from resolution rather than from the tree and so cannot arise on this side.
 */
class Name internal constructor(private val text: String) {

    fun asString(): String = text

    /** The name without backticks, which is what an identifier token's text already is here. */
    val identifier: String get() = text

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is Name && other.text == text

    override fun hashCode(): Int = text.hashCode()

    companion object {
        fun identifier(name: String): Name = Name(name)
    }
}

/** A dotted name. The root is the empty string, which is what a file with no package directive has. */
class FqName internal constructor(private val text: String) {

    fun asString(): String = text

    val isRoot: Boolean get() = text.isEmpty()

    fun shortName(): Name = Name(text.substringAfterLast('.'))

    fun parent(): FqName = if ('.' in text) FqName(text.substringBeforeLast('.')) else ROOT

    fun child(name: Name): FqName = if (isRoot) FqName(name.asString()) else FqName("$text.${name.asString()}")

    fun pathSegments(): List<Name> = if (isRoot) emptyList() else text.split('.').map { Name(it) }

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is FqName && other.text == text

    override fun hashCode(): Int = text.hashCode()

    companion object {
        val ROOT: FqName = FqName("")

        fun fromSegments(segments: List<String>): FqName = FqName(segments.joinToString("."))
    }
}

/**
 * Declaration-site variance, mirroring `org.jetbrains.kotlin.types.Variance`.
 *
 * It lives here rather than in a types package because the only thing on this side that has a variance is a
 * type parameter in the tree; there is no type system behind it.
 */
enum class Variance(val label: String) {
    INVARIANT(""),
    IN_VARIANCE("in"),
    OUT_VARIANCE("out"),
    ;

    override fun toString(): String = label
}
