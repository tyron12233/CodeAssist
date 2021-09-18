package com.tyron.code.ui.editor.language;

import com.tyron.code.ui.editor.language.java.Java;
import com.tyron.code.ui.editor.language.kotlin.Kotlin;
import com.tyron.code.ui.editor.language.xml.Xml;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.widget.CodeEditor;

public class LanguageManager {
	
	private static LanguageManager Instance = null;
	
	public static LanguageManager getInstance() {
		if (Instance == null) {
			Instance = new LanguageManager();
		}
		return Instance;
	}
	
	private final Set<Language> mLanguages = new HashSet<>();
	
	private LanguageManager() {
		initLanguages();
	}
	
	private void initLanguages() {
		mLanguages.addAll(
			Arrays.asList(
				new Xml(),
				new Java(),
				new Kotlin()));
	}

	public boolean supports(File file) {
		for (Language language : mLanguages) {
			if (language.isApplicable(file)) {
				return true;
			}
		}
		return false;
	}
	
	public EditorLanguage get(CodeEditor editor, File file) {
		for (Language lang : mLanguages) {
			if (lang.isApplicable(file)) {
				return lang.get(editor);
			}
		}
		return null;
	}
}
