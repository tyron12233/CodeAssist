package com.tyron.builder.common.symbols;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.tyron.builder.common.io.NonClosingStreams;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.compiler.manifest.resources.ResourceVisibility;
import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.android.SdkConstants;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.ParserConfigurationException;

import org.gradle.util.internal.GFileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xml.sax.SAXException;

/**
 * Reads and writes symbol tables to files.
 *
 * <p>Instantiatable as has interning caches for symbols to reduce memory use.
 *
 * <pre>
 * AAR Format:
 *  - R.txt in AARs
 *     - Format is <type> <class> <name> <value>
 *     - Contains the resources for this library and all its transitive dependencies.
 *     - Written using writeForAar()
 *     - Only read as part of writeSymbolListWithPackageName() as there are corrupt files with
 *       styleable children not below their parents in the wild.
 *     - IDs and styleable children don't matter, as this is only used to filtering symbols when
 *       generating R classes.
 *  - public.txt in AARs
 *     - Format is <class> <name>
 *     - Contains all the resources from this AAR that are public (missing public.txt means all
 *       resources should be public)
 *     - There are no IDs or styleable children here, needs to be merged with R.txt and filtered by
 *       the public visibility to actually get a full list of public resources from the AAR
 *
 * AAPT2 Outputs the following formats:
 *  - R.txt as output by AAPT2, where ID values matter.
 *     - Format is <type> <class> <name> <value>
 *     - Read using readFromAapt().
 *     - This is what the R class is generated from.
 *  - Partial R file format.
 *     - Format is <access qualifier> <type> <class> <name>
 *     - Contains only the resources defined in a single source file.
 *     - Used to push R class generation earlier.
 *
 * Internal intermediates:
 *  - Symbol list with package name. Used to filter down the generated R class for this library in
 *    the non-namespaced case.
 *     - Format is <type> <name> [<child> [<child>, [...]]], with the first line as the package name.
 *     - Contains resources from this sub-project and all its transitive dependencies.
 *     - Read by readSymbolListWithPackageName()
 *     - Generated from AARs and AAPT2 symbol tables by writeSymbolListWithPackageName()
 *  - R def format,
 *     - Used for namespace backward compatibility.
 *     - Contains only the resources defined in a single library.
 *     - Has the package name as the first line.
 *     - May contain internal resource types (e.g. "maybe attributes" defined under declare
 *       styleable resources).
 *
 *  All files are written in UTF-8. R files use linux-type line separators, while R.java use system
 *  line separators.
 * </pre>
 */
public final class SymbolIo {

    public static final String ANDROID_ATTR_PREFIX = "android_";

    private final Interner<Symbol> symbolInterner;

    public SymbolIo() {
        this(Interners.newStrongInterner());
    }

    public SymbolIo(Interner<Symbol> symbolInterner) {
        this.symbolInterner = symbolInterner;
    }

    /**
     * Loads a symbol table from a symbol file created by aapt.
     *
     * @param file the symbol file
     * @param tablePackage the package name associated with the table
     * @return the table read
     * @throws IOException failed to read the table
     */
    @NotNull
    public static SymbolTable readFromAapt(@NotNull File file, @Nullable String tablePackage)
            throws Exception {
        return new SymbolIo().read(file, tablePackage, ReadConfiguration.AAPT);
    }

    /**
     * Loads a symbol table from a symbol file created by aapt, but ignores all the resource values.
     * It will also ignore any styleable children that are not under their parents.
     *
     * @param file the symbol file
     * @param tablePackage the package name associated with the table
     * @return the table read
     * @throws IOException failed to read the table
     */
    @NotNull
    public static SymbolTable readFromAaptNoValues(
            @NotNull File file, @Nullable String tablePackage) throws Exception {
        return new SymbolIo().read(file, tablePackage, ReadConfiguration.AAPT_NO_VALUES);
    }

    /**
     * Loads a symbol table from a symbol file created by aapt, but ignores all the resource values.
     * It will also ignore any styleable children that are not under their parents.
     *
     * @param reader the reader for reading the symbol file
     * @param filename the name of the symbol file for use in error messages
     * @param tablePackage the package name associated with the table
     * @return the table read
     * @throws IOException failed to read the table
     */
    @NotNull
    public static SymbolTable readFromAaptNoValues(
            @NotNull BufferedReader reader, @NotNull String filename, @Nullable String tablePackage)
            throws Exception {
        return new SymbolIo()
                .read(reader.lines(), filename, tablePackage, ReadConfiguration.AAPT_NO_VALUES);
    }

    /**
     * Loads a symbol table from a partial symbol file created by aapt during compilation.
     *
     * @param file the partial symbol file
     * @param tablePackage the package name associated with the table
     * @return the table read
     * @throws IOException failed to read the table
     */
    @NotNull
    public SymbolTable readFromPartialRFile(@NotNull File file, @Nullable String tablePackage)
            throws Exception {
        return read(file, tablePackage, ReadConfiguration.PARTIAL_FILE);
    }

