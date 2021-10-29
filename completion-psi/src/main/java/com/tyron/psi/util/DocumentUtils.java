package com.tyron.psi.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentEvent;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.DocumentEx;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.util.LocalTimeCounter;
import org.jetbrains.kotlin.com.intellij.util.text.ImmutableCharSequence;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class DocumentUtils {

    public static void insertString(Document document, int offset, @NotNull CharSequence s) {
        if (offset < 0) throw new IndexOutOfBoundsException("Wrong offset: " + offset);
        if (offset > document.getTextLength()) {
            throw new IndexOutOfBoundsException("Wrong offset: " + offset + "; documentLength: " + document.getTextLength());
        }
        if (s.length() == 0) return;
        RangeMarker marker;
        try {
            Method getRangeGuard = DocumentImpl.class.getDeclaredMethod("getRangeGuard", int.class, int.class);
            getRangeGuard.setAccessible(true);
            marker = (RangeMarker) getRangeGuard.invoke(document, offset, offset);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            return;
        }

        ImmutableCharSequence myText;
        try {
            Field myTextField = DocumentImpl.class.getDeclaredField("myText");
            myTextField.setAccessible(true);
            myText = (ImmutableCharSequence) myTextField.get(document);
        } catch (Exception ignored) {
            return;
        }
        ImmutableCharSequence newText = myText.insert(offset, s);
        ImmutableCharSequence newString = newText.subtext(offset, offset + s.length());
        updateText(document, newText, offset, "", newString, false, LocalTimeCounter.currentTime(),
                offset, 0, offset);
    }

    public static void trimToSize(Document document) {
        try {
            int bufferSize;
            Field myBufferSize = DocumentImpl.class.getDeclaredField("myBufferSize");
            myBufferSize.setAccessible(true);
            bufferSize = (int) myBufferSize.get(document);

            if (bufferSize != 0 && document.getTextLength() > bufferSize) {
                Method deleteString = DocumentImpl.class.getDeclaredMethod("deleteString", int.class, int.class);
                deleteString.setAccessible(true);
                deleteString.invoke(document, 0, document.getTextLength() - bufferSize);
            }
        } catch (Exception ignored) {

        }
    }

    public static void updateText(@NotNull Document document,
                                  ImmutableCharSequence newText,
                                  int offset,
                                  @NotNull CharSequence oldString,
                                  @NotNull CharSequence newString,
                                  boolean wholeTextReplaced,
                                  long newModificationStamp,
                                  int initialStartOffset,
                                  int initialOldLength,
                                  int moveOffset) {
        try {
            Method updateText = DocumentImpl.class.getDeclaredMethod("updateText", ImmutableCharSequence.class, int.class, CharSequence.class, CharSequence.class, boolean.class, long.class, int.class, int.class, int.class);
            updateText.setAccessible(true);
            updateText.invoke(document, newText, offset, oldString, newString, wholeTextReplaced, newModificationStamp, initialStartOffset, initialOldLength, moveOffset);
        } catch (Exception ignored) {
        }
    }
}
