package com.tyron.code.ui.editor.language.xml;

import android.graphics.Color;
import android.os.Handler;
import android.util.Log;

import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.builder.log.ILogger;
import com.tyron.code.util.ProjectUtils;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.completion.provider.CompletionEngine;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.Token;

import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.Executors;

import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.text.TextAnalyzer;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorColorScheme;

public class XMLAnalyzer implements CodeAnalyzer {

	private final CodeEditor mEditor;

	public XMLAnalyzer(CodeEditor codeEditor) {
		mEditor = codeEditor;
	}
	
	@Override
	public void analyze(CharSequence content, TextAnalyzeResult colors, TextAnalyzer.AnalyzeThread.Delegate delegate) {

		compile();

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
						Span span1 = colors.addIfNeeded(line,column, EditorColorScheme.OPERATOR);
						span1.setUnderlineColor(Color.TRANSPARENT);
						break;
					case XMLLexer.STRING:
						colors.addIfNeeded(line,column, EditorColorScheme.LITERAL);
//						String text = token.getText();
//						if (text.startsWith("\"#")) {
//							try {
//								span.setUnderlineColor(Color.parseColor(text.substring(1, text.length() - 1)));
//							} catch (Exception ignore) {}
//						}
						break;
					case XMLLexer.SEA_WS:
					case XMLLexer.S:
					default:
						Span s = Span.obtain(column, EditorColorScheme.TEXT_NORMAL);
						colors.addIfNeeded(line, s);
				}
			}
			colors.determine(lastLine);
		} catch (IOException ignore) {

		}
	}

	private final Handler handler = new Handler();
	long delay = 1000L;
	long lastTime;

	private void compile() {
		handler.removeCallbacks(runnable);
		lastTime = System.currentTimeMillis();
		handler.postDelayed(runnable, delay);
	}

	CompileRunnable runnable = new CompileRunnable();

	private class CompileRunnable implements Runnable {
		@Override
		public void run() {
			Log.d("AAPT2", "Compiling");
			if (System.currentTimeMillis() < (lastTime - 500)) {
				return;
			}

			Executors.newSingleThreadExecutor().execute(() -> {
				boolean isResource = ProjectUtils.isResourceXMLFile(mEditor.getCurrentFile());

				if (isResource) {
					if (CompletionEngine.isIndexing()) {
						return;
					}
					Project project = FileManager.getInstance().getCurrentProject();
					if (project != null) {
						Task task = new IncrementalAapt2Task();
						try {
							task.prepare(project, new ILogger() {
								@Override
								public void info(DiagnosticWrapper wrapper) {

								}

								@Override
								public void debug(DiagnosticWrapper wrapper) {

								}

								@Override
								public void warning(DiagnosticWrapper wrapper) {

								}

								@Override
								public void error(DiagnosticWrapper wrapper) {

								}
							});
							task.run();
						} catch (IOException | CompilationFailedException e) {
							e.printStackTrace();
						}

					}
				}
			});
		}
	}
}
