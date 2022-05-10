package com.tyron.editor.event.impl;

import com.tyron.editor.Content;
import com.tyron.editor.event.ContentEvent;
import com.tyron.editor.util.diff.Diff;

import org.jetbrains.annotations.NotNull;

public class ContentEventImpl extends ContentEvent {

    private final int myOffset;
    @NotNull
    private final CharSequence myOldString;
    private final int myOldLength;
    @NotNull
    private final CharSequence myNewString;
    private final int myNewLength;

    private final long myOldTimeStamp;
    private final boolean myIsWholeDocReplaced;
    private Diff.Change myChange;
    private static final Diff.Change TOO_BIG_FILE = new Diff.Change(0, 0, 0, 0, null);

    private final int myInitialStartOffset;
    private final int myInitialOldLength;
    private final int myMoveOffset;

//    private LineSet myOldFragmentLineSet;
    private int myOldFragmentLineSetStart;

    public ContentEventImpl(@NotNull Content content,
                             int offset,
                             @NotNull CharSequence oldString,
                             @NotNull CharSequence newString,
                             long oldTimeStamp,
                             boolean wholeTextReplaced,
                             int initialStartOffset,
                             int initialOldLength,
                             int moveOffset) {
        super(content);
        myOffset = offset;

        myOldString = oldString;
        myOldLength = oldString.length();

        myNewString = newString;
        myNewLength = newString.length();

        myInitialStartOffset = initialStartOffset;
        myInitialOldLength = initialOldLength;
        myMoveOffset = moveOffset;

        myOldTimeStamp = oldTimeStamp;

        myIsWholeDocReplaced = getContent().length() != 0 && wholeTextReplaced;
        assert initialStartOffset >= 0 : initialStartOffset;
        assert initialOldLength >= 0 : initialOldLength;
        assert moveOffset == offset || myOldLength == 0 || myNewLength == 0 : this;
    }

    @Override
    public int getOffset() {
        return myOffset;
    }

    @Override
    public @NotNull CharSequence getOldFragment() {
        return null;
    }

    @Override
    public @NotNull CharSequence getNewFragment() {
        return null;
    }

    @Override
    public int getOldLength() {
        return myOldLength;
    }

    @Override
    public int getNewLength() {
        return myNewLength;
    }

    @Override
    public long getOldTimeStamp() {
        return myOldTimeStamp;
    }
}
