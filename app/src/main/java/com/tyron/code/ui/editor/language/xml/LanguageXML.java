package com.tyron.code.ui.editor.language.xml;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.code.util.ProjectUtils;
import com.tyron.layoutpreview.PreviewContext;
import com.tyron.layoutpreview.PreviewLayoutInflater;
import com.tyron.layoutpreview.PreviewTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.interfaces.CodeAnalyzer;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.widget.SymbolPairMatch;
import io.github.rosemoe.editor.interfaces.NewlineHandler;

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

	public View showPreview(Context context, ViewGroup container) {
		File currentFile = mEditor.getCurrentFile();
		if (currentFile == null || !ProjectUtils.isResourceXMLFile(mEditor.getCurrentFile())) {
			return null;
		}

		Project project = FileManager.getInstance().getCurrentProject();
		File apk = new File(project.getBuildDirectory(), "bin/generated.apk.res");
		File rClassDir = new File(project.getBuildDirectory(), "gen");
		List<File> dexes = new ArrayList<>();
		for (File jar : project.getLibraries()) {
			File parent = jar.getParentFile();
			if (parent == null) {
				continue;
			}

			File[] children = parent.listFiles(c -> c.getName().endsWith(".dex"));
			if (children != null) {
				Collections.addAll(dexes, children);
			}
		}
		File classOutput = new File(project.getBuildDirectory(), "layout_preview");
		if (!classOutput.exists()) {
			classOutput.mkdirs();
		}

		List<File> libraryRes = new ArrayList<>();

		PreviewTask task = new PreviewTask(apk, rClassDir, dexes,libraryRes,   classOutput);
		Optional<PreviewContext> optionalPreviewContext = task.run(context);
		if (!optionalPreviewContext.isPresent()) {
			return null;
		}
		PreviewContext previewContext = optionalPreviewContext.get();

		String resName = currentFile.getName().substring(0, currentFile.getName().lastIndexOf("."));
		String defType = "layout";
		String packageName = project.getPackageName();

		int resId = previewContext.getResources()
				.getIdentifier(resName, defType, packageName);

		View view = new PreviewLayoutInflater(previewContext).inflate(resId, container, false);
		return view;
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
