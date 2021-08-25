package com.tyron.code.editor.language.java;
import com.tyron.code.editor.language.Language;
import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.widget.CodeEditor;
import java.io.File;

public class JAVA implements Language {
	
	@Override
	public boolean isApplicable(File ext) {
		return ext.getName().endsWith(".java");
	}
	
	@Override
	public EditorLanguage get(CodeEditor editor) {
		return new JavaLanguage(editor);
	}
}
