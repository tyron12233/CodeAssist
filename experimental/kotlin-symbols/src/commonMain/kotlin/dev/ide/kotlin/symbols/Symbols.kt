package dev.ide.kotlin.symbols

/**
 * The neutral symbol model, as much of it as reading bytecode fills in.
 *
 * A PORT, not a design. Every name and every default here is taken from `:language-api`'s `Resolve.kt` and
 * `:lang-kotlin-index`'s `Model.kt`, because the point of this module is to find out what the real thing
 * costs to move, and a model that quietly improved on the original would answer a different question.
 *
 * What is missing is what bytecode cannot say: the resolution context a type uses to walk its supertypes,
 * the DOM node a source symbol points at, and the thirty-odd fields of `KotlinSymbol` that only the source
 * analyzer ever sets. Those are not part of this port, and leaving them out is what makes it measurable.
 */
enum class SymbolKind {
    PACKAGE, CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD,
    METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT,
    LOCAL_VARIABLE, PARAMETER, TYPE_PARAMETER,
}

enum class Modifier { PUBLIC, PROTECTED, PRIVATE, STATIC, FINAL, ABSTRACT, DEFAULT, SYNCHRONIZED }

/**
 * A resolved type name, with arguments and a use-site projection.
 *
 * The stand-in for `KotlinType`, minus the `KotlinTypeContext` that lets one walk its own supertypes. That
 * context is a callback into the symbol service and is the thing that makes the real type cyclic; nothing a
 * class file says needs it, so nothing here carries it.
 */
class TypeName(
    val qualifiedName: String,
    val typeArguments: List<TypeName> = emptyList(),
    val isTypeParameter: Boolean = false,
    /** `out`, `in`, `*` or empty, as this type appears in an argument position. */
    val projection: String = "",
    val nullable: Boolean = false,
    /**
     * A `kotlin.FunctionN` that is a RECEIVER function type (`T.() -> R`).
     *
     * The only thing distinguishing it from `(T) -> R`, which is the same `Function1`. It reaches the
     * metadata as an annotation ON the type, not as a flag.
     */
    val isExtensionFunctionType: Boolean = false,
    /** A `@Composable` function type: a Compose content slot. Also an annotation on the type. */
    val isComposable: Boolean = false,
) {
    fun withProjection(value: String): TypeName =
        if (value == projection) {
            this
        } else {
            TypeName(
                qualifiedName, typeArguments, isTypeParameter, value,
                nullable, isExtensionFunctionType, isComposable,
            )
        }

    /** The form the diff compares. Deliberately total: every field that a port could get wrong is in it. */
    fun render(): String = buildString {
        if (projection == "*") {
            append('*')
        } else {
            if (projection.isNotEmpty()) append(projection).append(' ')
            append(qualifiedName)
            if (typeArguments.isNotEmpty()) {
                append(typeArguments.joinToString(", ", "<", ">") { it.render() })
            }
        }
    }

    override fun toString(): String = render()
}

/**
 * One declaration, from either source of truth.
 *
 * ONE type for both, as in the original: bytecode fills some fields and `@Metadata` fills others, and
 * nothing above this level is supposed to be able to tell which a symbol came from. Splitting it in two
 * would have made the port neater and the comparison meaningless.
 */
class Symbol(
    val name: String,
    val kind: SymbolKind,
    val type: TypeName? = null,
    val owner: Symbol? = null,
    val modifiers: Set<Modifier> = emptySet(),
    /** The display string a completion list shows. */
    val signature: String? = null,
    val typeParameters: List<String> = emptyList(),
    val typeParameterBounds: List<TypeName> = emptyList(),
    /**
     * When a type parameter's upper bound is a SIBLING parameter (`fun <R, T : R>`), that parameter's
     * name, positional with [typeParameters]; null when the bound is concrete or absent.
     */
    val typeParamBoundNames: List<String?> = emptyList(),
    val paramTypes: List<TypeName?> = emptyList(),
    /** Empty when the source does not carry real names; never filled with `p0`, `p1`. */
    val paramNames: List<String> = emptyList(),
    /** Whether each parameter declares a default. Empty means UNKNOWN, which is not the same as none. */
    val paramHasDefault: List<Boolean> = emptyList(),
    /** The receiver type's FQN when this is an extension; null otherwise. */
    val receiverTypeFqn: String? = null,
    val receiverTypeArgs: List<TypeName> = emptyList(),
    /** When the receiver IS a bare type parameter (`fun <T> T.also()`), its name. */
    val receiverTypeParam: String? = null,
    val declaringClassFqn: String? = null,
    val isInternal: Boolean = false,
    val isComposable: Boolean = false,
    val isInline: Boolean = false,
    val isInfix: Boolean = false,
    val isSuspend: Boolean = false,
    val isDeprecated: Boolean = false,
    /** The index of the vararg parameter, or -1. A vararg absorbs trailing positional arguments. */
    val varargParamIndex: Int = -1,
) {
    val isExtension: Boolean get() = receiverTypeFqn != null

    override fun toString(): String = "$kind $name${signature.orEmpty()}"
}

/**
 * The bytecode shape of a classpath type: its own generics, its supertypes, and its members.
 *
 * Generics come from the generic SIGNATURE attribute rather than the erased descriptor, which is what lets a
 * resolver bind `List.of("s")` to `List<String>` and propagate `String` through `stream()`. A member with no
 * signature attribute falls back to the descriptor, so arity is still known for overload selection.
 */
class JavaShape(
    val typeParameters: List<String>,
    val typeParameterBounds: List<TypeName>,
    val superTypes: List<TypeName>,
    val members: List<Symbol>,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val isFinal: Boolean,
)
