package dev.ide.kotlin.syntax.tree

/**
 * An immutable set of [IElementType]s, mirroring `com.intellij.psi.tree.TokenSet`.
 *
 * Membership is by identity, matching [IElementType]'s contract. The platform's version indexes a bit set by
 * a globally assigned type index; this one holds the types directly, which costs a hash lookup instead of a
 * bit test. That is the right trade here: the parser's hot path is [contains] over sets of a dozen entries,
 * and a portable implementation must not depend on a process-wide type registry.
 */
class TokenSet private constructor(private val types: Set<IElementType>) {

    operator fun contains(type: IElementType?): Boolean = type != null && type in types

    /** The members, for diagnostics and for building derived sets. */
    val elements: Set<IElementType> get() = types

    override fun toString(): String = types.joinToString(prefix = "TokenSet(", postfix = ")") { it.debugName }

    companion object {
        val EMPTY: TokenSet = TokenSet(emptySet())

        fun create(vararg types: IElementType): TokenSet =
            if (types.isEmpty()) EMPTY else TokenSet(types.toHashSet())

        fun orSet(vararg sets: TokenSet): TokenSet {
            val all = HashSet<IElementType>()
            for (set in sets) all.addAll(set.types)
            return if (all.isEmpty()) EMPTY else TokenSet(all)
        }

        fun andNot(from: TokenSet, remove: TokenSet): TokenSet {
            val all = HashSet(from.types)
            all.removeAll(remove.types)
            return if (all.isEmpty()) EMPTY else TokenSet(all)
        }
    }
}
