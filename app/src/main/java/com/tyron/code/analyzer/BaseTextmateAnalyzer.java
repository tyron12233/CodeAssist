package com.tyron.code.analyzer;

import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.tyron.code.language.textmate.BaseIncrementalAnalyzeManager;
import com.tyron.code.language.textmate.CodeBlockUtils;
import com.tyron.editor.Editor;

import java.io.InputStream;
import java.io.Reader;
import java.util.List;

import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.langs.textmate.analyzer.TextMateAnalyzer;
import io.github.rosemoe.sora.langs.textmate.folding.FoldingRegions;
import io.github.rosemoe.sora.langs.textmate.folding.IndentRange;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.textmate.core.grammar.IGrammar;
import io.github.rosemoe.sora.textmate.core.grammar.ITokenizeLineResult2;
import io.github.rosemoe.sora.textmate.core.grammar.StackElement;
import io.github.rosemoe.sora.textmate.core.internal.grammar.StackElementMetadata;
import io.github.rosemoe.sora.textmate.core.registry.Registry;
import io.github.rosemoe.sora.textmate.core.theme.FontStyle;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import io.github.rosemoe.sora.textmate.core.theme.Theme;
import io.github.rosemoe.sora.textmate.languageconfiguration.ILanguageConfiguration;
import io.github.rosemoe.sora.textmate.languageconfiguration.internal.LanguageConfigurator;
import io.github.rosemoe.sora.textmate.languageconfiguration.internal.supports.Folding;
import io.github.rosemoe.sora.util.ArrayList;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * A text mate analyzer which does not use a TextMateLanguage
 */
public class BaseTextmateAnalyzer extends BaseIncrementalAnalyzeManager<StackElement, Span> {

    /**
     * Maximum for code block count
     */
    public static int MAX_FOLDING_REGIONS_FOR_INDENT_LIMIT = 5000;

    private final Registry registry = new Registry();
    private final IGrammar grammar;
    private Theme theme;
    private final Editor editor;
    private final ILanguageConfiguration configuration;

    public BaseTextmateAnalyzer(Editor editor,
                                String grammarName,
                                InputStream grammarIns,
                                Reader languageConfiguration,
                                IRawTheme theme) throws Exception {
        registry.setTheme(theme);
        this.editor = editor;
        this.theme = Theme.createFromRawTheme(theme);
        this.grammar = registry.loadGrammarFromPathSync(grammarName, grammarIns);
        if (languageConfiguration != null) {
            LanguageConfigurator languageConfigurator =
                    new LanguageConfigurator(languageConfiguration);
            configuration = languageConfigurator.getLanguageConfiguration();
        } else {
            configuration = null;
        }
    }

    public void analyzeCodeBlocks(Content model,
                                  List<CodeBlock> blocks,
                                  CodeBlockAnalyzeDelegate delegate) {
        if (configuration == null) {
            return;
        }
        Folding folding = configuration.getFolding();
        if (folding == null) {
            return;
        }
        try {
            FoldingRegions foldingRegions =
                    CodeBlockUtils.computeRanges(model, editor.getTabCount(), folding.getOffSide(),
                                                 folding, MAX_FOLDING_REGIONS_FOR_INDENT_LIMIT,
                                                 delegate);
            for (int i = 0; i < foldingRegions.length() && !delegate.isCancelled(); i++) {
                int startLine = foldingRegions.getStartLineNumber(i);
                int endLine = foldingRegions.getEndLineNumber(i);
                if (startLine != endLine) {
                    CodeBlock codeBlock = new CodeBlock();
                    codeBlock.toBottomOfEndLine = true;
                    codeBlock.startLine = startLine;
                    codeBlock.endLine = endLine;

                    // It's safe here to use raw data because the Content is only held by this
                    // thread
                    int length = model.getColumnCount(startLine);
                    char[] chars = model.getLine(startLine)
                            .getRawData();

                    codeBlock.startColumn =
                            IndentRange.computeStartColumn(chars, length, editor.useTab() ? 1 : editor.getTabCount());
                    codeBlock.endColumn = codeBlock.startColumn;
                    blocks.add(codeBlock);
                }
            }
            blocks.sort(CodeBlock.COMPARATOR_END);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public StackElement getInitialState() {
        return null;
    }

    @Override
    public boolean stateEquals(StackElement state, StackElement another) {
        if (state == null && another == null) {
            return true;
        }
        if (state != null && another != null) {
            return state.equals(another);
        }
        return false;
    }

    @Override
    public Result<StackElement, Span> tokenizeLine(CharSequence lineC, StackElement state) {
        String line = lineC.toString();
        ArrayList<Span> tokens = new ArrayList<>();
        ITokenizeLineResult2 lineTokens = grammar.tokenizeLine2(line, state);
        int tokensLength = lineTokens.getTokens().length / 2;
        for (int i = 0; i < tokensLength; i++) {
            int startIndex = lineTokens.getTokens()[2 * i];
            if (i == 0 && startIndex != 0) {
                tokens.add(Span.obtain(0, EditorColorScheme.TEXT_NORMAL));
            }
            int metadata = lineTokens.getTokens()[2 * i + 1];
            int foreground = StackElementMetadata.getForeground(metadata);
            int fontStyle = StackElementMetadata.getFontStyle(metadata);
            Span span = Span.obtain(startIndex, TextStyle
                    .makeStyle(foreground + 255, 0, (fontStyle & FontStyle.Bold) != 0,
                               (fontStyle & FontStyle.Italic) != 0, false));

            if ((fontStyle & FontStyle.Underline) != 0) {
                String color = theme.getColor(foreground);
                if (color != null) {
                    span.underlineColor = Color.parseColor(color);
                }
            }

            tokens.add(span);
        }
        return new Result<>(lineTokens.getRuleStack(), null, tokens);
    }

    @Override
    public List<Span> generateSpansForLine(LineTokenizeResult<StackElement, Span> tokens) {
        return null;
    }

    @Override
    public List<CodeBlock> computeBlocks(Content text, CodeBlockAnalyzeDelegate delegate) {
        List<CodeBlock> list = new java.util.ArrayList<>();
        analyzeCodeBlocks(text, list, delegate);
        return list;
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        if (!extraArguments.getBoolean("loaded", false)) {
            return;
        }
        super.reset(content, extraArguments);
    }

    public void updateTheme(IRawTheme theme) {
        registry.setTheme(theme);
        this.theme = Theme.createFromRawTheme(theme);
    }

    protected Theme getTheme() {
        return theme;
    }
}
