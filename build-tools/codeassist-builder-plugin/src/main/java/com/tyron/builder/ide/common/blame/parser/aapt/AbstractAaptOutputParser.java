package com.tyron.builder.ide.common.blame.parser.aapt;

import static com.android.SdkConstants.ANDROID_MANIFEST_XML;
import static com.android.SdkConstants.ATTR_NAME;
import static com.android.SdkConstants.ATTR_TYPE;
import static com.android.SdkConstants.DOT_XML;
import static com.android.SdkConstants.TAG_ITEM;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.blame.Message;
import com.android.ide.common.blame.SourceFile;
import com.android.ide.common.blame.SourceFilePosition;
import com.android.ide.common.blame.SourcePosition;
import com.android.resources.ResourceFolderType;
import com.android.utils.ILogger;
import com.android.utils.SdkUtils;
import com.android.utils.XmlUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.tyron.builder.common.utils.XmlUtilsWorkaround;
import com.tyron.builder.ide.common.blame.parser.ParsingFailedException;
import com.tyron.builder.ide.common.blame.parser.PatternAwareOutputParser;
import com.tyron.builder.ide.common.blame.parser.util.OutputLineReader;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.javax.xml.parsers.SAXParser;
import org.openjdk.javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

@VisibleForTesting
public abstract class AbstractAaptOutputParser implements PatternAwareOutputParser {
    /**
     * Portion of the error message which states the context in which the error occurred, such as
     * which property was being processed and what the string value was that caused the error.
     * <pre>
     * error: No resource found that matches the given name (at 'text' with value '@string/foo')
     * </pre>
     */
    private static final Pattern PROPERTY_NAME_AND_VALUE = Pattern
            .compile("\\(at '(.+)' with value '(.*)'\\)");

    /**
     * Portion of error message which points to the second occurrence of a repeated resource
     * definition. <p> Example: error: Resource entry repeatedStyle1 already has bag item
     * android:gravity.
     */
    private static final Pattern REPEATED_RESOURCE = Pattern
            .compile("Resource entry (.+) already has bag item (.+)\\.");

    /**
     * Suffix of error message which points to the first occurrence of a repeated resource
     * definition. Example: Originally defined here.
     */
    private static final String ORIGINALLY_DEFINED_HERE = "Originally defined here.";

    private static final Pattern NO_RESOURCE_FOUND = Pattern
            .compile("No resource found that matches the given name: attr '(.+)'\\.");

    /**
     * Portion of error message which points to a missing required attribute in a resource
     * definition. <p> Example: error: error: A 'name' attribute is required for <style>
     */
    private static final Pattern REQUIRED_ATTRIBUTE = Pattern
            .compile("A '(.+)' attribute is required for <(.+)>");

    // Keep in sync with SdkUtils#FILENAME_PREFIX
    private static final String START_MARKER = "<!-- From: ";

    private static final String END_MARKER = " -->";

    private static final Cache<String, ReadOnlyDocument> ourDocumentsByPathCache = CacheBuilder
            .newBuilder()
            .weakValues().build();

    public static final String AAPT_TOOL_NAME = "AAPT";

    @VisibleForTesting
    public static File ourRootDir;

    @NonNull
    private static SourcePosition findMessagePositionInFile(
            @NonNull File file, @NonNull String msgText, int locationLine, ILogger logger) {
        SourcePosition exactPosition =
                findExactMessagePositionInFile(file, msgText, locationLine, logger);
        if (exactPosition != null) {
            return exactPosition;
        } else {
            return new SourcePosition(locationLine, -1, -1);
        }
    }

