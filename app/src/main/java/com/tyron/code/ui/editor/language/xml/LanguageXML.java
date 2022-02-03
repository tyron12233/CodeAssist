package com.tyron.code.ui.editor.language.xml;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.code.util.ProjectUtils;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.io.File;
import java.util.List;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class LanguageXML implements Language {

	private final Editor mEditor;

	private final XMLAnalyzer mAnalyzer;

	public LanguageXML(Editor codeEditor) {
		mEditor = codeEditor;

		mAnalyzer = new XMLAnalyzer(codeEditor);
	}

	public boolean isAutoCompleteChar(char ch) {
		return MyCharacter.isJavaIdentifierPart(ch)
				|| ch == '<'
				|| ch == '/'
				|| ch == ':'
				|| ch == '.';
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
			formatted = XmlPrettyPrinter.prettyPrint(String.valueOf(text),
					preferences, XmlFormatStyle.MANIFEST, "\n");
		} else {
			if (ProjectUtils.isLayoutXMLFile(file)) {
				formatted = XmlPrettyPrinter.prettyPrint(String.valueOf(text),
						preferences, XmlFormatStyle.LAYOUT, "\n");
			} else if (ProjectUtils.isResourceXMLFile(file)) {
				formatted = XmlPrettyPrinter.prettyPrint(String.valueOf(text),
						preferences, XmlFormatStyle.RESOURCE, "\n");
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
		return new NewlineHandler[]{new StartTagHandler()};
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
						position.getLine(), position.getColumn());
		if (items == null) {
			return;
		}
		for (CompletionItem item : items) {
			publisher.addItem(item);
		}
	}

	@Override
	public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
		return getIndentAdvance(String.valueOf(content.getReference()));
	}

	public int getIndentAdvance(String content) {
		XMLLexer lexer = new XMLLexer(CharStreams.fromString(content));
		int advance = 0;
		Token token;
		while ((token = lexer.nextToken()) != null) {
			if (token.getType() == XMLLexer.EOF) {
				break;
			}

			if (token.getType() == XMLLexer.OPEN) {
				advance++;
			} else if (token.getType() == XMLLexer.SLASH_CLOSE) {
				advance--;
			} else if (token.getType() == XMLLexer.CLOSE) {
				advance--;
			}
		}
		advance = Math.max(0, advance);
		return advance * 4;
	}

	private class StartTagHandler implements NewlineHandler {

		@Override
		public boolean matchesRequirement(String beforeText, String afterText) {
			Log.d("StartTagHandler", "beforeText: " + beforeText + " afterText: " + afterText);
			return beforeText.trim().startsWith("<");
		}

		@Override
		public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
			int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
			String text;
			StringBuilder sb = new StringBuilder()
					.append("\n")
					.append(text = TextUtils.createIndent(count + 4, tabSize, useTab()));
			return new NewlineHandleResult(sb, 0);
		}
	}
}
