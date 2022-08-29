package com.tyron.builder.common.symbols

import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableTable
import com.google.common.collect.Interner
import com.google.common.collect.Lists
import com.google.common.collect.Maps
import com.google.common.collect.Table
import com.google.common.collect.Tables
import com.android.SdkConstants
import com.android.resources.ResourceType
import com.android.resources.ResourceVisibility
import java.io.File
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import javax.annotation.concurrent.Immutable
import javax.lang.model.SourceVersion

/**
 * List of symbols identifying resources in an Android application. Symbol tables do not only exist
 * for applications: they can be used for other building blocks for applications, such as libraries
 * or atoms.
 *
 * A symbol table keeps a list of instances of [Symbol], each one with a unique pair class
 * / name. Tables have one main attribute: a package name. This should be unique and are used to
 * generate the `R.java` file.
 */
private const val ANDROID_ATTR_PREFIX = "android_"
val EMPTY: SymbolTable = SymbolTable.builder().build()

@Immutable
abstract class SymbolTable protected constructor() {

    abstract val tablePackage: String
    abstract val symbols: ImmutableTable<ResourceType, String, Symbol>

    private data class SymbolTableImpl(
            override val tablePackage: String,
            override val symbols: ImmutableTable<ResourceType, String, Symbol>) : SymbolTable() {

        override fun toString(): String = "SymbolTable ($tablePackage)" +
                "\n  " + symbols.values().joinToString("\n  ")
    }

    /**
     * Produces a subset of this symbol table that has the symbols with resource type / name defined
     * in `filter`. In other words, a symbol `s` will exist in the result if and only
     * if `s` exists in `this` and there is a symbol `s1` in `table`
     * such that `s.resourceType == s1.resourceType && s.name == s1.name`.
     *
     * @param table the filter table
     * @return the filter result; this table will have the same name and package as this one
     */
    fun filter(table: SymbolTable): SymbolTable {
        val builder = ImmutableTable.builder<ResourceType, String, Symbol>()

        for (resourceType in table.symbols.rowKeySet()) {
            val symbols = table.symbols.row(resourceType).values
            val filteringSymbolNames = this.symbols.row(resourceType).keys

            for (symbol in symbols) {
                if (filteringSymbolNames.contains(symbol.canonicalName)) {
                    builder.put(
                        resourceType, symbol.canonicalName, this.symbols.get(resourceType, symbol.canonicalName))
                }
            }
        }

        return SymbolTableImpl(tablePackage, builder.build())
    }

    /**
     * Short for merging `this` and `m`.
     *
     * @param m the table to add to `this`
     * @return the result of merging `this` with `m`
     */
    fun merge(m: SymbolTable): SymbolTable {
        return merge(listOf(this, m))
    }

    /**
     * Builds a new symbol table that has the same symbols as this one, but was renamed with
     * the given package.
     *
     * @param tablePackage the table package
     * @return the new renamed symbol table
     */
    fun rename(tablePackage: String): SymbolTable {
        return SymbolTableImpl(tablePackage, symbols)
    }

    /**
     * Collect all the symbols for a particular symbol type to a sorted list of symbols.
     *
     * The symbols are sorted by name to make output predicable and, therefore, testing easier.
     */
    fun getSymbolByResourceType(type: ResourceType): List<Symbol> {
        val symbols = Lists.newArrayList(symbols.row(type).values)
        symbols.sortWith(compareBy { it.canonicalName })
        return Collections.unmodifiableList(symbols)
    }

    /**
     * Collect all the symbols for a particular resource visibility to a sorted list of symbols.
     *
     * The symbols are sorted by name to make the output predicable.
     */
    fun getSymbolByVisibility(visibility: ResourceVisibility): List<Symbol> {
        val symbols =
                Lists.newArrayList(
                        symbols.values().filter { it.resourceVisibility == visibility })
        symbols.sortWith(compareBy { it.canonicalName })
        return Collections.unmodifiableList(symbols)
    }

    /**
     * Checks if the table contains a resource with matching type and name.
     */
    fun containsSymbol(type: ResourceType, canonicalName: String): Boolean {
        var found = symbols.contains(type, canonicalName)
        if (!found && type == ResourceType.STYLEABLE && canonicalName.contains('_')) {
            // If the symbol is a styleable and contains the underscore character, it is very likely
            // that we're looking for a styleable child. These are stored under the parent's symbol,
            // so try finding the parent first and then the child under it. If we haven't found the
            // parent, then the child doesn't exist either.
            found = maybeGetStyleableParentSymbolForChild(canonicalName) != null
        }
        return found
    }