    @Nullable
    private static SourcePosition findExactMessagePositionInFile(
            @NonNull File file,
            @NonNull String msgText,
            int locationLine,
            @NonNull ILogger logger) {
        Matcher matcher = PROPERTY_NAME_AND_VALUE.matcher(msgText);
        if (matcher.find()) {
            String name = matcher.group(1);
            String value = matcher.group(2);
            if (!value.isEmpty()) {
                return findText(file, name, value, locationLine, logger);
            }
            SourcePosition position1 = findText(file, name, "\"\"", locationLine, logger);
            SourcePosition position2 = findText(file, name, "''", locationLine, logger);
            if (position1 == null) {
                if (position2 == null) {
                    // position at the property name instead.
                    return findText(file, name, null, locationLine, logger);
                }
                return position2;
            } else if (position2 == null) {
                return position1;
            } else if (position1.getStartOffset() < position2.getStartOffset()) {
                return position1;
            } else {
                return position2;
            }
        }

        matcher = REPEATED_RESOURCE.matcher(msgText);
        if (matcher.find()) {
            String property = matcher.group(2);
            return findText(file, property, null, locationLine, logger);
        }

        matcher = NO_RESOURCE_FOUND.matcher(msgText);
        if (matcher.find()) {
            String property = matcher.group(1);
            return findText(file, property, null, locationLine, logger);
        }

        matcher = REQUIRED_ATTRIBUTE.matcher(msgText);
        if (matcher.find()) {
            String elementName = matcher.group(2);
            return findText(file, '<' + elementName, null, locationLine, logger);
        }

        if (msgText.endsWith(ORIGINALLY_DEFINED_HERE)) {
            return findLineStart(file, locationLine, logger);
        }
        return null;
    }

    @Nullable
    private static SourcePosition findText(@NonNull File file, @NonNull String first,
            @Nullable String second, int locationLine, @NonNull ILogger logger) {
        ReadOnlyDocument document = getDocument(file, logger);
        if (document == null) {
            return null;
        }
        int offset = document.lineOffset(locationLine);
        if (offset == -1L) {
            return null;
        }
        int resultOffset = document.findText(first, offset);
        if (resultOffset == -1L) {
            return null;
        }

        if (second != null) {
            resultOffset = document.findText(second, resultOffset + first.length());
            if (resultOffset == -1L) {
                return null;
            }
        }

        int startLineNumber = document.lineNumber(resultOffset);
        int startLineOffset = document.lineOffset(startLineNumber);
        int endResultOffset = resultOffset + (second != null ? second.length() : first.length());
        int endLineNumber = document.lineNumber(endResultOffset);
        int endLineOffset = document.lineOffset((endLineNumber));
        return new SourcePosition(startLineNumber, resultOffset - startLineOffset, resultOffset,
                                  endLineNumber, endResultOffset - endLineOffset, endResultOffset);
    }

    @Nullable
    private static SourcePosition findLineStart(
            @NonNull File file, int locationLine, ILogger logger) {
        ReadOnlyDocument document = getDocument(file, logger);
        if (document == null) {
            return null;
        }
        int lineOffset = document.lineOffset(locationLine);
        if (lineOffset == -1L) {
            return null;
        }
        int nextLineOffset = document.lineOffset(locationLine + 1);
        if (nextLineOffset == -1) {
            nextLineOffset = document.length();
        }

        // Ignore whitespace at the beginning of the line
        int resultOffset = -1;
        for (int i = lineOffset; i < nextLineOffset; i++) {
            char c = document.charAt(i);
            if (!Character.isWhitespace(c)) {
                resultOffset = i;
                break;
            }
        }
        if (resultOffset == -1L) {
            return null;
        }

        //Ignore whitespace at the end of the line
        int endResultOffset = resultOffset;
        for (int i = nextLineOffset - 1; i >= resultOffset; i--) {
            char c = document.charAt(i);
            if (!Character.isWhitespace(c)) {
                endResultOffset = i;
                break;
            }
        }
        return new SourcePosition(locationLine, resultOffset - lineOffset, resultOffset,
                locationLine, endResultOffset - lineOffset, endResultOffset);
    }

