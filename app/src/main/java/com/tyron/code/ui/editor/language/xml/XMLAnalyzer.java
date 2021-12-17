package com.tyron.code.ui.editor.language.xml;

import android.graphics.Color;
import android.os.Handler;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.Token;

import java.io.StringReader;
import java.util.Stack;

import io.github.rosemoe.sora.data.BlockLine;
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
		try {
			CodePointCharStream stream = CharStreams.fromReader(new StringReader(content.toString()));
			XMLLexer lexer = new XMLLexer(stream);
			Token token, previous = null;
			Stack<BlockLine> stack = new Stack<>();

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
						if (previous != null && previous.getType() == XMLLexer.SLASH) {
							colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
							break;
						} else if (previous != null && previous.getType() == XMLLexer.OPEN) {
							colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
							BlockLine block = new BlockLine();
							block.startLine = previous.getLine() - 1;
							block.startColumn = previous.getCharPositionInLine();
							stack.push(block);
							break;
						}
						String attribute = token.getText();
						if (attribute.contains(":")) {
							colors.addIfNeeded(line, column, EditorColorScheme.ATTRIBUTE_NAME);
							colors.addIfNeeded(line, column + attribute.indexOf(":"), EditorColorScheme.TEXT_NORMAL);
							break;
						}
						colors.addIfNeeded(line, column, EditorColorScheme.IDENTIFIER_NAME);
						break;
					case XMLLexer.EQUALS:
						Span span1 = colors.addIfNeeded(line,column, EditorColorScheme.OPERATOR);
						span1.setUnderlineColor(Color.TRANSPARENT);
						break;
					case XMLLexer.STRING:
						String text = token.getText();
						if (text.startsWith("\"#")) {
							try {
								int color = Color.parseColor(text.substring(1, text.length() - 1));
								colors.addIfNeeded(line, Span.obtain(column, EditorColorScheme.LITERAL));
								colors.add(line, Span.obtain(column + 1, EditorColorScheme.LITERAL))
										.setUnderlineColor(color);
								colors.add(line, Span.obtain( column + text.length() - 1, EditorColorScheme.LITERAL))
										.setUnderlineColor(Color.TRANSPARENT);
								colors.addIfNeeded(line, column + text.length(), EditorColorScheme.TEXT_NORMAL)
										.setUnderlineColor(Color.TRANSPARENT);
								break;
							} catch (Exception ignore) {}
						}
						colors.addIfNeeded(line,column, EditorColorScheme.LITERAL);
						break;
					case XMLLexer.SLASH_CLOSE:
						colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
						if (!stack.isEmpty()) {
							BlockLine block = stack.pop();
							block.endLine = line;
							block.endColumn = column;
							if (block.startLine != block.endLine) {
								if (previous.getLine() == token.getLine()) {
									block.toBottomOfEndLine = true;
								}
								colors.addBlockLine(block);
							}
						}
						break;
					case XMLLexer.SLASH:
						colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
						if (previous != null && previous.getType() == XMLLexer.OPEN) {
							if (!stack.isEmpty()) {
								BlockLine block = stack.pop();
								block.endLine = previous.getLine() - 1;
								block.endColumn = previous.getCharPositionInLine();
								if (block.startLine != block.endLine) {
									if (previous.getLine() == token.getLine()) {
										block.toBottomOfEndLine = true;
									}
									colors.addBlockLine(block);
								}
							}
						}
						break;
					case XMLLexer.OPEN:
					case XMLLexer.CLOSE:
						colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
						break;
					default:
						Span s = Span.obtain(column, EditorColorScheme.TEXT_NORMAL);
						colors.addIfNeeded(line, s);
						s.setUnderlineColor(Color.TRANSPARENT);
				}

				if (token.getType() != XMLLexer.SEA_WS && token.getType() != XMLLexer.S) {
					previous = token;
				}
			}

			new BasicXmlPullAnalyzer().analyze(content, colors, delegate);

			colors.determine(lastLine);
			compile(colors);
		} catch (Throwable ignore) {

		}
	}

	private final Handler handler = new Handler();
	long delay = 1000L;
	long lastTime;

	private void compile(TextAnalyzeResult colors) {
		handler.removeCallbacks(runnable);
		lastTime = System.currentTimeMillis();
		runnable.setColors(colors);
		handler.postDelayed(runnable, delay);
	}

	CompileRunnable runnable = new CompileRunnable();

	private class CompileRunnable implements Runnable {

		private TextAnalyzeResult colors;

		public CompileRunnable() {
		}

		public void setColors(TextAnalyzeResult colors) {
			this.colors = colors;
		}

		@Override
		public void run() {
			if (colors == null) {
				return;
			}
			if (System.currentTimeMillis() < (lastTime - 500)) {
				return;
			}

//			Executors.newSingleThreadExecutor().execute(() -> {
//				boolean isResource = ProjectUtils.isLayoutXMLFile(mEditor.getCurrentFile());
//
//				if (isResource) {
//					if (CompletionEngine.isIndexing()) {
//						return;
//					}
//					Project project = ProjectManager.getInstance().getCurrentProject();
//					if (project != null) {
//						project.getFileManager().save(mEditor.getCurrentFile(), mEditor.getText().toString());
//						Task task = new IncrementalAapt2Task();
//						try {
//							task.prepare(BuildType.DEBUG);
//							task.run();
//						} catch (IOException | CompilationFailedException e) {
//							e.printStackTrace();
//						}
//
//					}
//				}
//			});
		}
	}
}
