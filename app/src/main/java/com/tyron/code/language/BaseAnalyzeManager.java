package com.tyron.code.language;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.StyleReceiver;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

/**
 * Built-in implementation of {@link AnalyzeManager}.
 * <p>
 * This is a simple version without any incremental actions.
 * <p>
 * The analysis will always re-run when the text changes. Hopefully, it will stop previous outdated
 * runs by provide a {@link io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager.Delegate}
 * object.
 *
 * @param <V> The shared object type that we get for auto-completion.
 */
public abstract class BaseAnalyzeManager<V> implements AnalyzeManager {

    private final static String LOG_TAG = "SimpleAnalyzeManager";
    private static int sThreadId = 0;

    private StyleReceiver receiver;
    private ContentReference ref;
    private Bundle extraArguments;
    private volatile long newestRequestId;
    private final Object lock = new Object();
    private V data;

    private AnalyzeThread thread;

    @Override
    public void setReceiver(@Nullable StyleReceiver receiver) {
        this.receiver = receiver;
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        this.ref = content;
        this.extraArguments = extraArguments;
        rerun();
    }

    @Override
    public void insert(CharPosition start, CharPosition end, CharSequence insertedContent) {
        rerun();
    }

    @Override
    public void delete(CharPosition start, CharPosition end, CharSequence deletedContent) {
        rerun();
    }

    @Override
    public synchronized void rerun() {
        newestRequestId++;
        if (thread == null || !thread.isAlive()) {
            // Create new thread
            Log.v(LOG_TAG, "Starting a new thread for analysis");
            thread = new AnalyzeThread();
            thread.setDaemon(true);
            thread.setName("SplAnalyzer-" + nextThreadId());
            thread.start();
        }
        synchronized (lock) {
            lock.notify();
        }
    }

    @Override
    public void destroy() {
        ref = null;
        extraArguments = null;
        newestRequestId = 0;
        data = null;
        if (thread != null && thread.isAlive()) {
            thread.cancel();
        }
        thread = null;
    }

    private synchronized static int nextThreadId() {
        sThreadId++;
        return sThreadId;
    }

    /**
     * Get extra arguments set by
     * {@link io.github.rosemoe.sora.widget.CodeEditor#setText(CharSequence, Bundle)}
     */
    public Bundle getExtraArguments() {
        return extraArguments;
    }

    /**
     * Get data set by analyze thread
     */
    @Nullable
    public V getData() {
        return data;
    }

    public AnalyzeThread getAnalyzeThread() {
        return thread;
    }

    /**
     * Analyze the given input.
     *
     * @param text     A {@link StringBuilder} instance containing the text in editor. DO NOT
     *                 SAVE THE INSTANCE OR
     *                 UPDATE IT. It is continuously used by this analyzer.
     * @param delegate A delegate used to check whether this invocation is outdated. You should
     *                 stop your logic
     *                 if {@link Delegate#isCancelled()} returns true.
     * @return Styles created according to the text.
     */
    protected abstract Styles analyze(StringBuilder text, Delegate<V> delegate);

    /**
     * Analyze thread.
     * <p>
     * The thread will keep alive unless there is any exception or {@link AnalyzeManager#destroy()}
     * is called.
     */
    private class AnalyzeThread extends Thread {

        private volatile boolean cancelled = false;
        /**
         * Single instance for text storing
         */
        private final StringBuilder textContainer = new StringBuilder();

        public void cancel() {
            cancelled = true;
        }

        @Override
        public void run() {
            Log.v(LOG_TAG, "Analyze thread started");
            try {
                while (!cancelled) {
                    final ContentReference text = ref;
                    if (text != null) {
                        long requestId = 0L;
                        Styles result;
                        V newData;
                        // Do the analysis, until the requestId matches
                        do {
                            requestId = newestRequestId;
                            Delegate<V> delegate = new Delegate<>(requestId);

                            // Collect line contents
                            textContainer.setLength(0);
                            textContainer.ensureCapacity(text.length());
                            for (int i = 0; i < text.getLineCount() &&
                                            requestId == newestRequestId; i++) {
                                if (i != 0) {
                                    textContainer.append('\n');
                                }
                                text.appendLineTo(textContainer, i);
                            }

                            // Invoke the implementation
                            result = analyze(textContainer, delegate);

                            newData = delegate.data;
                        } while (requestId != newestRequestId);
                        // Send result
                        final StyleReceiver receiver = BaseAnalyzeManager.this.receiver;
                        if (receiver != null) {
                            receiver.setStyles(BaseAnalyzeManager.this, result);
                        }
                        data = newData;
                    }
                    // Wait for next time
                    synchronized (lock) {
                        lock.wait();
                    }
                }
            } catch (InterruptedException e) {
                Log.v(LOG_TAG, "Thread is interrupted.");
            } catch (Exception e) {
                Log.e(LOG_TAG, "Unexpected exception is thrown in the thread.", e);
            }
        }

    }

    /**
     * Delegate between manager and analysis implementation
     */
    public final class Delegate<T> {

        private final long myRequestId;
        private T data;

        public Delegate(long requestId) {
            myRequestId = requestId;
        }

        /**
         * Set shared data
         */
        public void setData(T value) {
            data = value;
        }

        /**
         * Check whether the operation is cancelled
         */
        public boolean isCancelled() {
            return myRequestId != newestRequestId;
        }
    }
}