    /**
     * Tries to retrieve a declare-styleable's parent for the given child name. For example:
     * <pre>
     *     <declare-styleable name="s1">
     *         <item name="foo"/>
     *     </declare-styleable>
     * </pre>
     * Calling {@code containsStyleableSymbol("s1_foo")} would return the parent symbol, but calling
     * {@code containsStyleableSymbol("foo")} or {@code containsSymbol(STYLEABLE, "foo")} would
     * both return {@code null}.
     */
    fun maybeGetStyleableParentSymbolForChild(canonicalName: String, start: Int = 0):
            Symbol.StyleableSymbol? {
        var found: Symbol.StyleableSymbol? = null
        val index = canonicalName.indexOf('_', start)
        if (index > -1) {
            val parentName = canonicalName.substring(0, index)
            if (symbols.contains(ResourceType.STYLEABLE, parentName)) {
                var childName = canonicalName.substring(index + 1, canonicalName.length)
                val parent = symbols.get(ResourceType.STYLEABLE, parentName)
                found =
                        if (parent!!.children.any { it == childName })
                            parent as Symbol.StyleableSymbol
                        else
                            null // I still haven't found what I'm looking for~

                // styleable children of the format <parent>_android_<child> could have been either
                // declared as <item name="android_foo"/> or <item name="android:foo>.
                // If we didn't find the "android_" child, look for one in the "android:" namespace.
                if (found == null && childName.startsWith(ANDROID_ATTR_PREFIX)) {
                    childName =
                            SdkConstants.ANDROID_NS_NAME_PREFIX +
                                    childName.substring(ANDROID_ATTR_PREFIX.length)
                    found =
                            if (parent.children.any { it == childName })
                                parent as Symbol.StyleableSymbol
                            else null
                }
            }
            if (found == null) {
                found = maybeGetStyleableParentSymbolForChild(canonicalName, index + 1)
            }
        }
        return found
    }

    /**
     * Creates a table with all the symbols taken from [this] table, but the values are from
     * [mainSymbolTable]
     */
    fun withValuesFrom(mainSymbolTable: SymbolTable): SymbolTable
            = mainSymbolTable.filter(this).rename(this.tablePackage)

    /** [ResourceType]s present in the table. */
    val resourceTypes: Set<ResourceType> get() = symbols.rowKeySet()

    /**
     * Builder that creates a symbol table. Use [FastBuilder], if possible, instead of this class.
     */
    class Builder {

        private var tablePackage = ""

        private val symbols: Table<ResourceType, String, Symbol> =
                Tables.newCustomTable(Maps.newEnumMap(ResourceType::class.java), ::HashMap)

        /**
         * Adds a symbol to the table to be built. The table must not contain a symbol with the same
         * resource type and name.
         *
         * @param symbol the symbol to add
         */
        fun add(symbol: Symbol): Builder {
            if (symbols.contains(symbol.resourceType, symbol.canonicalName)) {
                throw IllegalArgumentException(
                        "Duplicate symbol in table with resource type '${symbol.resourceType}' " +
                                "and symbol name '${symbol.canonicalName}'")
            }
            symbols.put(symbol.resourceType, symbol.canonicalName, symbol)
            return this
        }

        /**
         * Adds all symbols in the given collection to the table. This is semantically equivalent
         * to calling [.add] for all symbols.
         *
         * @param symbols the symbols to add
         */
        fun addAll(symbols: Collection<Symbol>): Builder {
            symbols.forEach { this.add(it) }
            return this
        }

        /**
         * Adds all symbols in the given collection to the table - ignores the duplicates.
         */
        fun addAllIfNotExist(symbols: Collection<Symbol>): Builder {
            symbols.filter { !this.contains(it) }.forEach { this.add(it) }
            return this
        }

