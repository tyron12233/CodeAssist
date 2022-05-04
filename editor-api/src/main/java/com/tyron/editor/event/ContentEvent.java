package com.tyron.editor.event;

import com.tyron.editor.Content;

import org.jetbrains.annotations.NotNull;

import java.util.EventObject;

public abstract class ContentEvent extends EventObject {

    public ContentEvent(Content source) {
        super(source);
    }

    public Content getContent() {
        return ((Content) getSource());
    }

    /**
     * The start offset of a text change.
     */
    public abstract int getOffset();

    public int getMoveOffset() {
        return getOffset();
    }

    @NotNull
    public abstract CharSequence getOldFragment();

    @NotNull
    public abstract CharSequence getNewFragment();

    public abstract int getOldLength();
    public abstract int getNewLength();

    public abstract long getOldTimeStamp();

    public boolean isWholeTextReplaced() {
        return getOffset() == 0 && getNewLength() == getContent().length();
    }
}
