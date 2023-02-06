package com.tyron.completion.psi.completion.item;

import androidx.annotation.NonNull;

import com.tyron.completion.model.CompletionItemWithMatchLevel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiNamedElement;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

public class SimplePsiCompletionItem extends CompletionItemWithMatchLevel {

    private static final String INSERTED_IDENTIFIER = "IntelijIdeaRulezzzzzzzz";

    private final String toInsert;
    private final PsiElement element;
    private final PsiElement position;

    public SimplePsiCompletionItem(PsiElement element, String toInsert, PsiElement position) {
        super(toInsert);

        label(toInsert);
        addFilterText(toInsert);

        this.element = element;
        this.toInsert = toInsert;
        this.position = position;
    }




    @Override
    public void performCompletion(@NonNull @NotNull CodeEditor editor,
                                  @NonNull @NotNull Content text,
                                  int line,
                                  int column) {
        String positionText = position.getText();
        String currentIdentifier = positionText.substring(0, positionText.length() - INSERTED_IDENTIFIER.length());

        // delete old prefix
        int identifierStart = position.getTextRange().getStartOffset();
        text.delete(identifierStart, identifierStart + currentIdentifier.length());

        CharPosition insertPosition = text.getIndexer().getCharPosition(identifierStart);
        text.insert(insertPosition.getLine(), insertPosition.getColumn(), toInsert);
    }
}