    @Nullable
    private static ReadOnlyDocument getDocument(@NonNull File file, ILogger logger) {
        String filePath = file.getAbsolutePath();
        ReadOnlyDocument document = ourDocumentsByPathCache.getIfPresent(filePath);
        if (document == null || document.isStale()) {
            try {
                if (!file.exists()) {
                    if (ourRootDir != null && ourRootDir.isAbsolute() && !file.isAbsolute()) {
                        file = new File(ourRootDir, file.getPath());
                        return getDocument(file, logger);
                    }
                    return null;
                }
                document = new ReadOnlyDocument(file);
                ourDocumentsByPathCache.put(filePath, document);
            } catch (IOException e) {
                String format = "Unexpected error occurred while reading file '%s' [%s]";
                logger.warning(format, file.getAbsolutePath(), e);
                return null;
            }
        }
        return document;
    }

    @NonNull
    private static String urlToPath(@NonNull String url) {
        if (url.startsWith("file:")) {
            String prefix;
            if (url.startsWith("file://")) {
                prefix = "file://";
            } else {
                prefix = "file:";
            }
            return url.substring(prefix.length());
        }
        return url;
    }

    /**
     * Locates a resource value definition in a given file for a given key, and returns the
     * corresponding line number, or -1 if not found. For example, given the key
     * "string/group2_string" it will locate an element {@code <string name="group2_string">} or
     * {@code <item type="string" name="group2_string"}
     */
    public static SourcePosition findResourceLine(
            @NonNull File file, @NonNull String key, @NonNull ILogger logger) {
        int slash = key.indexOf('/');
        if (slash == -1) {
            assert false : slash; // invalid key format
            return SourcePosition.UNKNOWN;
        }

        final String type = key.substring(0, slash);
        final String name = key.substring(slash + 1);

        return findValueDeclaration(file, type, name, logger);
    }

    /**
     * Locates a resource value declaration in a given file and returns the corresponding line
     * number, or -1 if not found.
     */
    public static SourcePosition findValueDeclaration(
            @NonNull File file,
            @NonNull final String type,
            @NonNull final String name,
            @NonNull ILogger logger) {
        if (!file.exists()) {
            return SourcePosition.UNKNOWN;
        }

        final ReadOnlyDocument document = getDocument(file, logger);
        if (document == null) {
            return SourcePosition.UNKNOWN;
        }

        // First just do something simple: scan for the string. If it only occurs once, it's easy!
        int index = document.findText(name, 0);
        if (index == -1) {
            return SourcePosition.UNKNOWN;
        }

        // See if there are any more occurrences; if not, we're done
        if (document.findText(name, index + name.length()) == -1) {
            return document.sourcePosition(index);
        }

        // Try looking for name="$name"
        int nameIndex = document.findText("name=\"" + name + "\"", 0);
        if (nameIndex != -1) {
            // TODO: Disambiguate by type, so if values.xml contains both R.string.foo and
            // R.dimen.foo we pick the right one!
            return document.sourcePosition(nameIndex);
        }

        SourcePosition lineNumber = findValueDeclarationViaParse(type, name, document);
        if (!SourcePosition.UNKNOWN.equals(lineNumber)) {
            return lineNumber;
        }

        // Just fall back to the first occurrence of the string
        //noinspection ConstantConditions
        assert index != -1;
        return document.sourcePosition(index);
    }

