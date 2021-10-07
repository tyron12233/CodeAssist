package com.tyron.code.ui.editor.language;

import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.widget.CodeEditor;
import java.io.File;

public interface Language {
	
	/**
	 * Subclasses return whether they support this file extension
	 */
	boolean isApplicable(File ext);
	
	EditorLanguage get(CodeEditor editor);
}
