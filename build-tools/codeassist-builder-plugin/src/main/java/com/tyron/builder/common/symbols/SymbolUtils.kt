@file: JvmName("SymbolUtils")

package com.tyron.builder.common.symbols

import com.android.ide.common.symbols.Symbol
import com.android.ide.common.symbols.SymbolTable
import com.android.ide.common.symbols.SymbolTableBuilder
import com.android.resources.ResourceType
import com.google.common.base.CharMatcher
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.Writer

/** Helper methods related to Symbols and resource processing. */

private val NORMALIZED_VALUE_NAME_CHARS =
    CharMatcher.anyOf(".:").precomputed()

/**
 * Updates the value resource name to mimic aapt's behaviour - replaces all dots and colons with
 * underscores.
 *
 * <p>If the name contains whitespaces or other illegal characters, they are not checked in this
 * method, but caught in the Symbol constructor call to {@link
 * Symbol#createAndValidateSymbol(ResourceType, String, SymbolJavaType, String, List)}.
 *
 * @param name the resource name to be updated
 * @return a valid resource name
 */
fun canonicalizeValueResourceName(name: String): String =
    NORMALIZED_VALUE_NAME_CHARS.replaceFrom(name, '_')

private val VALUE_ID_SPLITTER = Splitter.on(',').trimResults()

fun valueStringToInt(valueString: String) =
    if (valueString.startsWith("0x")) {
        Integer.parseUnsignedInt(valueString.substring(2), 16)
    } else {
        Integer.parseInt(valueString)
    }
fun parseArrayLiteral(size: Int, valuesString: String): ImmutableList<Int> {
    if (size == 0) {
        if (valuesString.subSequence(1, valuesString.length - 1).isNotBlank()) {
            failParseArrayLiteral(size, valuesString)
        }
        return ImmutableList.of()
    }
    val ints = ImmutableList.builder<Int>()

    val values = VALUE_ID_SPLITTER.split(
        valuesString.subSequence(
            1,
            valuesString.length - 1
        )
    ).iterator()
    for (i in 0 until size) {
        if (!values.hasNext()) {
            failParseArrayLiteral(size, valuesString)
        }
        val value = values.next()
        // Starting S, android attrs might be unstable and in that case instead of a value we will
        // have a reference to the android.R here instead (e.g. android.R.attr.lStar). In that case
        // just parse it as a zero, and then re-create when writing the R.jar (the name matches the
        // child exactly, e.g. a child attr "android:foo" will reference android.R.attr.foo).
        if (value.startsWith("android")) {
            ints.add(0)
        } else {
            ints.add(valueStringToInt(value))
        }
    }
    if (values.hasNext()) {
        failParseArrayLiteral(size, valuesString)
    }

    return ints.build()
}

fun failParseArrayLiteral(size: Int, valuesString: String): Nothing {
    throw IOException("""Values string $valuesString should have $size item(s).""")
}

/**
 * A visitor to process symbols in a lightweight way.
 *
 * Calls should only be made in the sequence exactly once.
 * [visit] ([symbol] ([child])*)* [visitEnd]
 */
interface SymbolListVisitor {
    fun visit()
    fun symbol(resourceType: CharSequence, name: CharSequence)
    /** Visit a child of a styleable symbol, only ever called after styleable symbols. */
    fun child(name: CharSequence)

    fun visitEnd()
}

/**
 * Read a symbol table from [lines] and generate events for the given [visitor].
 */
@Throws(IOException::class)
fun readAarRTxt(lines: Iterator<String>, visitor: com.android.ide.common.symbols.SymbolListVisitor) {

    visitor.visit()
    // When a styleable parent is encountered,
    // consume any children if the line starts with
    var styleableChildPrefix: String? = null
    while (lines.hasNext()) {
        val line = lines.next()
        if (styleableChildPrefix != null && line.startsWith(styleableChildPrefix)) {
            // Extract the child name and write it to the same line.
            val start = styleableChildPrefix.length + 1
            val end = line.indexOf(' ', styleableChildPrefix.length)
            if (end != -1) {
                visitor.child(line.substring(start, end))
            }
            continue
        }

        // Ignore out-of-order styleable children
        if (line.startsWith("int styleable ")) {
            continue
        }
        //          start     middle          end
        //            |         |              |
        //      "int[] styleable AppCompatTheme {750,75..."

        // Allows the symbol list with package name writer to only keep the type and the name,
        // so the example becomes "styleable AppCompatTheme <child> <child>"
        val start = line.indexOf(' ') + 1
        if (start == 0) {
            continue
        }
        val middle = line.indexOf(' ', start) + 1
        if (middle == 0) {
            continue
        }
        val end = line.indexOf(' ', middle) + 1
        if (end == 0) {
            continue
        }
        visitor.symbol(line.subSequence(start, middle - 1), line.subSequence(middle, end - 1))
        if (line.startsWith("int[] ")) {
            styleableChildPrefix = "int styleable " + line.substring(middle, end - 1)
        } else {
            styleableChildPrefix = null
        }
    }
    visitor.visitEnd()
}