    private static SourcePosition findValueDeclarationViaParse(final String type, final String name,
            ReadOnlyDocument document) {
        // Finally do a full SAX parse to identify the position
        final int[] certain = new int[]{-1, 0};  // line,column for exact match
        final int[] possible = new int[]{-1,
                0}; // line,column for possible match, not confirmed by type

        final AtomicReference<Integer> line = new AtomicReference<>(-1);
        final DefaultHandler handler =
                new DefaultHandler() {
                    private int myDepth;

                    private Locator myLocator;

                    @Override
                    public void setDocumentLocator(final Locator locator) {
                        myLocator = locator;
                    }

                    @Override
                    public void startElement(
                            String uri, String localName, String qName, Attributes attributes)
                            throws SAXException {
                        myDepth++;
                        if (myDepth == 2) {
                            if (name.equals(attributes.getValue(ATTR_NAME))) {
                                int lineNumber = myLocator.getLineNumber() - 1;
                                int column = myLocator.getColumnNumber() - 1;
                                if (qName.equals(type)
                                        || TAG_ITEM.equals(qName)
                                                && type.equals(attributes.getValue(ATTR_TYPE))) {
                                    line.set(lineNumber);
                                    certain[0] = lineNumber;
                                    certain[1] = column;
                                } else if (line.get() < 0) {
                                    // Use a negative number to indicate a match where we're not totally
                                    // confident (type didn't match)
                                    line.set(-lineNumber);
                                    possible[0] = lineNumber;
                                    possible[1] = column;
                                }
                            }
                        }
                    }

                    @Override
                    public void endElement(String uri, String localName, String qName)
                            throws SAXException {
                        myDepth--;
                    }
                };

        SAXParserFactory factory = SAXParserFactory.newInstance();
        try {
            // Parse the input
            XmlUtilsWorkaround.configureSaxFactory(factory, false, false);
            SAXParser saxParser = XmlUtilsWorkaround.createSaxParser(factory);
            saxParser.parse(new InputSource(new StringReader(document.getContents())), handler);
        } catch (Throwable t) {
            // Ignore parser errors; we might have found the error position earlier than the parse
            // error position
        }

        int endLineNumber;
        int endColumn;
        if (certain[0] != -1) {
            endLineNumber = certain[0];
            endColumn = certain[1];
        } else {
            endLineNumber = possible[0];
            endColumn = possible[1];
        }
        if (endLineNumber != -1) {
            // SAX' locator will point to the END of the opening declaration, meaning that if it
            // spans multiple lines, we are pointing
            // to the last line:
            //     <item
            //       type="dimen"
            //       name="attribute"
            //     >     <--- this is where the locator points, so we need to search backwards
            int endOffset = document.lineOffset(endLineNumber) + endColumn;
            int offset = document.findTextBackwards(name, endOffset);
            if (offset != -1) {
                SourcePosition start = document.sourcePosition(offset);
                return new SourcePosition(
                        start.getStartLine(),
                        start.getStartColumn(),
                        start.getStartOffset(),
                        endLineNumber,
                        endColumn,
                        endOffset);
            }
            return new SourcePosition(endLineNumber, endColumn, endOffset);
        }

        return SourcePosition.UNKNOWN;
    }

    @Nullable
    static Matcher getNextLineMatcher(@NonNull OutputLineReader reader, @NonNull Pattern pattern) {
        // unless we can't, because we reached the last line
        String line = reader.readLine();
        if (line == null) {
            // we expected a 2nd line, so we flag as error and we bail
            return null;
        }
        Matcher m = pattern.matcher(line);
        return m.matches() ? m : null;
    }

    @NonNull
    static Message createMessage(
            @NonNull Message.Kind kind,
            @NonNull String text,
            @Nullable String sourcePath,
            @Nullable String lineNumberAsText,
            @Nullable String startColumnAsText,
            @Nullable String endColumnAsText,
            @NonNull String original,
            ILogger logger)
            throws ParsingFailedException {
        File file = null;
        if (sourcePath != null) {
            file = new File(sourcePath);
            if (!file.isFile()) {
                throw new ParsingFailedException();
            }
        }

        SourcePosition errorPosition =
                parseErrorPosition(lineNumberAsText, startColumnAsText, endColumnAsText);
        if (file != null) {
            SourceFilePosition source =
                    findSourcePosition(file, errorPosition.getStartLine(), text, logger);
            if (source != null) {
                file = source.getFile().getSourceFile();
                if (source.getPosition().getStartLine() != -1) {
                    errorPosition = source.getPosition();
                }
            }
        }

        // Attempt to determine the exact range of characters affected by this error.
        // This will look up the actual text of the file, go to the particular error line and
        // findText for the specific string mentioned in the error.
        if (file != null
                && errorPosition.getStartLine() != -1
                && errorPosition.getStartColumn() == -1) {
            errorPosition =
                    findMessagePositionInFile(file, text, errorPosition.getStartLine(), logger);
        }
        return new Message(
                kind,
                text,
                original,
                AAPT_TOOL_NAME,
                file == null
                        ? SourceFilePosition.UNKNOWN
                        : new SourceFilePosition(file, errorPosition));
    }

