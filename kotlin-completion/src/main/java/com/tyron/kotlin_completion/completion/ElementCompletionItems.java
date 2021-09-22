package com.tyron.kotlin_completion.completion;

import com.tyron.completion.model.CompletionItem;

import org.jetbrains.kotlin.psi.KtExpression;

import kotlin.sequences.Sequence;

public class ElementCompletionItems {

    private final Sequence<CompletionItem> items;
    private final boolean isExhaustive;
    private final KtExpression receiver;

    public ElementCompletionItems(Sequence<CompletionItem> items, boolean isExhaustive, KtExpression receiver) {
        this.items = items;
        this.isExhaustive = isExhaustive;
        this.receiver = receiver;
    }

    public Sequence<CompletionItem> getItems() {
        return items;
    }

    public boolean isExhaustive() {
        return isExhaustive;
    }

    public KtExpression getReceiver() {
        return receiver;
    }


    public Sequence<CompletionItem> component1() {
        return items;
    }

    public boolean component2() {
        return isExhaustive;
    }

    public KtExpression component3() {
        return receiver;
    }
}
