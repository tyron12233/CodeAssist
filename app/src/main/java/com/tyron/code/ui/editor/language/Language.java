package com.tyron.code.ui.editor.language;

import com.tyron.editor.Editor;

import io.github.rosemoe.sora.widget.CodeEditor;
import java.io.File;

public interface Language {
	
	/**
	 * Subclasses return whether they support this file extension
	 */
	boolean isApplicable(File ext);

	/**
	 *
	 * @param editor the editor instance
	 * @return The specific language instance for this editor
	 */
	io.github.rosemoe.sora.lang.Language get(Editor editor);
}
