package com.tyron.eclipse.formatter;

import org.eclipse.jdt.internal.formatter.DefaultCodeFormatter;
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatterOptions;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

/**
 * Formats java source files using the eclipse formatter.
 */
public class Formatter {

    /**
     * Formats the given source using the default java convention options
     * Returns the original string if the source cannot be formatted.
     *
     * @param source      The java source
     * @param indentLevel The number of indents per line
     * @return Formatted java source
     */
    public static String format(String source, int indentLevel) {
        DefaultCodeFormatterOptions options =
                DefaultCodeFormatterOptions.getJavaConventionsSettings();
        return format(source, indentLevel, 0, source.length(), options);
    }

    /**
     * Formats the given source at the specified start and end index.
     *
     * @param source  The java source
     * @param indentLevel The number of indents on a new line
     * @param start   The start index
     * @param length  The length of the source to format
     * @param options The formatting options
     * @return The formatted source
     */
    public static String format(String source,
                                int indentLevel,
                                int start,
                                int length,
                                DefaultCodeFormatterOptions options) {
        DefaultCodeFormatter formatter = new DefaultCodeFormatter(options);
        TextEdit format = formatter
                .format(DefaultCodeFormatter.K_UNKNOWN, source, start, length, indentLevel, "\n");

        IDocument document = new Document(source);
        try {
            format.apply(document);
        } catch (BadLocationException e) {
            return source;
        }
        return document.get();
    }
}