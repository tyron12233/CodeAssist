package com.tyron.code.language.java;

import com.tyron.code.language.Language;
import com.tyron.editor.Editor;

import java.io.File;

public class Java implements Language {
	
	@Override
	public boolean isApplicable(File ext) {
		return ext.getName().endsWith(".java");
	}
	
	@Override
	public io.github.rosemoe.sora.lang.Language get(Editor editor) {
		return new JavaLanguage(editor);
	}
}