    @NonNull
    static Message createMessage(
            @NonNull Message.Kind kind,
            @NonNull String text,
            @Nullable String sourcePath,
            @Nullable String lineNumberAsText,
            @NonNull String original,
            ILogger logger)
            throws ParsingFailedException {
        return createMessage(
                kind, text, sourcePath, lineNumberAsText, null, null, original, logger);
    }

    private static SourcePosition parseErrorPosition(
            @Nullable String lineNumberAsText,
            @Nullable String startColumnAsText,
            @Nullable String endColumnAsText)
            throws ParsingFailedException {
        int lineNumber = 0, startColumn = 0, endColumn;
        if (lineNumberAsText != null) {
            try {
                lineNumber = Integer.parseInt(lineNumberAsText);
            } catch (NumberFormatException e) {
                throw new ParsingFailedException();
            }
        }
        if (startColumnAsText != null) {
            try {
                startColumn = Integer.parseInt(startColumnAsText);
            } catch (NumberFormatException e) {
                throw new ParsingFailedException();
            }
        }
        if (endColumnAsText != null) {
            try {
                endColumn = Integer.parseInt(endColumnAsText);
            } catch (NumberFormatException e) {
                throw new ParsingFailedException();
            }
        } else {
            endColumn = startColumn;
        }
        return new SourcePosition(
                lineNumber - 1, startColumn - 1, -1, lineNumber - 1, endColumn - 1, -1);
    }

    /** @return null if could not be found, new SourceFilePosition(new SourceFile file, */
    @Nullable
    protected static SourceFilePosition findSourcePosition(
            @NonNull File file, int locationLine, String message, ILogger logger) {
        if (!file.getPath().endsWith(DOT_XML)) {
            return null;
        }

        ReadOnlyDocument document = getDocument(file, logger);
        if (document == null) {
            return null;
        }
        // All value files get merged together into a single values file; in that case, we need to
        // search for comment markers backwards which indicates the source file for the current file

        int searchStart;
        String fileName = file.getName();
        boolean isManifest = fileName.equals(ANDROID_MANIFEST_XML);
        boolean isValueFile = fileName.startsWith(ResourceFolderType.VALUES.getName());
        if (isValueFile || isManifest) {
            searchStart = document.lineOffset(locationLine);
        } else {
            searchStart = document.length();
        }
        if (searchStart == -1L) {
            return null;
        }

        int start = document.findTextBackwards(START_MARKER, searchStart);
        assert start < searchStart;

        if (start == -1 && isManifest && searchStart < document.length()) {
            // If the manifest file didn't need to merge, it will place the source reference at the
            // end instead
            searchStart = document.length();
            if (searchStart != -1L) {
                start = document.findTextBackwards(START_MARKER, searchStart);
                assert start < searchStart;
            }
        }

        if (start == -1) {
            return null;
        }
        start += START_MARKER.length();
        int end = document.findText(END_MARKER, start);
        if (end == -1) {
            return null;
        }
        String sourcePath = document.subsequence(start, end);
        File sourceFile;
        if (sourcePath.startsWith("file:")) {
            String originalPath = sourcePath;
            sourcePath = urlToPath(sourcePath);
            sourceFile = new File(sourcePath);
            if (!sourceFile.exists()) {
                // JpsPathUtil.urlToPath just chops off the prefix; try a little harder
                // for example to decode %2D's which are used by the MergedResourceWriter to
                // encode --'s in the path, since those are invalid in XML comments
                try {
                    sourceFile = SdkUtils.urlToFile(originalPath);
                } catch (MalformedURLException e) {
                    logger.warning("Invalid file URL: " + originalPath);
                }
            }
        } else {
            sourceFile = new File(sourcePath);
        }

        if (isValueFile) {
            // Look up the line number
            SourcePosition position = findMessagePositionInFile(sourceFile, message,
                    1, logger); // Search from the beginning
            return new SourceFilePosition(new SourceFile(sourceFile), position);
        }

        return new SourceFilePosition(new SourceFile(sourceFile), SourcePosition.UNKNOWN);
    }
}
