package com.tyron.builder.internal.logging.console;


import com.tyron.builder.api.Action;
import com.tyron.builder.internal.logging.text.AbstractLineChoppingStyledTextOutput;

public class DefaultTextArea extends AbstractLineChoppingStyledTextOutput implements TextArea {
    private static final Action<AnsiContext> NEW_LINE_ACTION = new Action<AnsiContext>() {
        @Override
        public void execute(AnsiContext ansi) {
            ansi.newLine();
        }
    };
    private static final int CHARS_PER_TAB_STOP = 8;
    private final Cursor writePos = new Cursor();
    private final AnsiExecutor ansiExecutor;

    public DefaultTextArea(AnsiExecutor ansiExecutor) {
        this.ansiExecutor = ansiExecutor;
    }

    /**
     * Returns the bottom right position of this text area.
     */
    public Cursor getWritePosition() {
        return writePos;
    }

    public void newLineAdjustment() {
        writePos.row++;
    }

    @Override
    protected void doLineText(final CharSequence text) {
        if (text.length() == 0) {
            return;
        }

        ansiExecutor.writeAt(writePos, new Action<AnsiContext>() {
            @Override
            public void execute(AnsiContext ansi) {
                ansi.withStyle(getStyle(), new Action<AnsiContext>() {
                    @Override
                    public void execute(AnsiContext ansi) {
                        String textStr = text.toString();
                        int pos = 0;
                        while (pos < text.length()) {
                            int next = textStr.indexOf('\t', pos);
                            if (next == pos) {
                                int charsToNextStop = CHARS_PER_TAB_STOP - (writePos.col % CHARS_PER_TAB_STOP);
                                for(int i = 0; i < charsToNextStop; i++) {
                                    ansi.a(" ");
                                }
                                pos++;
                            } else if (next > pos) {
                                ansi.a(textStr.substring(pos, next));
                                pos = next;
                            } else {
                                ansi.a(textStr.substring(pos));
                                pos = textStr.length();
                            }
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void doEndLine(CharSequence endOfLine) {
        ansiExecutor.writeAt(writePos, NEW_LINE_ACTION);
    }
}
