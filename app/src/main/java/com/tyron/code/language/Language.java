package com.tyron.code.language;

import com.tyron.legacyEditor.Editor;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.provider.local.LocalFile;

import java.io.File;

public interface Language {
	
	/**
	 * Subclasses return whether they support this file extension
	 */
	boolean isApplicable(File ext);

	default boolean isApplicable(FileObject fileObject) {
		if (fileObject instanceof LocalFile) {
			return isApplicable(new File(fileObject.getURI()));
		}
		return false;
	}

	/**
	 *
	 * @param editor the editor instance
	 * @return The specific language instance for this editor
	 */
	io.github.rosemoe.sora.lang.Language get(Editor editor);
}
