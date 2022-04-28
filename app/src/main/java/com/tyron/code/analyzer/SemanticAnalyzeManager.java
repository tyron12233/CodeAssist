package com.tyron.code.analyzer;

import com.tyron.code.analyzer.semantic.SemanticToken;
import com.tyron.code.language.HighlightUtil;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Content;
import com.tyron.editor.Editor;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Spans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.textmate.core.grammar.StackElement;
import io.github.rosemoe.sora.textmate.core.theme.FontStyle;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import io.github.rosemoe.sora.textmate.core.theme.ThemeTrieElementRule;

public abstract class SemanticAnalyzeManager extends DiagnosticTextmateAnalyzer {

    private List<SemanticToken> mSemanticTokens;

    public SemanticAnalyzeManager(Editor editor,
                                  String grammarName,
                                  InputStream grammarIns,
                                  Reader languageConfiguration,
                                  IRawTheme theme) throws Exception {
        super(editor, grammarName, grammarIns, languageConfiguration, theme);
    }

    public abstract List<SemanticToken> analyzeSpansAsync(CharSequence contents);

    @Override
    public void insert(io.github.rosemoe.sora.text.CharPosition start,
                       io.github.rosemoe.sora.text.CharPosition end,
                       CharSequence insertedText) {
        super.insert(start, end, insertedText);
    }

    @Override
    protected void modifyStyles(Styles styles) {
        super.modifyStyles(styles);

        Content content = mEditor.getContent();

        if (mSemanticTokens != null) {
            for (int i = mSemanticTokens.size() - 1; i >= 0; i--) {
                SemanticToken token = mSemanticTokens.get(i);
                if (token.getOffset() > content.length()) {
                    continue;
                }
                CharPosition start = mEditor.getCharPosition(token.getOffset());
                CharPosition end =
                        mEditor.getCharPosition(token.getOffset() + token.getLength());

                Span span = Span.obtain(0, getStyle(token));
                HighlightUtil.replaceSpan(styles, span, start.getLine(), start.getColumn(),
                                          end.getLine(), end.getColumn());
            }
        }
    }

    private long getStyle(SemanticToken token) {
        List<ThemeTrieElementRule> match = getTheme().match(token.getTokenType().toString());
        if (!match.isEmpty()) {
            ThemeTrieElementRule next = match.iterator().next();
            int foreground = next.foreground;
            int fontStyle = next.fontStyle;
            return TextStyle.makeStyle(foreground + 255, 0,
                                       (fontStyle & FontStyle.Bold) == FontStyle.Bold,
                                       (fontStyle & FontStyle.Italic) == FontStyle.Italic,
                                       false);
        }
        return 0;
    }
}
