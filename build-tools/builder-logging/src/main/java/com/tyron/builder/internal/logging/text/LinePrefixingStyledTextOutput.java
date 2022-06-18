package com.tyron.builder.internal.logging.text;


/**
 * A {@link StyledTextOutput} which prefixes each line of text with some fixed prefix. Does not prefix the first line.
 */
public class LinePrefixingStyledTextOutput extends AbstractLineChoppingStyledTextOutput {
    private final StyledTextOutput output;
    private final CharSequence prefix;
    private final boolean prefixFirstLine;
    private boolean prefixed;

    public LinePrefixingStyledTextOutput(StyledTextOutput output, CharSequence prefix) {
        this(output, prefix, true);
    }

    public LinePrefixingStyledTextOutput(StyledTextOutput output, CharSequence prefix, boolean prefixFirstLine) {
        this.output = output;
        this.prefix = prefix;
        this.prefixFirstLine = prefixFirstLine;
    }

    @Override
    protected void doLineText(CharSequence text) {
        if (!prefixed && prefixFirstLine) {
            output.text(prefix);
            prefixed = true;
        }
        output.text(text);
    }

    @Override
    protected void doEndLine(CharSequence endOfLine) {
        output.text(endOfLine);
    }

    @Override
    protected void doStartLine() {
        output.text(prefix);
    }

    @Override
    protected void doStyleChange(Style style) {
        output.style(style);
    }
}
