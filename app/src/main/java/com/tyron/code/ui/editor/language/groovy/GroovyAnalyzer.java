package com.tyron.code.ui.editor.language.groovy;

import com.tyron.code.ui.editor.language.kotlin.KotlinAnalyzer;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;

import java.util.Stack;

import io.github.rosemoe.sora.data.BlockLine;
import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.text.TextAnalyzer;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorColorScheme;

public class GroovyAnalyzer implements CodeAnalyzer {

    private final CodeEditor mEditor;

    public GroovyAnalyzer(CodeEditor editor) {
        mEditor = editor;
    }

    @Override
    public void analyze(CharSequence content, TextAnalyzeResult result, TextAnalyzer.AnalyzeThread.Delegate delegate) {
        try {
            CodePointCharStream stream = CharStreams.fromString(String.valueOf(content));
            GroovyLexer lexer = new GroovyLexer(stream);

            Stack<BlockLine> stack = new Stack<>();
            int maxSwitch = 1, currSwitch = 0;
            int lastLine = 0;
            int line, column;
            Token previous = UnknownToken.INSTANCE;
            Token token = null;

            while (delegate.shouldAnalyze()) {
                token = lexer.nextToken();
                if (token == null) {
                    break;
                }

                if (token.getType() == GroovyLexer.EOF) {
                    break;
                }

                line = token.getLine() - 1;
                column = token.getCharPositionInLine();
                lastLine = line;

                switch (token.getType()) {
                    case GroovyLexer.KW_DO:
                    case GroovyLexer.KW_TRUE:
                    case GroovyLexer.KW_FALSE:
                        result.addIfNeeded(line, Span.obtain(column, EditorColorScheme.KEYWORD));
                    case GroovyLexer.INTEGER:
                    case GroovyLexer.STRING:
                        result.addIfNeeded(line, Span.obtain(column, EditorColorScheme.LITERAL));
                        break;
                    case GroovyLexer.RCURVE:
                        if (!stack.isEmpty()) {
                            BlockLine b = stack.pop();
                            b.endLine = line;
                            b.endColumn = column;
                            if (b.startLine != b.endLine) {
                                result.addBlockLine(b);
                            }
                        }
                        break;
                    case GroovyLexer.LCURVE:
                        if (stack.isEmpty()) {
                            if (currSwitch > maxSwitch) {
                                maxSwitch = currSwitch;
                            }
                            currSwitch = 0;
                        }
                        currSwitch++;
                        BlockLine block = result.obtainNewBlock();
                        block.startLine = line;
                        block.startColumn = column;
                        stack.push(block);
                        break;
                    default:
                        result.addIfNeeded(line, Span.obtain(column, EditorColorScheme.TEXT_NORMAL));
                }
            }

            result.determine(lastLine);
            if (stack.isEmpty()) {
                if (currSwitch > maxSwitch) {
                    maxSwitch = currSwitch;
                }
            }
            result.setSuppressSwitch(maxSwitch + 10);
        } catch (Throwable ignore) {

        }
    }

    private static class UnknownToken implements Token {

        public static UnknownToken INSTANCE = new UnknownToken();

        @Override
        public String getText() {
            return "";
        }

        @Override
        public int getType() {
            return -1;
        }

        @Override
        public int getLine() {
            return 0;
        }

        @Override
        public int getCharPositionInLine() {
            return 0;
        }

        @Override
        public int getChannel() {
            return 0;
        }

        @Override
        public int getTokenIndex() {
            return 0;
        }

        @Override
        public int getStartIndex() {
            return 0;
        }

        @Override
        public int getStopIndex() {
            return 0;
        }

        @Override
        public TokenSource getTokenSource() {
            return null;
        }

        @Override
        public CharStream getInputStream() {
            return null;
        }
    }
}
