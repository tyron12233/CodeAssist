package com.tyron.code.ui.editor.language.java;

import com.tyron.code.ui.editor.language.Language;
import com.tyron.editor.Editor;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora2.interfaces.EditorLanguage;
import io.github.rosemoe.sora2.widget.CodeEditor;
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
