package com.tyron.eclipse.formatter;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
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
     * @param indentLevel The number of indents at the start of each line
     * @return Formatted java source
     */
    public static String format(String source, int indentLevel) {
        return format(source, indentLevel, 0, source.length());
    }

    public static String format(String source, int start, int length) {
        return format(source, 0, start, length);
    }

    /**
     * Formats the given source at the given range with the default eclipse java convention settings
     * @param source The java source contents
     * @param indentLevel The number of indents at the start of each line
     * @param start the start index
     * @param length the length of the source to format
     * @return Formatted java source
     */
    public static String format(String source, int indentLevel, int start, int length) {
        DefaultCodeFormatterOptions options =
                DefaultCodeFormatterOptions.getEclipseDefaultSettings();
        return format(source, indentLevel, start, length, options);
    }

    /**
     * Formats the given source at the specified start and end index.
     *
     * @param source  The java source
     * @param indentLevel The number of indents at the start of each line
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
                .format(DefaultCodeFormatter.K_COMPILATION_UNIT, source, start, length, indentLevel, "\n");

        IDocument document = new Document(source);
        try {
            format.apply(document);
        } catch (BadLocationException e) {
            return source;
        }
        return document.get();
    }
}