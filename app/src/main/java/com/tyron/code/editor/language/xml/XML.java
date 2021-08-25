package com.tyron.code.editor.language.xml;
import com.tyron.code.editor.language.Language;
import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.widget.CodeEditor;
import java.io.File;

public class XML implements Language {
	
	@Override
	public boolean isApplicable(File file) {
		return file.getName().endsWith(".xml");
	}
	
	@Override
	public EditorLanguage get(CodeEditor editor) {
		return new LanguageXML();
	}
}
