package com.tyron.code.ui.editor.language.xml;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.ProteusView;
import com.tyron.ProjectManager;
import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.builder.project.api.AndroidProject;
import com.tyron.builder.project.api.Project;
import com.tyron.code.util.ProjectUtils;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.inflate.PreviewLayoutInflater;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.interfaces.NewlineHandler;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class LanguageXML implements EditorLanguage {

	private final CodeEditor mEditor;
	public LanguageXML(CodeEditor codeEditor) {
		mEditor = codeEditor;
	}
	@Override
	public boolean isAutoCompleteChar(char ch) {
		return false;
	}

	@Override
	public boolean useTab() {
		return true;
	}

	@Override
	public CharSequence format(CharSequence text) {
		XmlFormatPreferences preferences = XmlFormatPreferences.defaults();
		File file = mEditor.getCurrentFile();
		if ("AndroidManifest.xml".equals(file.getName())) {
			return XmlPrettyPrinter.prettyPrint(String.valueOf(text), preferences, XmlFormatStyle.MANIFEST, "\n");
		} else {
			if (ProjectUtils.isLayoutXMLFile(file)) {
				return XmlPrettyPrinter.prettyPrint(String.valueOf(text), preferences, XmlFormatStyle.LAYOUT, "\n");
			} else if (ProjectUtils.isResourceXMLFile(file)) {
				return XmlPrettyPrinter.prettyPrint(String.valueOf(text), preferences, XmlFormatStyle.RESOURCE, "\n");
			}
		}
		return text;
	}

	public View showPreview(Context context, ViewGroup container) throws IOException, ConvertException {
		Project project = ProjectManager.getInstance().getCurrentProject();
		File currentFile = mEditor.getCurrentFile();
		if (currentFile == null || !ProjectUtils.isResourceXMLFile(mEditor.getCurrentFile())) {
			return null;
		}
		if (!(project instanceof AndroidProject)) {
			return null;
		}
		PreviewLayoutInflater inflater = new PreviewLayoutInflater(context, (AndroidProject) project);
		inflater.parseResources(Executors.newSingleThreadExecutor());
		ProteusView inflatedView = inflater.inflateLayout(currentFile.getName().substring(0,  currentFile.getName().lastIndexOf(".")));

		return inflatedView.getAsView();
	}

	@Override
	public AutoCompleteProvider getAutoCompleteProvider() {
		return new XMLAutoCompleteProvider();
	}

	@Override
	public CodeAnalyzer getAnalyzer() {
		return new XMLAnalyzer(mEditor);
	}

	@Override
	public SymbolPairMatch getSymbolPairs() {
		return null;
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