        /**
         * Adds a symbol if it doesn't exist in the table yet. If a symbol already exists, choose
         * the correct resource accessibility.
         *
         * @param table the other table to merge into the current symbol table.
         */
        internal fun addFromPartial(table: SymbolTable): Builder {
            table.symbols.values().forEach {
                if (!this.symbols.contains(it.resourceType, it.canonicalName)) {
                    // If this symbol hasn't been encountered yet, simply add it as is.
                    this.symbols.put(it.resourceType, it.canonicalName, it)
                } else {
                    val existing = this.symbols.get(it.resourceType, it.canonicalName)
                    // If we already encountered it, check the qualifiers.
                    // - if it's a styleable and visibilities don't conflict, merge them into one
                    //   with the highest visibility of the two
                    // - if they're the same, leave the existing one (the existing one overrode the
                    //   new one)
                    // - if the existing one is PRIVATE_XML_ONLY, use the new one (overriding
                    //   resource was defined as PRIVATE or PUBLIC)
                    // - if the new one is PRIVATE_XML_ONLY, leave the existing one (overridden
                    //   resource was defined as PRIVATE or PUBLIC)
                    // - if neither of them is PRIVATE_XML_ONLY and they differ, that's an error
                    if (existing!!.resourceVisibility != it.resourceVisibility
                            && existing.resourceVisibility != ResourceVisibility.PRIVATE_XML_ONLY
                            && it.resourceVisibility != ResourceVisibility.PRIVATE_XML_ONLY) {
                        // Conflicting visibilities.
                        throw IllegalResourceVisibilityException(
                                "Symbol with resource type ${it.resourceType} and name " +
                                        "${it.canonicalName} defined both as ${it.resourceVisibility} and " +
                                        "${existing.resourceVisibility}.")
                    }
                    if (it.resourceType == ResourceType.STYLEABLE) {
                        // Merge the styleables. Join the children and sort by name, do not keep
                        // duplicates.
                        it as Symbol.StyleableSymbol
                        existing as Symbol.StyleableSymbol

                        val children =
                                ImmutableList.copyOf(
                                        mutableSetOf<String>()
                                                .plus(it.children)
                                                .plus(existing.children)
                                                .sorted())
                        val visibility =
                                ResourceVisibility.max(
                                        it.resourceVisibility, existing.resourceVisibility)

                        this.symbols.remove(existing.resourceType, existing.canonicalName)
                        this.symbols.put(
                                it.resourceType,
                                it.canonicalName,
                                Symbol.styleableSymbol(
                                        it.canonicalName,
                                        ImmutableList.of(),
                                        children,
                                        visibility))
                    } else {
                        // We only need to replace the existing symbol with the new one if the
                        // visibilities differ and the new visibility is higher than the old one.
                        if (it.resourceVisibility > existing.resourceVisibility) {
                            this.symbols.remove(existing.resourceType, existing.canonicalName)
                            this.symbols.put(it.resourceType, it.canonicalName, it)
                        }
                    }

                }
            }
            return this
        }

        /**
         * Sets the table package. See `SymbolTable` description.
         *
         * @param tablePackage; must be a valid java package name
         */
        fun tablePackage(tablePackage: String): Builder {
            verifyTablePackage(tablePackage)
            this.tablePackage = tablePackage
            return this
        }

        /**
         * Checks if a symbol with the same resource type and name as `symbol` have been
         * added.
         *
         * @param symbol the symbol to check
         *
         * @return has a symbol with the same resource type / name been added?
         */
        operator fun contains(symbol: Symbol): Boolean {
            return contains(symbol.resourceType, symbol.canonicalName)
        }

        /**
         * Checks if the table contains a symbol with the given resource type / name.
         *
         * @param resourceType the resource type
         *
         * @param canonicalName the name
         *
         * @return does the table contain a symbol with the given resource type / name?
         */
        fun contains(resourceType: ResourceType, canonicalName: String): Boolean {
            return symbols.contains(resourceType, canonicalName)
        }

        /**
         * Returns the symbol form the table matching the provided symbol
         *
         * @param symbol the symbol
         */
        operator fun get(symbol: Symbol): Symbol? {
            return symbols.get(symbol.resourceType, symbol.canonicalName)
        }


        fun remove(resourceType: ResourceType, canonicalName: String): Symbol? {
            return symbols.remove(resourceType, canonicalName)
        }

        fun isEmpty(): Boolean = symbols.isEmpty

        /**
         * Builds a symbol table with all symbols added.
         *
         * @return the symbol table
         */
        fun build(): SymbolTable {
            return SymbolTableImpl(tablePackage,
                    ImmutableTable.copyOf(symbols))
        }
    }

    /**
     * A builder that creates a symbol table. Use this class instead of [Builder], if possible.
     */
    class FastBuilder(private val symbolInterner: Interner<Symbol>) {

        private var tablePackage = ""

        private val symbols: ImmutableTable.Builder<ResourceType, String, Symbol> =
            ImmutableTable.builder()

        /**
         * Adds a symbol to the table to be built. The table must not contain a symbol with the same
         * resource type and name.
         *
         * @param symbol the symbol to add
         */
        fun add(symbol: Symbol) {
            symbols.put(symbol.resourceType, symbol.canonicalName, symbolInterner.intern(symbol))
        }

        /**
         * Adds all symbols in the given collection to the table. This is semantically equivalent
         * to calling [.add] for all symbols.
         *
         * @param symbols the symbols to add
         */
        fun addAll(symbols: Collection<Symbol>) {
            symbols.forEach { this.add(it) }
        }

