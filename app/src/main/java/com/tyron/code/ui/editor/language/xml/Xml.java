package com.tyron.code.ui.editor.language.xml;

import com.tyron.code.ui.editor.language.Language;
import io.github.rosemoe.sora2.interfaces.EditorLanguage;
import io.github.rosemoe.sora2.widget.CodeEditor;
import java.io.File;

public class Xml implements Language {
	
	@Override
	public boolean isApplicable(File file) {
		return file.getName().endsWith(".xml");
	}
	
	@Override
	public EditorLanguage get(CodeEditor editor) {
		return new LanguageXML(editor);
	}
}
