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
) {
    fun withProjection(value: String): TypeName =
        if (value == projection) this else TypeName(qualifiedName, typeArguments, isTypeParameter, value)

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

/** One member of a type, as bytecode describes it. */
class JavaSymbol(
    val name: String,
    val kind: SymbolKind,
    val type: TypeName?,
    val modifiers: Set<Modifier>,
    /** The display string a completion list shows, built from the ERASED descriptor. */
    val signature: String?,
    val typeParameters: List<String>,
    val typeParameterBounds: List<TypeName>,
    val paramTypes: List<TypeName?>,
    /** Empty unless the class carries real names; never filled with `p0`, `p1`. */
    val paramNames: List<String>,
    val declaringClassFqn: String?,
    val isDeprecated: Boolean,
    /** The index of the vararg parameter, or -1. A vararg absorbs trailing positional arguments. */
    val varargParamIndex: Int,
) {
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
    val members: List<JavaSymbol>,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val isFinal: Boolean,
)
