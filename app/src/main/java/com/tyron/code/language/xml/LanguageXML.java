package com.tyron.code.language.xml;

import android.content.res.AssetManager;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.analyzer.BaseTextmateAnalyzer;
import com.tyron.code.util.ProjectUtils;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.editor.Editor;

import java.io.File;
import java.io.InputStreamReader;
import java.util.List;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class LanguageXML implements Language {

    private final Editor mEditor;

    private final BaseTextmateAnalyzer mAnalyzer;

    public LanguageXML(Editor editor) {
        mEditor = editor;

        try {
            AssetManager assetManager = ApplicationLoader.applicationContext.getAssets();
            mAnalyzer = new XMLAnalyzer(editor, "xml.tmLanguage.json",
                                                 assetManager.open(
                                                         "textmate/xml" +
                                                         "/syntaxes/xml" +
                                                         ".tmLanguage.json"),
                                                 new InputStreamReader(
                                                         assetManager.open(
                                                                 "textmate/java/language-configuration.json")),
                                                 ((TextMateColorScheme) ((CodeEditorView) editor).getColorScheme()).getRawTheme());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isAutoCompleteChar(char ch) {
        return MyCharacter.isJavaIdentifierPart(ch) ||
               ch == '<' ||
               ch == '/' ||
               ch == ':' ||
               ch == '.';
    }

    @Override
    public boolean useTab() {
        return true;
    }

    @Override
    public CharSequence format(CharSequence text) {
        XmlFormatPreferences preferences = XmlFormatPreferences.defaults();
        File file = mEditor.getCurrentFile();
        CharSequence formatted = null;
        if ("AndroidManifest.xml".equals(file.getName())) {
            formatted = XmlPrettyPrinter.prettyPrint(String.valueOf(text), preferences,
                                                     XmlFormatStyle.MANIFEST, "\n");
        } else {
            if (ProjectUtils.isLayoutXMLFile(file)) {
                formatted = XmlPrettyPrinter.prettyPrint(String.valueOf(text), preferences,
                                                         XmlFormatStyle.LAYOUT, "\n");
            } else if (ProjectUtils.isResourceXMLFile(file)) {
                formatted = XmlPrettyPrinter.prettyPrint(String.valueOf(text), preferences,
                                                         XmlFormatStyle.RESOURCE, "\n");
            }
        }
        if (formatted == null) {
            formatted = text;
        }
        return formatted;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[]{new StartTagHandler(), new EndTagHandler(),
                new EndTagAttributeHandler()};
    }

    @Override
    public void destroy() {

    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return mAnalyzer;
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_SLIGHT;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content,
                                    @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher,
                                    @NonNull Bundle extraArguments) throws CompletionCancelledException {
        String prefix = CompletionHelper.computePrefix(content, position, this::isAutoCompleteChar);
        List<CompletionItem> items =
                new XMLAutoCompleteProvider(mEditor).getAutoCompleteItems(prefix,
                                                                          position.getLine(),
                                                                          position.getColumn());
        if (items == null) {
            return;
        }
        for (CompletionItem item : items) {
            publisher.addItem(item);
        }
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        String text = content.getLine(line)
                .substring(0, column);
        return getIndentAdvance(text);
    }

    public int getIndentAdvance(String content) {
        return getIndentAdvance(content, XMLLexer.DEFAULT_MODE, true);
    }

    public int getIndentAdvance(String content, int mode, boolean ignore) {
        return 0;
//        XMLLexer lexer = new XMLLexer(CharStreams.fromString(content));
//        lexer.pushMode(mode);
//
//        int advance = 0;
//        while (lexer.nextToken()
//                       .getType() != Lexer.EOF) {
//            switch (lexer.getToken()
//                    .getType()) {
//                case XMLLexer.OPEN:
//                    advance++;
//                    break;
//                case XMLLexer.CLOSE:
//                case XMLLexer.SLASH_CLOSE:
//                    advance--;
//                    break;
//            }
//        }
//
//        if (advance == 0 && mode != XMLLexer.INSIDE) {
//            return getIndentAdvance(content, XMLLexer.INSIDE, ignore);
//        }
//
//        return advance * mEditor.getTabCount();
    }

    public int getFormatIndent(String line) {
        return getIndentAdvance(line, XMLLexer.DEFAULT_MODE, false);
    }

    private class EndTagHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            String trim = beforeText.trim();
            if (!trim.startsWith("<")) {
                return false;
            }
            if (!trim.endsWith(">")) {
                return false;
            }
            return afterText.trim()
                    .startsWith("</");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            String middle;
            StringBuilder sb = new StringBuilder();
            sb.append('\n');
            sb.append(TextUtils.createIndent(count + tabSize, tabSize, useTab()));
            sb.append('\n');
            sb.append(middle = TextUtils.createIndent(count, tabSize, useTab()));
            return new NewlineHandleResult(sb, middle.length() + 1);
        }
    }

    private class EndTagAttributeHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.trim()
                           .endsWith(">") &&
                   afterText.trim()
                           .startsWith("</");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            String middle;
            StringBuilder sb = new StringBuilder();
            sb.append('\n');
            sb.append(TextUtils.createIndent(count, tabSize, useTab()));
            sb.append('\n');
            sb.append(middle = TextUtils.createIndent(count - tabSize, tabSize, useTab()));
            return new NewlineHandleResult(sb, middle.length() + 1);
        }
    }

    private class StartTagHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            String trim = beforeText.trim();
            return trim.startsWith("<") && !trim.endsWith(">");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            String text;
            StringBuilder sb = new StringBuilder().append("\n")
                    .append(TextUtils.createIndent(count + tabSize, tabSize, useTab()));
            return new NewlineHandleResult(sb, 0);
        }
    }
}