    @NotNull
    public static SymbolTable readFromPublicTxtFile(
            @NotNull InputStream inputStream,
            @NotNull String fileName,
            @Nullable String tablePackage)
            throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return new SymbolIo()
                    .read(reader.lines(), fileName, tablePackage, ReadConfiguration.PUBLIC_FILE);
        }
    }

    @NotNull
    private SymbolTable read(
            @NotNull File file,
            @Nullable String tablePackage,
            @NotNull ReadConfiguration readConfiguration)
            throws Exception {
        String filename = file.getAbsolutePath();
        try (Stream<String> lines = Files.lines(file.toPath())) {
            return read(lines, filename, tablePackage, readConfiguration);
        }
    }

    @NotNull
    private SymbolTable read(
            @NotNull Stream<String> lines,
            @NotNull String filename,
            @Nullable String tablePackage,
            @NotNull ReadConfiguration readConfiguration)
            throws Exception {
        Iterator<String> linesIterator = lines.iterator();
        int startLine = checkFileTypeHeader(linesIterator, readConfiguration, filename);
        SymbolTable.FastBuilder table =
                new SymbolLineReader(
                                readConfiguration,
                                linesIterator,
                                filename,
                                symbolInterner,
                                startLine)
                        .readLines();
        if (tablePackage != null) {
            table.tablePackage(tablePackage);
        }
        try {
            return table.build();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Issue parsing symbol table from package '"
                            + tablePackage + "' at " + filename + ".\n"
                            + e.getMessage(), e);
        }
    }

    /**
     * Loads a symbol table from a synthetic namespaced symbol file.
     *
     * <p>See {@link #writeSymbolListWithPackageName(Path, Path, Path)} for format details.
     *
     * @param file the symbol file
     * @return the table read
     * @throws IOException failed to read the table
     */
    @NotNull
    public SymbolTable readSymbolListWithPackageName(@NotNull Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file, UTF_8)) {
            return readWithPackage(
                    lines, file.toString(), ReadConfiguration.SYMBOL_LIST_WITH_PACKAGE);
        }
    }

    /**
     * Loads a symbol table from an partial file.
     *
     * @param file the symbol file
     * @return the table read
     * @throws IOException failed to read the table
     */
    @NotNull
    public static SymbolTable readRDef(@NotNull Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file, UTF_8)) {
            return new SymbolIo().readWithPackage(lines, file.toString(), ReadConfiguration.R_DEF);
        }
    }

    /**
     * Loads a symbol table from a pre-processed AAR that contains an R-def.txt
     *
     * @param zipFile a zip containing R-def.txt
     * @return the table read
     * @throws IOException failed to read the table
     */
    @NotNull
    public static SymbolTable readRDefFromZip(@NotNull Path zipFile) throws IOException {
        try (ZipInputStream zip =
                new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            while (true) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    throw new IOException("Expected zip " + zipFile + " to contain R-def.txt");
                }
                if (!entry.getName().equals(SdkConstants.FN_R_DEF_TXT)) {
                    continue;
                }
                return readRDefFromInputStream(zipFile.toString() + "/!R-def.txt", zip);
            }
        }
    }

    /**
     * Loads a symbol table from the given input stream that contains an R-def.txt
     *
     * @param filePath the display name for the file
     * @param fileInputStream an input stream for the contents of the file. Will not be closed.
     * @return the table read
     * @throws IOException failed to read the table
     */
    @NotNull
    public static SymbolTable readRDefFromInputStream(
            @NotNull String filePath, @NotNull InputStream fileInputStream) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                NonClosingStreams.nonClosing(fileInputStream), UTF_8))) {
            return new SymbolIo()
                    .readWithPackage(reader.lines(), filePath, ReadConfiguration.R_DEF);
        }
    }

    @NotNull
    private SymbolTable readWithPackage(
            @NotNull Stream<String> lines,
            @NotNull String filePath,
            @NotNull ReadConfiguration readConfiguration)
            throws IOException {

        Iterator<String> linesIterator = lines.iterator();

        int startLine = checkFileTypeHeader(linesIterator, readConfiguration, filePath);
        if (!linesIterator.hasNext()) {
            throw new IOException(
                    "Internal error: Symbol file with package cannot be empty. File located at: "
                            + filePath);
        }
        String tablePackage = linesIterator.next().trim();
        SymbolTable.FastBuilder table =
                new SymbolLineReader(
                                readConfiguration,
                                linesIterator,
                                filePath,
                                symbolInterner,
                                startLine + 1)
                        .readLines();

        table.tablePackage(tablePackage);
        return table.build();
    }

    private static int checkFileTypeHeader(
            @NotNull Iterator<String> lines,
            @NotNull ReadConfiguration readConfiguration,
            @NotNull String filename)
            throws IOException {
        if (readConfiguration.fileTypeHeader == null) {
            return 1;
        }
        if (!lines.hasNext()) {
            throw new IOException(
                    "Internal Error: Invalid symbol file '"
                            + filename
                            + "', cannot be empty for type '"
                            + readConfiguration
                            + "'");
        }
        String firstLine = lines.next();
        if (!lines.hasNext() || !readConfiguration.fileTypeHeader.equals(firstLine)) {
            throw new IOException(
                    "Internal Error: Invalid symbol file '"
                            + filename
                            + "', first line is incorrect for type '"
                            + readConfiguration
                            + "'.\n Expected '"
                            + readConfiguration.fileTypeHeader
                            + "' but got '"
                            + firstLine
                            + "'");
        }
        return 2;
    }

    private static class SymbolLineReader {
        @NotNull private final SymbolTable.FastBuilder table;

        @NotNull private final Iterator<String> lines;
        @NotNull private final String filename;
        @NotNull private final ReadConfiguration readConfiguration;

        // Current line number and content
        private int currentLineNumber;
        @Nullable private String currentLineContent;

        // Reuse list to avoid allocations.
        private final List<SymbolData> aaptStyleableChildrenCache = new ArrayList<>(10);

        SymbolLineReader(
                @NotNull ReadConfiguration readConfiguration,
                @NotNull Iterator<String> lines,
                @NotNull String filename,
                @NotNull Interner<Symbol> symbolInterner,
                int startLine) {
            this.table = new SymbolTable.FastBuilder(symbolInterner);
            this.readConfiguration = readConfiguration;
            this.lines = lines;
            this.filename = filename;
            currentLineNumber = startLine - 1;
        }

        private void readNextLine() {
            if (!lines.hasNext()) {
                currentLineContent = null;
            } else {
                currentLineContent = lines.next();
                currentLineNumber++;
            }
        }

        @NotNull
        SymbolTable.FastBuilder readLines() throws IOException {
            if (!lines.hasNext()) {
                return table;
            }
            readNextLine();
            try {
                while (currentLineContent != null) {
                    SymbolData data = readConfiguration.parseLine(currentLineContent);
                    if (data.resourceType == ResourceType.STYLEABLE) {
                        switch (data.javaType) {
                            case INT:
                                if (readConfiguration.ignoreRogueChildren) {
                                    // If we're ignoring rogue children (styleable children that are
                                    // not under their parent), we can just read the next line and
                                    // continue.
                                    readNextLine();
                                    break;
                                } else {
                                    // If we're not ignoring rogue children, we need to error out.
                                    throw new IOException(
                                            "Unexpected styleable child " + currentLineContent);
                                }
                            case INT_LIST:
                                readNextLine();
                                handleStyleable(table, data);
                                // Already at the next line, as handleStyleable has to read forward
                                // to find its children.
                                break;
                        }
                    } else {
                        int value = 0;
                        if (readConfiguration.readValues) {
                            value = SymbolUtils.valueStringToInt(data.value);
                        }
                        String canonicalName =
                                readConfiguration.rawSymbolNames
                                        ? SymbolUtils.canonicalizeValueResourceName(data.name)
                                        : data.name;

                        if (data.resourceType == ResourceType.ATTR) {
                            table.add(
                                    Symbol.attributeSymbol(
                                            data.name,
                                            value,
                                            data.maybeDefinition,
                                            data.accessibility,
                                            canonicalName));
                        } else {
                            table.add(
                                    Symbol.normalSymbol(
                                            data.resourceType,
                                            data.name,
                                            value,
                                            data.accessibility,
                                            canonicalName));
                        }
                        readNextLine();
                    }
                }
            } catch (IndexOutOfBoundsException | IOException e) {
                throw new IOException(
                        String.format(
                                "File format error reading %1$s line %2$d: '%3$s'",
                                filename, currentLineNumber, currentLineContent),
                        e);
            }

            return table;
        }

        private void handleStyleable(
                @NotNull SymbolTable.FastBuilder table, @NotNull SymbolData data)
                throws IOException {
            if (readConfiguration.singleLineStyleable) {
                String canonicalName =
                        readConfiguration.rawSymbolNames
                                ? SymbolUtils.canonicalizeValueResourceName(data.name)
                                : data.name;
                table.add(
                        Symbol.styleableSymbol(
                                data.name,
                                ImmutableList.of(),
                                data.children,
                                data.accessibility,
                                canonicalName));
                return;
            }
            // Keep the current location to report if there is an error
            String styleableLineContent = currentLineContent;
            int styleableLineIndex = currentLineNumber;
            final String data_name = data.name + "_";
            aaptStyleableChildrenCache.clear();
            List<SymbolData> children = aaptStyleableChildrenCache;
            while (currentLineContent != null) {
                SymbolData subData = readConfiguration.parseLine(currentLineContent);
                if (subData.resourceType != ResourceType.STYLEABLE
                        || subData.javaType != SymbolJavaType.INT) {
                    break;
                }
                children.add(subData);
                readNextLine();
            }

            // Having the attrs in order only matters if the values matter.
            if (readConfiguration.readValues) {
                try {
                    children.sort(SYMBOL_DATA_VALUE_COMPARATOR);
                } catch (NumberFormatException e) {
                    // Report error from styleable parent.
                    currentLineContent = styleableLineContent;
                    currentLineNumber = styleableLineIndex;
                    throw new IOException(e);
                }
            }
            ImmutableList.Builder<String> builder = ImmutableList.builder();
            for (SymbolData aaptStyleableChild : children) {
                builder.add(computeItemName(data_name, aaptStyleableChild.name));
            }
            ImmutableList<String> childNames = builder.build();

            ImmutableList<Integer> values;
            if (readConfiguration.readValues) {
                try {
                    values = SymbolUtils.parseArrayLiteral(childNames.size(), data.value);
                } catch (NumberFormatException e) {
                    // Report error from styleable parent.
                    currentLineContent = styleableLineContent;
                    currentLineNumber = styleableLineIndex;
                    throw new IOException(
                            "Unable to parse array literal " + data.name + " = " + data.value, e);
                }
            } else {
                values = ImmutableList.of();
            }
            String canonicalName =
                    readConfiguration.rawSymbolNames
                            ? SymbolUtils.canonicalizeValueResourceName(data.name)
                            : data.name;
            table.add(
                    Symbol.styleableSymbol(
                            canonicalName, values, childNames, data.accessibility, data.name));
        }

        private static final Comparator<SymbolData> SYMBOL_DATA_VALUE_COMPARATOR =
                Comparator.comparingInt(o -> Integer.parseInt(o.value));
    }

    private static final class SymbolData {
        @NotNull final ResourceVisibility accessibility;
        @NotNull final ResourceType resourceType;
        @NotNull final String name;
        @NotNull final SymbolJavaType javaType;
        @NotNull final String value;
        @NotNull final ImmutableList<String> children;
        final boolean maybeDefinition;

        public SymbolData(
                @NotNull ResourceType resourceType,
                @NotNull String name,
                @NotNull SymbolJavaType javaType,
                @NotNull String value) {
            this.accessibility = ResourceVisibility.UNDEFINED;
            this.resourceType = resourceType;
            this.name = name;
            this.javaType = javaType;
            this.value = value;
            this.children = ImmutableList.of();
            this.maybeDefinition = false;
        }

        public SymbolData(
                @NotNull ResourceVisibility accessibility,
                @NotNull ResourceType resourceType,
                @NotNull String name,
                @NotNull SymbolJavaType javaType,
                @NotNull String value) {
            this.accessibility = accessibility;
            this.resourceType = resourceType;
            this.name = name;
            this.javaType = javaType;
            this.value = value;
            this.children = ImmutableList.of();
            this.maybeDefinition = false;
        }

        public SymbolData(@NotNull String name, @NotNull ImmutableList<String> children) {
            this.accessibility = ResourceVisibility.UNDEFINED;
            this.resourceType = ResourceType.STYLEABLE;
            this.name = name;
            this.javaType = SymbolJavaType.INT_LIST;
            this.value = "";
            this.children = children;
            this.maybeDefinition = false;
        }

        public SymbolData(@NotNull ResourceType resourceType, @NotNull String name) {
            this.accessibility = ResourceVisibility.UNDEFINED;
            this.resourceType = resourceType;
            this.name = name;
            this.javaType =
                    resourceType == ResourceType.STYLEABLE
                            ? SymbolJavaType.INT_LIST
                            : SymbolJavaType.INT;
            this.value = "";
            this.children = ImmutableList.of();
            this.maybeDefinition = false;
        }

        public SymbolData(@NotNull String name, boolean maybeDefinition) {
            this.accessibility = ResourceVisibility.UNDEFINED;
            this.name = name;
            this.javaType = SymbolJavaType.INT;
            this.resourceType = ResourceType.ATTR;
            this.value = "";
            this.children = ImmutableList.of();
            this.maybeDefinition = maybeDefinition;
        }
    }

    @NotNull
    private static SymbolData readAaptLine(@NotNull String line) throws IOException {
        // format is "<type> <class> <name> <value>"
        // don't want to split on space as value could contain spaces.
        int pos = line.indexOf(' ');
        String typeName = line.substring(0, pos);
        SymbolJavaType type = SymbolJavaType.getEnum(typeName);
        if (type == null) {
            throw new IOException("Invalid symbol type " + typeName);
        }

        int pos2 = line.indexOf(' ', pos + 1);
        String className = line.substring(pos + 1, pos2);

        ResourceType resourceType = ResourceType.fromClassName(className);
        if (resourceType == null) {
            throw new IOException("Invalid resource type " + className);
        }

        int pos3 = line.indexOf(' ', pos2 + 1);
        String name = line.substring(pos2 + 1, pos3);
        String value = line.substring(pos3 + 1).trim();

        return new SymbolData(resourceType, name, type, value);
    }

    @NotNull
    private static SymbolData readPartialRLine(@NotNull String line) throws IOException {
        // format is "<access qualifier> <type> <class> <name>"
        int pos = line.indexOf(' ');
        String accessName = line.substring(0, pos);
        ResourceVisibility accessibility = ResourceVisibility.getEnum(accessName);
        if (accessibility == null) {
            throw new IOException("Invalid resource access qualifier " + accessName);
        }

        int pos2 = line.indexOf(' ', pos + 1);
        String typeName = line.substring(pos + 1, pos2);
        SymbolJavaType type = SymbolJavaType.getEnum(typeName);
        if (type == null) {
            throw new IOException("Invalid symbol type " + typeName);
        }

        int pos3 = line.indexOf(' ', pos2 + 1);
        String className = line.substring(pos2 + 1, pos3);

        ResourceType resourceType = ResourceType.fromClassName(className);
        if (resourceType == null) {
            throw new IOException("Invalid resource type " + className);
        }

        String name = line.substring(pos3 + 1);

        return new SymbolData(accessibility, resourceType, name, type, "");
    }

    @NotNull
    private static SymbolData readPublicTxtLine(@NotNull String line) throws IOException {
        // format is "<class> <name>"
        int pos = line.indexOf(' ');
        String className = line.substring(0, pos);
        ResourceType resourceType = ResourceType.fromClassName(className);
        if (resourceType == null) {
            throw new IOException("Invalid resource type " + className);
        }
        // If it's a styleable it must be the parent. Styleable-children are only references to
        // attrs, if a child is to be public then the corresponding attr will be marked as public.
        // Styleable children (int styleable) should not be present in the public.txt.
        String typeName = resourceType == ResourceType.STYLEABLE ? "int[]" : "int";
        SymbolJavaType type = SymbolJavaType.getEnum(typeName);
        if (type == null) {
            throw new IOException("Invalid symbol type " + typeName);
        }

        String name = line.substring(pos + 1);
        return new SymbolData(ResourceVisibility.PUBLIC, resourceType, name, type, "");
    }

    @NotNull
    private static SymbolData readSymbolListWithPackageLine(@NotNull String line)
            throws IOException {
        // format is "<type> <name>[ <child>[ <child>[ ...]]]"
        int startPos = line.indexOf(' ');
        boolean maybeDefinition = false;
        String typeName = line.substring(0, startPos);
        ResourceType resourceType;
        if (typeName.equals("attr?")) {
            maybeDefinition = true;
            resourceType = ResourceType.ATTR;
        } else {
            resourceType = ResourceType.fromClassName(typeName);
        }
        if (resourceType == null) {
            throw new IOException("Invalid symbol type " + typeName);
        }
        int endPos = line.indexOf(' ', startPos + 1);
        // If styleable with children
        if (resourceType == ResourceType.STYLEABLE && endPos > 0) {
            String name = line.substring(startPos + 1, endPos);
            startPos = endPos + 1;
            ImmutableList.Builder<String> children = ImmutableList.builder();
            while (true) {
                endPos = line.indexOf(' ', startPos);
                if (endPos == -1) {
                    children.add(line.substring(startPos));
                    break;
                }
                children.add(line.substring(startPos, endPos));
                startPos = endPos + 1;
            }
            return new SymbolData(name, children.build());
        } else {
            String name = line.substring(startPos + 1);
            if (resourceType == ResourceType.ATTR) {
                return new SymbolData(name, maybeDefinition);
            } else {
                return new SymbolData(resourceType, name);
            }
        }
    }

    private static String computeItemName(@NotNull String prefix, @NotNull String name) {
        // tweak the name to remove the styleable prefix
        String indexName = name.substring(prefix.length());
        // check if it's a namespace, in which case replace android_name
        // with android:name
        if (indexName.startsWith(ANDROID_ATTR_PREFIX)) {
            indexName =
                    SdkConstants.ANDROID_NS_NAME_PREFIX
                            + indexName.substring(ANDROID_ATTR_PREFIX.length());
        }

        return indexName;
    }

    private enum ReadConfiguration {
        AAPT(true, false) {
            @NotNull
            @Override
            public SymbolData parseLine(@NotNull String line) throws IOException {
                return readAaptLine(line);
            }
        },
        AAPT_NO_VALUES(false, false, false, true, null) {
            @NotNull
            @Override
            public SymbolData parseLine(@NotNull String line) throws IOException {
                return readAaptLine(line);
            }
        },
        SYMBOL_LIST_WITH_PACKAGE(false, true) {
            @NotNull
            @Override
            public SymbolData parseLine(@NotNull String line) throws IOException {
                return readSymbolListWithPackageLine(line);
            }
        },
        R_DEF(false, true, true, false, "R_DEF: Internal format may change without notice") {
            @NotNull
            @Override
            public SymbolData parseLine(@NotNull String line) throws IOException {
                return readSymbolListWithPackageLine(line);
            }
        },
        PARTIAL_FILE(false, false) {
            @NotNull
            @Override
            public SymbolData parseLine(@NotNull String line) throws IOException {
                return readPartialRLine(line);
            }
        },
        PUBLIC_FILE(false, true) {
            @NotNull
            @Override
            public SymbolData parseLine(@NotNull String line) throws IOException {
                return readPublicTxtLine(line);
            }
        };

        ReadConfiguration(boolean readValues, boolean singleLineStyleable) {
            this(readValues, singleLineStyleable, false, false, null);
        }

        ReadConfiguration(
                boolean readValues,
                boolean singleLineStyleable,
                boolean rawSymbolNames,
                boolean ignoreRogueChildren,
                @Nullable String fileTypeHeader) {
            this.readValues = readValues;
            this.singleLineStyleable = singleLineStyleable;
            this.fileTypeHeader = fileTypeHeader;
            this.rawSymbolNames = rawSymbolNames;
            this.ignoreRogueChildren = ignoreRogueChildren;
        }

        final boolean readValues;
        final boolean singleLineStyleable;
        final boolean rawSymbolNames;
        final boolean ignoreRogueChildren;
        @Nullable final String fileTypeHeader;

        @NotNull
        abstract SymbolData parseLine(@NotNull String line) throws IOException;
    }

    /**
     * Load symbol tables of each library on which the main library/application depends on.
     *
     * @param libraries libraries which the main library/application depends on
     * @return a set of `symbol table for each library
     */
    public ImmutableList<SymbolTable> loadDependenciesSymbolTables(Iterable<File> libraries)
            throws IOException {
        ImmutableList.Builder<SymbolTable> tables = ImmutableList.builder();
        for (File dependency : libraries) {
            tables.add(readSymbolListWithPackageName(dependency.toPath()));
        }
        return tables.build();
    }

    /**
     * Writes a symbol table to a symbol file.
     *
     * @param table the table
     * @param file the file where the table should be written
     * @throws IOException I/O error
     */
    public static void writeForAar(@NotNull SymbolTable table, @NotNull File file)
            throws IOException {
        writeForAar(table, file.toPath());
    }

    /**
     * Writes a symbol table to a symbol file.
     *
     * @param table the table
     * @param file the file where the table should be written
     * @throws IOException I/O error
     */
    public static void writeForAar(@NotNull SymbolTable table, @NotNull Path file)
            throws IOException {
        try (Writer writer = Files.newBufferedWriter(file)) {
            writeForAar(table, writer);
        } catch (Exception e) {
            throw new IOException("Failed to write symbol file " + file.toString(), e);
        }
    }

    /**
     * Writes a symbol table to a symbol file.
     *
     * @param table the table
     * @param writer for the file where the table should be written
     * @throws IOException I/O error
     */
    public static void writeForAar(@NotNull SymbolTable table, @NotNull Writer writer)
            throws IOException {
        // loop on the resource types so that the order is always the same
        for (ResourceType resType : ResourceType.values()) {
            List<Symbol> symbols = table.getSymbolByResourceType(resType);
            if (symbols.isEmpty()) {
                continue;
            }

            for (Symbol s : symbols) {
                writer.write(s.getJavaType().getTypeName());
                writer.write(' ');
                writer.write(s.getResourceType().getName());
                writer.write(' ');
                writer.write(s.getCanonicalName());
                writer.write(' ');
                if (s.getResourceType() != ResourceType.STYLEABLE) {
                    writer.write("0x");
                    writer.write(Integer.toHexString(s.getIntValue()));
                    writer.write('\n');
                } else {

                    Symbol.StyleableSymbol styleable = (Symbol.StyleableSymbol) s;
                    writeStyleableValue(styleable, writer);
                    writer.write('\n');
                    // Declare styleables have the attributes that were defined under their node
                    // listed in
                    // the children list.
                    List<String> children = styleable.getChildren();
                    for (int i = 0; i < children.size(); ++i) {
                        writer.write(SymbolJavaType.INT.getTypeName());
                        writer.write(' ');
                        writer.write(ResourceType.STYLEABLE.getName());
                        writer.write(' ');
                        writer.write(s.getCanonicalName());
                        writer.write('_');
                        writer.write(SymbolUtils.canonicalizeValueResourceName(children.get(i)));
                        writer.write(' ');
                        writer.write(Integer.toString(i));
                        writer.write('\n');
                    }
                }
            }
        }
    }


    private static void writeStyleableValue(Symbol.StyleableSymbol s, Writer writer)
            throws IOException {
        writer.write("{ ");
        ImmutableList<Integer> values = s.getValues();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(", ");
            }
            writer.write("0x");
            writer.write(Integer.toHexString(values.get(i)));
        }
        writer.write(" }");
    }

    /**
     * Writes a file listing the resources provided by the library.
     *
     * <p>This uses the symbol list with package name format of {@code "<type> <name>[ <child>[
     * <child>[ ...]]]" }.
     */
    public static void writeRDef(@NotNull SymbolTable table, @NotNull Path file)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            writeRDef(table, writer);
        } catch (Exception e) {
            throw new IOException("Failed to write R def  file " + file.toString(), e);
        }
    }

    /**
     * Writes a file listing the resources provided by the library.
     *
     * <p>This uses the symbol list with package name format of {@code "<type> <name>[ <child>[
     * <child>[ ...]]]" }.
     */
    public static void writeRDef(@NotNull SymbolTable table, @NotNull OutputStream outputStream)
            throws IOException {
        try (BufferedWriter writer =
                new BufferedWriter(
                        new OutputStreamWriter(NonClosingStreams.nonClosing(outputStream)))) {
            writeRDef(table, writer);
        }
    }

    private static void writeRDef(@NotNull SymbolTable table, @NotNull BufferedWriter writer)
            throws IOException {
        Preconditions.checkNotNull(
                ReadConfiguration.R_DEF.fileTypeHeader, "Missing package for R-def file");

        writer.write(ReadConfiguration.R_DEF.fileTypeHeader);
        writer.write('\n');
        writer.write(table.getTablePackage());
        writer.write('\n');
        // loop on the resource types so that the order is always the same
        for (ResourceType resType : ResourceType.values()) {
            List<Symbol> symbols = table.getSymbolByResourceType(resType);
            if (symbols.isEmpty()) {
                continue;
            }

            for (Symbol s : symbols) {
                writer.write(s.getResourceType().getName());
                if (s.getResourceType() == ResourceType.ATTR
                        && ((Symbol.AttributeSymbol) s).isMaybeDefinition()) {
                    writer.write('?');
                }
                writer.write(' ');
                writer.write(s.getName());
                if (s.getResourceType() == ResourceType.STYLEABLE) {
                    List<String> children = s.getChildren();
                    for (String child : children) {
                        writer.write(' ');
                        writer.write(child);
                    }
                }
                writer.write('\n');
            }
        }
    }

    /**
     * Writes a file listing the resources provided by the SymbolTable in partial-R.txt format.
     *
     * <p>This uses the partial r (go/partial-r) format of {@code "<access qualifier> <java type>
     * <symbol type> <resource name>" }.
     *
     * @param table The SymbolTable to be written as a partial R file.
     * @param file The file path of the file to be written.
     */
    public static void writePartialR(@NotNull SymbolTable table, @NotNull Path file)
            throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(file);
        generatePartialRContents(table, writer);
        writer.close();
    }

    /**
     * Returns a String listing the resources provided by the SymbolTable in partial-R.txt format.
     *
     * <p>This uses the partial r (go/partial-r) format of {@code "<access qualifier> <java type>
     * <symbol type> <resource name>" }.
     *
     * @param table The SymbolTable to be written as a partial R file.
     */
    public static String getPartialRContentsAsString(@NotNull SymbolTable table)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        generatePartialRContents(table, sb);
        return sb.toString();
    }

    private static void generatePartialRContents(@NotNull SymbolTable table, Appendable appendable)
            throws IOException {
        // Loop resource types to keep order.
        for (ResourceType resType : ResourceType.values()) {
            List<Symbol> symbols = table.getSymbolByResourceType(resType);
            for (Symbol s : symbols) {
                appendable.append(s.getResourceVisibility().getName());
                appendable.append(' ');
                appendable.append(s.getJavaType().getTypeName());
                appendable.append(' ');
                appendable.append(s.getResourceType().getName());
                appendable.append(' ');
                appendable.append(s.getCanonicalName());
                appendable.append('\n');

                // Declare styleables having attributes defined in their node
                // listed in the children list.
                if (s.getJavaType() == SymbolJavaType.INT_LIST) {
                    Preconditions.checkArgument(
                            s.getResourceType() == ResourceType.STYLEABLE,
                            "Only resource type 'styleable' has java type 'int[]'");
                    List<String> children = s.getChildren();
                    for (String child : children) {
                        appendable.append(s.getResourceVisibility().getName());
                        appendable.append(' ');
                        appendable.append(SymbolJavaType.INT.getTypeName());
                        appendable.append(' ');
                        appendable.append(ResourceType.STYLEABLE.getName());
                        appendable.append(' ');
                        appendable.append(s.getCanonicalName());
                        appendable.append('_');
                        appendable.append(SymbolUtils.canonicalizeValueResourceName(child));
                        appendable.append('\n');
                    }
                }
            }
        }
    }

    /**
     * Writes the abridged symbol table with the package name as the first line.
     *
     * <p>This collapses the styleable children so the subsequent lines have the format {@code
     * "<type> <canonical_name>[ <child>[ <child>[ ...]]]"}
     *
     * @param symbolTable The R.txt file. If it does not exist, the result will be a file containing
     *     only the package name
     * @param manifest The AndroidManifest.xml file for this library. The package name is extracted
     *     and written as the first line of the output.
     * @param outputFile The file to write the result to.
     */
    public static void writeSymbolListWithPackageName(
            @NotNull Path symbolTable, @NotNull Path manifest, @NotNull Path outputFile)
            throws IOException {
        @Nullable String packageName;
        try (InputStream is = new BufferedInputStream(Files.newInputStream(manifest))) {
            packageName = AndroidManifestParser.parse(is).getPackage();
        } catch (SAXException | org.openjdk.javax.xml.parsers.ParserConfigurationException e) {
            throw new IOException(
                    "Failed to get package name from manifest " + manifest.toAbsolutePath(), e);
        }
        writeSymbolListWithPackageName(symbolTable, packageName, outputFile);
    }

    /**
     * Writes the symbol table with the package name as the first line.
     *
     * <p>This collapses the styleable children so the subsequent lines have the format {@code
     * "<type> <canonical_name>[ <child>[ <child>[ ...]]]" }
     *
     * @param symbolTable The R.txt file. If it does not exist, the result will be a file containing
     *     only the package name
     * @param packageName The package name for the module. If not null, it will be written as the
     *     first line of output.
     * @param outputFile The file to write the result to.
     */
    public static void writeSymbolListWithPackageName(
            @NotNull Path symbolTable, @Nullable String packageName, @NotNull Path outputFile)
            throws IOException {
        try (SymbolListWithPackageNameWriter writer =
                new SymbolListWithPackageNameWriter(
                        packageName, Files.newBufferedWriter(outputFile))) {
            if (Files.exists(symbolTable)) {
                try (Stream<String> lines = Files.lines(symbolTable)) {
                    SymbolUtils.readAarRTxt(lines.iterator(), writer);
                }
            } else {
                SymbolUtils.visitEmptySymbolTable(writer);
            }
        }
    }

    /**
     * Exports a symbol table to a java {@code R} class source. This method will create the source
     * file and any necessary directories. For example, if the package is {@code a.b} and the class
     * name is {@code RR}, this method will generate a file called {@code RR.java} in directory
     * {@code directory/a/b} creating directories {@code a} and {@code b} if necessary.
     *
     * @param table the table to export
     * @param directory the directory where the R source should be generated
     * @param finalIds should the generated IDs be final?
     * @return the generated file
     * @throws UncheckedIOException failed to generate the source
     */
    @NotNull
    public static File exportToJava(
            @NotNull SymbolTable table, @NotNull File directory, boolean finalIds) {
        Preconditions.checkArgument(directory.isDirectory());

        /*
         * Build the path to the class file, creating any needed directories.
         */
        Splitter splitter = Splitter.on('.');
        Iterable<String> directories = splitter.split(table.getTablePackage());
        File file = directory;
        for (String d : directories) {
            file = new File(file, d);
        }

        GFileUtils.mkdirs(file);
        file = new File(file, SdkConstants.R_CLASS + SdkConstants.DOT_JAVA);

        String idModifiers = finalIds ? "public static final" : "public static";

        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {

            writer.write("/* AUTO-GENERATED FILE.  DO NOT MODIFY.");
            writer.newLine(); // use system line separator
            writer.write(" *");
            writer.newLine();
            writer.write(" * This class was automatically generated by the");
            writer.newLine();
            writer.write(" * gradle plugin from the resource data it found. It");
            writer.newLine();
            writer.write(" * should not be modified by hand.");
            writer.newLine();
            writer.write(" */");
            writer.newLine();

            if (!table.getTablePackage().isEmpty()) {
                writer.write("package ");
                writer.write(table.getTablePackage());
                writer.write(';');
                writer.newLine();
            }

            writer.newLine();
            writer.write("public final class R {");
            writer.newLine();
            writer.write("    private R() {}");
            writer.newLine();
            writer.newLine();

            final String typeName = SymbolJavaType.INT.getTypeName();

            // loop on the resource types so that the order is always the same
            for (ResourceType resType : ResourceType.values()) {
                List<Symbol> symbols = table.getSymbolByResourceType(resType);
                if (symbols.isEmpty()) {
                    continue;
                }
                writer.write("    public static final class ");
                writer.write(resType.getName());
                writer.write(" {");
                writer.newLine();

                writer.write("        private ");
                writer.write(resType.getName());
                writer.write("() {}");
                writer.newLine();
                writer.newLine();

                for (Symbol s : symbols) {
                    final String name = s.getCanonicalName();
                    writer.write("        ");
                    writer.write(idModifiers);
                    writer.write(' ');
                    writer.write(s.getJavaType().getTypeName());
                    writer.write(' ');
                    writer.write(name);
                    writer.write(" = ");

                    if (s.getResourceType() != ResourceType.STYLEABLE) {
                        writer.write("0x");
                        writer.write(Integer.toHexString(s.getIntValue()));
                        writer.write(';');
                        writer.newLine();
                    } else {
                        Symbol.StyleableSymbol styleable = (Symbol.StyleableSymbol) s;
                        writeStyleableValue(styleable, writer);
                        writer.write(';');
                        writer.newLine();
                        // Declare styleables have the attributes that were defined under their
                        // node
                        // listed in the children list.
                        List<String> children = styleable.getChildren();
                        for (int i = 0; i < children.size(); ++i) {
                            writer.write("        ");
                            writer.write(idModifiers);
                            writer.write(' ');
                            writer.write(typeName);
                            writer.write(' ');
                            writer.write(name);
                            writer.write('_');
                            writer.write(
                                    SymbolUtils.canonicalizeValueResourceName(children.get(i)));
                            writer.write(" = ");
                            writer.write(Integer.toString(i));
                            writer.write(';');
                            writer.newLine();
                        }
                    }
                }
                writer.write("    }");
                writer.newLine();
            }

            writer.write('}');
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return file;
    }
}