package com.tyron.code.ui.editor.impl.text.rosemoe;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.Maps;
import com.tyron.editor.AbstractContent;
import com.tyron.editor.event.ContentEvent;
import com.tyron.editor.event.ContentListener;
import com.tyron.editor.event.impl.ContentEventImpl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;

public class ContentWrapper extends Content implements com.tyron.editor.Content {

    private AtomicInteger sequence;

    public ContentWrapper() {

    }

    public ContentWrapper(CharSequence text) {
        super(text, true);
    }

    private long modificationStamp = 0;
    private final Map<String, Object> dataMap = Maps.newConcurrentMap();
    private final List<ContentListener> contentListeners = new CopyOnWriteArrayList<>();

    @Override
    public void insert(int index, CharSequence text) {
        CharPosition pos = getIndexer().getCharPosition(index);
        insert(pos.line, pos.column, text);
    }

    @Override
    public void insert(int line, int column, CharSequence text) {
        super.insert(line, column, text);

        int offset = getCharIndex(line, column);
        Content newText = this;
        CharSequence newString = newText.subSequence(offset, offset + text.length());
        updateText(newText, offset, "", newString, false, System.currentTimeMillis(), offset, 0,
                offset);
    }

    @Override
    public void replace(int start, int end, CharSequence text) {
        CharPosition startPos = getIndexer().getCharPosition(start);
        CharPosition endPos = getIndexer().getCharPosition(end);
        replace(startPos.line, startPos.column, endPos.line, endPos.column, text);
    }

    @Override
    public void delete(int start, int end) {
        CharPosition startPos = getIndexer().getCharPosition(start);
        CharPosition endPos = getIndexer().getCharPosition(end);
        delete(startPos.line, startPos.column, endPos.line, endPos.column);
    }

    @Override
    public void delete(int startLine, int columnOnStartLine, int endLine, int columnOnEndLine) {
        // need to get the offset before deleting since the end offset will be invalid
        // if it has been deleted before
        int startOffset = getCharIndex(startLine, columnOnStartLine);
        int endOffset = getCharIndex(endLine, columnOnEndLine);
        CharSequence oldString = subSequence(startOffset, endOffset);

        super.delete(startLine, columnOnStartLine, endLine, columnOnEndLine);

        Content newText = this;
        updateText(newText, startOffset, oldString, "", false, System.currentTimeMillis(),
                startOffset, endOffset - startOffset, startOffset);
    }

    private AtomicInteger getSequence() {
        if (sequence == null) {
            sequence = new AtomicInteger(0);
        }
        return sequence;
    }

    protected void updateText(@NonNull CharSequence text,
                              int offset,
                              @NonNull CharSequence oldString,
                              @NonNull CharSequence newString,
                              boolean wholeTextReplaced,
                              long newModificationStamp,
                              int initialStartOffset,
                              int initialOldLength,
                              int moveOffset) {
        assert moveOffset >= 0 && moveOffset <= length() : "Invalid moveOffset: " + moveOffset;
        ContentEvent event =
                new ContentEventImpl(this, offset, oldString, newString, modificationStamp,
                        wholeTextReplaced, initialStartOffset, initialOldLength, moveOffset);
        getSequence().incrementAndGet();

        CharSequence prevText = this;
        changedUpdate(event, newModificationStamp, prevText);
    }

    protected void changedUpdate(@NonNull ContentEvent event,
                                 long newModificationStamp,
                                 @NonNull CharSequence prevText) {
//        assert event.getOldFragment().length() == event.getOldLength();
//        assert event.getNewFragment().length() == event.getNewLength();
        if (contentListeners == null) {
            return;
        }
        for (ContentListener contentListener : contentListeners) {
            contentListener.contentChanged(event);
        }
    }

    @Override
    public void setData(String key, Object object) {
        dataMap.put(key, object);
    }

    @Override
    public Object getData(String key) {
        return dataMap.get(key);
    }

    @Override
    public void addContentListener(ContentListener listener) {
        contentListeners.add(listener);
    }

    @Override
    public void removeContentListener(ContentListener listener) {
        contentListeners.remove(listener);
    }

    @Override
    public void setModificationStamp(long stamp) {
        modificationStamp = stamp;
    }

    @Override
    public long getModificationStamp() {
        return modificationStamp;
    }
}
