package com.tyron.code.ui.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.tyron.code.R;
import com.tyron.code.highlighter.JavaFileHighlighter;
import com.tyron.code.highlighter.SyntaxHighlighter;
import com.tyron.code.highlighter.attributes.CodeAssistTextAttributes;
import com.tyron.code.highlighter.attributes.TextAttributesKeyUtils;
import com.tyron.code.language.CompletionItemWrapper;
import com.tyron.code.ui.main.TextEditorState;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.model.CompletionItemWithMatchLevel;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.util.CompletionUtils;

import org.jetbrains.kotlin.com.intellij.lang.ASTNode;
import org.jetbrains.kotlin.com.intellij.lang.FileASTNode;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.LanguageUtil;
import org.jetbrains.kotlin.com.intellij.lexer.Lexer;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Attachment;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.kotlin.com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.PlainTextLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.pom.core.impl.PomModelImpl;
import org.jetbrains.kotlin.com.intellij.psi.FileViewProvider;
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiInvalidElementAccessException;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.ChangedPsiRangeUtil;
import org.jetbrains.kotlin.com.intellij.psi.impl.DiffLog;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase;
import org.jetbrains.kotlin.com.intellij.psi.impl.light.JavaIdentifier;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiJavaFileImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.ResolveCache;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.FileElement;
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.util.FileContentUtilCore;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.styling.ExternalRenderer;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentListener;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.LineNumberCalculator;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub;

@SuppressLint("ViewConstructor")
public class EditorView extends FrameLayout {
    private final Project project;
    private final VirtualFile file;
    private final FileType fileType;

    private final CodeEditor editor;

    public EditorView(Context context, Project project, TextEditorState state) {
        super(context);

        this.project = project;
        this.file = state.getFile();
        this.fileType = state.getFileType();

        Language fileLanguage = LanguageUtil.getFileLanguage(this.file);
        if (fileLanguage == null) {
            fileLanguage = PlainTextLanguage.INSTANCE;
        }


        editor = new CodeEditor(context);
        editor.setColorScheme(new SchemeGitHub());
        editor.setText(state.getContent());
        editor.setAutoCompletionItemAdapter(new CodeAssistCompletionAdapter());
        editor.setTypefaceText(ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular));

        AnalyzeManager analyzeManager = new TestAnalyzeManager(new JavaFileHighlighter());
        editor.setEditorLanguage(new EmptyLanguage() {
            @NonNull
            @Override
            public AnalyzeManager getAnalyzeManager() {
                return analyzeManager;
            }

            @Override
            public void requireAutoComplete(@NonNull ContentReference content,
                                            @NonNull CharPosition position,
                                            @NonNull CompletionPublisher publisher,
                                            @NonNull Bundle extraArguments) {
                publisher.setComparator((o1, o2) -> {
                    if (o1 instanceof CompletionItemWithMatchLevel &&
                        o2 instanceof CompletionItemWithMatchLevel) {
                        return CompletionList.COMPARATOR.compare((CompletionItemWithMatchLevel) o1,
                                (CompletionItemWithMatchLevel) o2);
                    }
                    return 0;
                });
                String prefix = CompletionUtils.computePrefix(content.getLine(position.getLine()),
                        new com.tyron.editor.CharPosition(position.line, position.column),
                        Character::isJavaIdentifierPart);

                CompletionList.Builder builder = new CompletionList.Builder(publisher::addItem);

                CompletionParameters params = CompletionParameters.builder()
                        .setLine(position.getLine())
                        .setColumn(position.getColumn())
                        .setIndex(position.getIndex())
                        .setFile(new File(file.getPath()))
                        .setContents(content.toString())
                        .setPrefix(prefix)
                        .setCompletionListBuilder(builder)
                        .build();


                JavaCompletionProvider javaCompletionProvider = new JavaCompletionProvider();
                javaCompletionProvider.completeV2(params);
            }
        });


        ContentListener psiUpdater = new ContentListener() {
            @Override
            public void beforeReplace(@NonNull Content content) {

            }

            @Override
            public void afterInsert(Content content,
                                    int startLine,
                                    int startColumn,
                                    int endLine,
                                    int endColumn,
                                    @NonNull CharSequence insertedContent) {
                int startIndex = content.getCharIndex(startLine, startColumn);
                int endIndex = content.getCharIndex(endLine, endColumn);
                commit(ContentChangeEvent.ACTION_INSERT, startIndex, endIndex, insertedContent);
            }

            @Override
            public void afterDelete(Content content,
                                    int startLine,
                                    int startColumn,
                                    int endLine,
                                    int endColumn,
                                    CharSequence deletedContent) {
                int startIndex = content.getCharIndex(startLine, startColumn);
                int endIndex = content.getCharIndex(endLine, endColumn - deletedContent.length()) +
                               deletedContent.length();
                commit(ContentChangeEvent.ACTION_DELETE, startIndex, endIndex, deletedContent);
            }

            private void commit(int action, int start, int end, CharSequence charSequence) {
                EditorChangeUtil.doCommit(action, start, end, charSequence, project, file);
            }
        };
        state.getContent().addContentListener(psiUpdater);
        addView(editor);
    }

    public VirtualFile getFile() {
        return file;
    }

    public Project getProject() {
        return project;
    }

    private static class TestAnalyzeManager extends SimpleAnalyzeManager<Void> {

        private final SyntaxHighlighter highlighter;
        private ContentReference contentReference;

        public TestAnalyzeManager(SyntaxHighlighter highlighter) {
            this.highlighter = highlighter;
        }

        @Override
        public synchronized void rerun() {
            super.rerun();
        }

        @Override
        public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
            this.contentReference = content;
            super.reset(content, extraArguments);
        }

        @Override
        protected Styles analyze(StringBuilder text,
                                 SimpleAnalyzeManager<Void>.Delegate<Void> delegate) {
            Lexer lexer = highlighter.getHighlightingLexer();
            lexer.start(text);

            MappedSpans.Builder builder = new MappedSpans.Builder();
            while (!delegate.isCancelled()) {
                lexer.advance();

                IElementType tokenType = lexer.getTokenType();
                if (tokenType == null) {
                    break;
                }

                CharPosition tokenStart = contentReference.getCharPosition(lexer.getTokenStart());

                TextAttributesKey[] tokenHighlights = highlighter.getTokenHighlights(tokenType);

                if (tokenHighlights.length == 0) {
                    builder.add(tokenStart.getLine(),
                            Span.obtain(tokenStart.getColumn(), EditorColorScheme.TEXT_NORMAL));
                    continue;
                }

                TextAttributesKey first = tokenHighlights[0];
                CodeAssistTextAttributes defaultAttributes =
                        TextAttributesKeyUtils.getDefaultAttributes(first);

                if (defaultAttributes == null) {
                    builder.add(tokenStart.getLine(),
                            Span.obtain(tokenStart.getColumn(), EditorColorScheme.TEXT_NORMAL));
                    continue;
                }

                Span span;
                try {
                    span = Span.obtain(tokenStart.getColumn(),
                            TextStyle.makeStyle(defaultAttributes.getForegroundColor()));
                } catch (IllegalArgumentException e) {
                    span = Span.obtain(tokenStart.getColumn(),
                            TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL));
                }
                builder.add(tokenStart.getLine(), span);
            }

            return new Styles(builder.build());
        }
    }
}
