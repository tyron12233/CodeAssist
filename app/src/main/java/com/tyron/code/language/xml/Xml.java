package com.tyron.code.language.xml;

import com.tyron.code.language.Language;
import com.tyron.editor.Editor;

import org.apache.commons.vfs2.FileObject;

import java.io.File;


public class Xml implements Language {
	
	@Override
	public boolean isApplicable(File file) {
		return file.getName().endsWith(".xml");
	}

	@Override
	public boolean isApplicable(FileObject fileObject) {
		return fileObject.getName()	.getExtension().equals("xml");
	}

	@Override
	public io.github.rosemoe.sora.lang.Language get(Editor editor) {
		return new LanguageXML(editor);
	}
}
