package com.tyron.completion;

import com.tyron.editor.Editor;

import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import io.github.rosemoe.sora.widget.CodeEditor;

public class EditorMemory {

    public static Key<String> PREFIX_KEY = Key.create("PREFIX");
    public static Key<PsiFile> FILE_KEY = Key.create("FILE");
    public static Key<PsiElement> INSERTED_KEY = Key.create("INSERTED_ELEMENT");
    public static Key<Document> DOCUMENT_KEY = Key.create("DOCUMENT");

    private static final Map<Editor, UserDataHolderBase> editorToHolderMap = new WeakHashMap<>();

    public static <T> T getUserData(Editor editor, Key<T> key) {
        UserDataHolderBase userDataHolderBase = editorToHolderMap.get(editor);
        if (userDataHolderBase != null) {
            return userDataHolderBase.getUserData(key);
        }
        return null;
    }

    public static <T> void putUserData(Editor editor, Key<T> key, T value) {
        UserDataHolderBase userDataHolderBase = editorToHolderMap.get(editor);
        if (userDataHolderBase == null) {
            editorToHolderMap.put(editor, userDataHolderBase = new UserDataHolderBase());
        }

        userDataHolderBase.putUserData(key, value);
    }
}
