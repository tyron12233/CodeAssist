package com.tyron.editor;

import com.google.common.collect.Maps;
import com.tyron.editor.event.ContentEvent;
import com.tyron.editor.event.ContentListener;
import com.tyron.editor.event.impl.ContentEventImpl;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AbstractContent implements Content {

    private final Map<String, Object> dataMap = Maps.newConcurrentMap();
    private final List<ContentListener> contentListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private long modificationStamp = 0;

    @Override
    public void setData(String key, Object object) {
        dataMap.put(key, object);
    }

    @Override
    public Object getData(String key) {
        return dataMap.get(key);
    }

    protected void updateText(
            @NotNull CharSequence text,
            int offset,
            @NotNull CharSequence oldString,
            @NotNull CharSequence newString,
            boolean wholeTextReplaced,
            long newModificationStamp,
            int initialStartOffset,
            int initialOldLength,
            int moveOffset
    ) {
        assert moveOffset >= 0 && moveOffset <= getTextLength() : "Invalid moveOffset: " + moveOffset;
        ContentEvent event = new ContentEventImpl(this, offset, oldString, newString, modificationStamp, wholeTextReplaced,
                initialStartOffset, initialOldLength, moveOffset);
        sequence.incrementAndGet();

        CharSequence prevText = this;
        changedUpdate(event, newModificationStamp, prevText);
    }

    protected int getTextLength() {
        return length();
    }

    protected void changedUpdate(@NotNull ContentEvent event, long newModificationStamp, @NotNull CharSequence prevText) {
        assert event.getOldFragment().length() == event.getOldLength();
        assert event.getNewFragment().length() == event.getNewLength();

        for (ContentListener contentListener : contentListeners) {
            contentListener.contentChanged(event);
        }
    }

    @Override
    public void setModificationStamp(long modificationStamp) {
        this.modificationStamp = modificationStamp;
    }

    @Override
    public long getModificationStamp() {
        return modificationStamp;
    }

    @Override
    public void addContentListener(@NotNull ContentListener listener) {
        contentListeners.add(listener);
    }

    @Override
    public void removeContentListener(@NotNull ContentListener listener) {
        contentListeners.remove(listener);
    }
}
