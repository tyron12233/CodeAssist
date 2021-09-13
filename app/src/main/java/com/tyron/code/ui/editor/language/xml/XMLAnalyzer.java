package com.tyron.code.ui.editor.language.xml;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.Token;

import java.io.IOException;
import java.io.StringReader;

import io.github.rosemoe.editor.interfaces.CodeAnalyzer;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import io.github.rosemoe.editor.text.TextAnalyzer;
import io.github.rosemoe.editor.widget.EditorColorScheme;

public class XMLAnalyzer implements CodeAnalyzer {
	
	public XMLAnalyzer() {
		
	}
	
	@Override
	public void analyze(CharSequence content, TextAnalyzeResult colors, TextAnalyzer.AnalyzeThread.Delegate delegate) {
		try {
			CodePointCharStream stream = CharStreams.fromReader(new StringReader(content.toString()));
			XMLLexer lexer = new XMLLexer(stream);
			Token token, previous = null;
			boolean first = true;

			int lastLine = 1;
			int line, column;

			while (delegate.shouldAnalyze()) {
				token = lexer.nextToken();
				if (token == null) break;
				if (token.getType() == XMLLexer.EOF) {
					lastLine = token.getLine() - 1;
					break;
				}
				line = token.getLine() - 1;
				column = token.getCharPositionInLine();
				lastLine = line;

				switch (token.getType()) {
					case XMLLexer.COMMENT:
						colors.addIfNeeded(line, column, EditorColorScheme.COMMENT);
						break;
					case XMLLexer.Name:
						colors.addIfNeeded(line, column, EditorColorScheme.IDENTIFIER_NAME);
						break;
					case XMLLexer.EQUALS:
					case XMLLexer.STRING:
						colors.addIfNeeded(line,column, EditorColorScheme.LITERAL);
						break;
					default:
						colors.addIfNeeded(line, column, EditorColorScheme.TEXT_NORMAL);
				}
			}
			colors.determine(lastLine);
		} catch (IOException ignore) {

		}
	}

}
