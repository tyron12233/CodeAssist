package com.tyron.code.ui.editor.language.textmate;

import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.ui.editor.language.HighlightUtil;
import com.tyron.editor.Editor;

import java.io.InputStream;
import java.io.Reader;
import java.util.Collections;
import java.util.List;

import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.UIThreadIncrementalAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.folding.FoldingRegions;
import io.github.rosemoe.sora.langs.textmate.folding.IndentRange;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.textmate.core.grammar.IGrammar;
import io.github.rosemoe.sora.textmate.core.grammar.ITokenizeLineResult2;
import io.github.rosemoe.sora.textmate.core.grammar.StackElement;
import io.github.rosemoe.sora.textmate.core.internal.grammar.Grammar;
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
public class BaseTextmateAnalyzer extends SimpleAnalyzeManager<StackElement> {

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
                                  Delegate<StackElement> delegate) {
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

    public IncrementalAnalyzeManager.LineTokenizeResult<StackElement, Span> tokenizeLine(CharSequence lineC,
                                                                                         StackElement state) {
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
        return new IncrementalAnalyzeManager.LineTokenizeResult<>(lineTokens.getRuleStack(), null, tokens);
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        if (!extraArguments.getBoolean("loaded", false)) {
            return;
        }
        super.reset(content, extraArguments);
    }

    @Override
    protected Styles analyze(StringBuilder text, Delegate<StackElement> delegate) {
        Content model = new Content(text);
        Styles styles = new Styles();
        MappedSpans.Builder builder = new MappedSpans.Builder();
        try {
            boolean first = true;
            StackElement ruleStack = null;
            for (int lineCount = 0; lineCount < model.getLineCount(); lineCount++) {
                if (delegate.isCancelled()) {
                    break;
                }
                String line = model.getLine(lineCount) + "\n";
                if (first) {
                    builder.addNormalIfNull();
                    first = false;
                }
                IncrementalAnalyzeManager.LineTokenizeResult<StackElement, Span>
                        result = tokenizeLine(line, ruleStack);
                for (Span span : result.spans) {
                    builder.add(lineCount, span);
                }

                ruleStack = result.state;
            }

            analyzeCodeBlocks(model, styles.blocks, delegate);

            builder.determine(model.getLineCount() - 1);
            styles.spans = builder.build();
            styles.finishBuilding();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return styles;
    }

    public void updateTheme(IRawTheme theme) {
        registry.setTheme(theme);
        this.theme = Theme.createFromRawTheme(theme);
    }
}