        /**
         * Sets the table package. See [SymbolTable] description.
         *
         * @param tablePackage; must be a valid java package name
         */
        fun tablePackage(tablePackage: String) {
            verifyTablePackage(tablePackage)
            this.tablePackage = tablePackage
        }

        /**
         * Builds a symbol table with all symbols added.
         *
         * @return the symbol table
         */
        fun build(): SymbolTable {
            return SymbolTableImpl(tablePackage, symbols.build())
        }
    }

    companion object {

        /**
         * Merges a list of tables into a single table. The merge is order-sensitive: when multiple
         * symbols with the same class / name exist in multiple tables, the first one will be used.
         *
         * @param tables the tables to merge
         *
         * @return the table with the result of the merge; this table will have the package of
         *  the first table in `tables`, or the default one if there are no tables in `tables`
         */
        @JvmStatic fun merge(tables: List<SymbolTable>): SymbolTable {
            if (tables.size == 1) {
                return tables.first()
            }
            val builder = ImmutableTable.builder<ResourceType, String, Symbol>()

            val present = HashSet<String>()

            for (resourceType in ResourceType.values()) {
                present.clear()
                for (t in tables) {
                    val tableSymbolMap = t.symbols.row(resourceType)
                    if (tableSymbolMap != null && !tableSymbolMap.isEmpty()) {
                        for (s in tableSymbolMap.values) {
                            val canonicalName = s.canonicalName
                            if (!present.contains(canonicalName)) {
                                present.add(canonicalName)
                                builder.put(resourceType, canonicalName, s)
                            }
                        }
                    }
                }
            }

            val packageName = if (tables.isEmpty()) "" else tables[0].tablePackage

            return SymbolTableImpl(packageName, builder.build())
        }

        /**
         * Merges a list of partial R files. See 'package-info.java' for a detailed description of
         * the merging algorithm.
         *
         * @param tables partial R files in oder of the source-sets relation (base first, overriding
         *  source-set afterwards etc).
         * @param packageName the package name for the merged symbol table.
         */
        @JvmStatic fun mergePartialTables(tables: List<File>, packageName: String): SymbolTable {
            val symbolIo = SymbolIo()
            val builder = SymbolTable.builder()
            builder.tablePackage(packageName)

            // A set to keep the names of the visited layout files.
            val visitedFiles = HashSet<String>()

            try {
                // Reverse the file list, since we have to start from the 'highest' source-set (base
                // source-set will be last).
                tables.reversed().forEach {
                    if (it.name.startsWith("layout")) {
                        // When a layout file is overridden, its' contents get overridden too. That
                        // is why we need to keep the 'highest' version of the file.
                        if (!visitedFiles.contains(it.name)) {
                            // If we haven't encountered a file with this name yet, remember it and
                            // process the partial R file.
                            visitedFiles.add(it.name)
                            builder.addFromPartial(symbolIo.readFromPartialRFile(it, null))
                        }
                    } else {
                        // Partial R files for values XML files and non-XML files need to be parsed
                        // always. The order matters for declare-styleables and for resource
                        // accessibility.
                        builder.addFromPartial(symbolIo.readFromPartialRFile(it, null))
                    }
                }
            } catch (e: Exception) {
                throw PartialRMergingException(
                        "An error occurred during merging of the partial R files", e)
            }


            return builder.build()
        }

        /**
         * Creates a new builder to create a `SymbolTable`.
         *
         * @return a builder
         */
        @JvmStatic fun builder(): Builder {
            return Builder()
        }
    }

    class IllegalResourceVisibilityException(description: String) : Exception(description)
}

/**
 * Verifies that tablePackage is valid. See [SymbolTable] description.
 */
private fun verifyTablePackage(tablePackage: String) {
    if (tablePackage.isNotEmpty() && !SourceVersion.isName(tablePackage)) {
        for (segment in Splitter.on('.').split(tablePackage)) {
            if (!SourceVersion.isIdentifier(segment)) {
                throw IllegalArgumentException(
                    "Package '$tablePackage' from AndroidManifest.xml is not a valid " +
                            "Java package name as '$segment' is not a valid Java " +
                            "identifier.")
            }
            if (SourceVersion.isKeyword(segment)) {
                throw IllegalArgumentException(
                    "Package '$tablePackage' from AndroidManifest.xml is not a valid " +
                            "Java package name as '$segment' is a Java keyword.")
            }
        }
        // Shouldn't happen.
        throw IllegalArgumentException(
            "Package '$tablePackage' from AndroidManifest.xml is not a valid Java " +
                    "package name.")
    }
}