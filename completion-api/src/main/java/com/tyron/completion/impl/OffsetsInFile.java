package com.tyron.completion.impl;

import com.tyron.completion.OffsetMap;

import org.jetbrains.kotlin.com.intellij.lang.FileASTNode;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator;
import org.jetbrains.kotlin.com.intellij.pom.PomManager;
import org.jetbrains.kotlin.com.intellij.pom.core.impl.PomModelImpl;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.FileElement;
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport;

import java.util.function.Supplier;

public class OffsetsInFile {

    private final PsiFile file;
    private final OffsetMap offsets;

    public OffsetsInFile(PsiFile file) {
        this(file, new OffsetMap(file.getViewProvider().getDocument()));
    }

    public OffsetsInFile(PsiFile file, OffsetMap map) {
        this.file = file;
        this.offsets = map;
    }

    public PsiFile getFile() {
        return file;
    }

    public OffsetMap getOffsets() {
        return offsets;
    }

    public Supplier<OffsetsInFile> replaceInCopy(PsiFile fileCopy,
                                                 int startOffset,
                                                 int endOffset,
                                                 String replacement) {
        String originalText = offsets.getDocument().getImmutableCharSequence().toString();
        DocumentImpl tempDocument = new DocumentImpl(originalText,
                originalText.contains("\r") || replacement.contains("\r"),
                true);
        OffsetMap tempMap = offsets.copyOffsets(tempDocument);
        tempDocument.replaceString(startOffset, endOffset, replacement);

        Document copyDocument = fileCopy.getViewProvider().getDocument();
        assert copyDocument != null;

        FileASTNode node = fileCopy.getNode();
        if (!(node instanceof FileElement)) {
            throw new IllegalStateException();
        }

        return () -> {
            copyDocument.setText(tempDocument.getImmutableCharSequence());
            ((PsiFileImpl) fileCopy).onContentReload();

            return new OffsetsInFile(fileCopy, tempMap.copyOffsets(copyDocument));
        };
    }
}
