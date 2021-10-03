package com.tyron.code.ui.editor.language.xml;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.ProteusView;
import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.code.util.ProjectUtils;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.inflate.PreviewLayoutInflater;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.interfaces.CodeAnalyzer;
import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.interfaces.NewlineHandler;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.widget.SymbolPairMatch;

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
		return XmlPrettyPrinter.prettyPrint(String.valueOf(text),
				XmlFormatPreferences.defaults(), XmlFormatStyle.LAYOUT, "\n");
	}

	public View showPreview(Context context, ViewGroup container) throws IOException, ConvertException {
		File currentFile = mEditor.getCurrentFile();
		if (currentFile == null || !ProjectUtils.isResourceXMLFile(mEditor.getCurrentFile())) {
			return null;
		}

		String xmlString = FileUtils.readFileToString(currentFile, Charset.defaultCharset());
		PreviewLayoutInflater inflater = new PreviewLayoutInflater(context, FileManager.getInstance().getCurrentProject());
		ProteusView inflatedView = inflater.inflate(xmlString);

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
		return null;
	}

	@Override
	public int getIndentAdvance(String content) {
		return 0;
	}
}
