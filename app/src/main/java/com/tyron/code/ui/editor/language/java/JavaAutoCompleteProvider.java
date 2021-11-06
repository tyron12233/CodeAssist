package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.tyron.ProjectManager;
import com.tyron.builder.model.Project;
import com.tyron.completion.drawable.CircleDrawable;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.provider.CompletionEngine;
import com.tyron.psi.completion.CompletionEnvironment;
import com.tyron.psi.lookup.LookupElement;

import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.command.WriteCommandAction;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.impl.PsiJavaFileStubImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.JavaStubPsiElement;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiJavaFileImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.CodeEditor;

public class JavaAutoCompleteProvider implements AutoCompleteProvider {

    private final CodeEditor mEditor;
    private final SharedPreferences mPreferences;

    public JavaAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
    }


    @Override
    public List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult analyzeResult, int line, int column) throws InterruptedException {
        if (!mPreferences.getBoolean("code_editor_completion", true)) {
            return Collections.emptyList();
        }
        if (CompletionEngine.isIndexing()) {
            return Collections.emptyList();
        }

        Project currentProject = ProjectManager.getInstance().getCurrentProject();
        List<CompletionItem> result = new ArrayList<>();
        if (currentProject != null) {
            CompletionList completionList = CompletionEngine.getInstance().complete(currentProject,
                    mEditor.getCurrentFile(),
                    mEditor.getText().toString(),
                    mEditor.getCursor().getLeft());

            for (com.tyron.completion.model.CompletionItem item : completionList.items) {
                result.add(new CompletionItem(item));
            }
            return result;
        } else {
            Log.w("JavaAutoCompleteProvider", "Current project is null");
        }
        return null;
    }

    @SuppressWarnings("all")
    private List<CompletionItem> getPsiItems() {
        Project currentProject = ProjectManager.getInstance().getCurrentProject();
        CompletionEnvironment completionEnvironment = ProjectManager.getInstance().getCompletionEnvironment();

        currentProject.getFileManager()
                .save(mEditor.getCurrentFile(), mEditor.getText().toString());

        if (!CompletionEngine.getInstance().getCompiler(currentProject).isReady()) {
            return Collections.emptyList();
        }

        Cursor cursor = mEditor.getCursor();
        int offset = cursor.getLeft();

        List<CompletionItem> result = new ArrayList<>();

        PsiJavaFile javaFile = (PsiJavaFile) completionEnvironment.getPsiFile(mEditor.getCurrentFile());
        if (javaFile == null) {
            return null;
        }
        Document document = javaFile.getViewProvider().getDocument();
        if (document == null) {
            return null;
        }
        CommandProcessor.getInstance().executeCommand(completionEnvironment.getEnvironment().getProject(), () -> {
            document.replaceString(0, document.getTextLength(), mEditor.getText().toString());
        }, "insert", "");
        PsiDocumentManager.getInstance(completionEnvironment.getEnvironment().getProject()).commitDocument(document);
        javaFile = (PsiJavaFile) PsiDocumentManager.getInstance(completionEnvironment.getEnvironment().getProject()).getPsiFile(document);
        if (javaFile == null) {
            return null;
        }
        PsiManager.getInstance(completionEnvironment.getEnvironment().getProject())
                .reloadFromDisk(javaFile);
        completionEnvironment.getCompletionEngine()
                .complete(javaFile, javaFile.getViewProvider().findElementAt(offset - 1), offset, completionResult -> {
                    LookupElement element = completionResult.getLookupElement();
                    com.tyron.completion.model.CompletionItem item = new com.tyron.completion.model.CompletionItem();
                    item.label = element.getLookupString();
                    item.iconKind = CircleDrawable.Kind.Method;
                    result.add(new CompletionItem(item));
                });
        return result;
    }
}
