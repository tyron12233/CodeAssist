package com.tyron.code.ui.editor.language.java;

import com.tyron.code.ui.editor.language.Language;
import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.widget.CodeEditor;
import java.io.File;

public class Java implements Language {
	
	@Override
	public boolean isApplicable(File ext) {
		return ext.getName().endsWith(".java");
	}
	
	@Override
	public EditorLanguage get(CodeEditor editor) {
		return new JavaLanguage(editor);
	}
}
