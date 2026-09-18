package dev.ide.kotlin.classfile

/**
 * Turns the name indices in Kotlin metadata into strings.
 *
 * Nothing in the protobuf is a name: everything is an index. WHICH table those indices point into depends on
 * where the metadata came from, and that is the whole reason this is an interface. A class file's
 * `@kotlin.Metadata` carries an instruction table alongside a string array ([JvmNameResolver]); a
 * `.kotlin_builtins` fragment carries a plain string table and a table of qualified names
 * ([BuiltInsNameResolver]). Everything ABOVE the indices — the declarations, the types, the flags — is
 * identical, so the decoder takes this and neither format has to know the other exists.
 */
interface NameResolver {

    /** The string at [index], as the format's own table spells it (slashes between package parts). */
    fun getString(index: Int): String

    /** A class name as Kotlin spells it: dots between packages, dots between nested names. */
    fun getClassName(index: Int): String
}