/** Generate events of an empty symbol table for the given [visitor] */
fun visitEmptySymbolTable(visitor: SymbolListVisitor) {
    visitor.visit()
    visitor.visitEnd()
}

/**
 * Writes symbols in the AGP internal 'Symbol list with package name' format.
 *
 * This collapses the styleable children so the subsequent lines have the format
 * `"<type> <canonical_name>[ <child>[ <child>[ ...]]]"`
 *
 * See [SymbolIo.writeSymbolListWithPackageName] for use.
 *
 * @param packageName The package name for the project.
 *                    If not null, it will be written as the first line of output.
 * @param writer The writer to write the resulting symbol table with package name to.
 */
class SymbolListWithPackageNameWriter(
    private val packageName: String?,
    private val writer: Writer
) : SymbolListVisitor,
    Closeable {

    override fun visit() {
        packageName?.let { writer.append(it) }
    }

    override fun symbol(resourceType: CharSequence, name: CharSequence) {
        writer.append('\n')
        writer.append(resourceType)
        writer.append(' ')
        writer.append(name)
    }

    override fun child(name: CharSequence) {
        writer.append(' ')
        writer.append(name)
    }

    override fun visitEnd() {
        writer.append('\n')
    }

    override fun close() {
        writer.close()
    }
}


/**
 * Collects symbols in an in-memory SymbolTable.
 *
 * @param packageName The package for the symbol table
 *
 */
class SymbolTableBuilder(packageName: String) : SymbolListVisitor {
    private val symbolTableBuilder: SymbolTable.Builder =
        SymbolTable.builder().tablePackage(packageName)

    private var currentStyleable: String? = null
    private var children = ImmutableList.builder<String>()

    private var _symbolTable: SymbolTable? = null

    /**
     * The collected symbols.
     * Will throw [IllegalStateException] if called before the symbol table has been visited.
     */
    val symbolTable: SymbolTable
        get() = _symbolTable
            ?: throw IllegalStateException("Must finish visit before getting table.")

    override fun visit() {
    }

    override fun symbol(resourceType: CharSequence, name: CharSequence) {
        symbol(ResourceType.fromClassName(resourceType.toString())!!, name.toString())
    }

    private fun writeCurrentStyleable() {
        currentStyleable?.let {
            symbolTableBuilder.add(Symbol.styleableSymbol(it, ImmutableList.of(), children.build()))
            currentStyleable = null
            children = ImmutableList.builder()
        }
    }

    private fun symbol(resourceType: ResourceType, name: String) {
        writeCurrentStyleable()
        when (resourceType) {
            ResourceType.STYLEABLE -> currentStyleable = name
            ResourceType.ATTR -> symbolTableBuilder.add(Symbol.attributeSymbol(name, 0))
            else -> symbolTableBuilder.add(Symbol.normalSymbol(resourceType, name, 0))
        }
    }

    override fun child(name: CharSequence) {
        children.add(name.toString())
    }

    override fun visitEnd() {
        writeCurrentStyleable()
        _symbolTable = symbolTableBuilder.build()
    }
}


fun rTxtToSymbolTable(inputStream: InputStream, packageName: String): com.android.ide.common.symbols.SymbolTable {
    val symbolTableBuilder = SymbolTableBuilder(packageName)
    inputStream.bufferedReader().use {
        readAarRTxt(it.lines().iterator(), symbolTableBuilder)
    }
    return symbolTableBuilder.symbolTable
}