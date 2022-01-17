package com.tyron.code.ui.editor.language.xml;

import android.util.Log;

import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.code.util.ProjectUtils;
import com.tyron.completion.xml.XmlCharacter;
import com.tyron.completion.xml.lexer.XMLLexer;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.interfaces.NewlineHandler;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class LanguageXML implements EditorLanguage {

	private final CodeEditor mEditor;

	public LanguageXML(CodeEditor codeEditor) {
		mEditor = codeEditor;
	}

	@Override
	public boolean isAutoCompleteChar(char ch) {
		return MyCharacter.isJavaIdentifierPart(ch)
				|| ch == '<'
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
	public AutoCompleteProvider getAutoCompleteProvider() {
		return new XMLAutoCompleteProvider(mEditor);
	}

	@Override
	public CodeAnalyzer getAnalyzer() {
		return new XMLAnalyzer(mEditor);
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
		public HandleResult handleNewline(String beforeText, String afterText, int tabSize) {
			int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
			String text;
			StringBuilder sb = new StringBuilder()
					.append("\n")
					.append(text = TextUtils.createIndent(count + 4, tabSize, useTab()));
			return new HandleResult(sb, 0);
		}
	}
}
