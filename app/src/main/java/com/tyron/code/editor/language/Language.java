package com.tyron.code.editor.language;
import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.widget.CodeEditor;
import java.io.File;

public interface Language {
	
	/**
	 * Subclasses return wether they support this file extension
	 */
	boolean isApplicable(File ext);
	
	EditorLanguage get(CodeEditor editor);
}
