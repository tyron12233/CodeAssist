package com.android.ide.common.resources;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.blame.Message;
import com.android.ide.common.blame.SourceFile;
import com.android.ide.common.blame.SourceFilePosition;
import com.android.ide.common.blame.SourcePosition;
import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;
import java.io.File;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;

/**
 * A consumer of merges. Used with {@link DataMerger#mergeData(MergeConsumer, boolean)}.
 */
public interface MergeConsumer<I extends DataItem> {

    /**
     * An exception thrown during resource merging by the consumer. It always contains the original
     * exception as its cause.
     */
    class ConsumerException extends MergingException {

        public ConsumerException(@NonNull Throwable cause) {
            this(cause, SourceFile.UNKNOWN);
        }

        public ConsumerException(@NonNull Throwable cause, @NonNull File file) {
            this(cause, new SourceFile(file));
        }

        private ConsumerException(@NonNull Throwable cause, @NonNull SourceFile file) {
            super(
                    cause,
                    new Message(
                            Message.Kind.ERROR,
                            MoreObjects.firstNonNull(
                                    cause.getLocalizedMessage(),
                                    cause.getClass().getCanonicalName()),
                            Throwables.getStackTraceAsString(cause),
                            RESOURCE_ASSET_MERGER_TOOL_NAME,
                            new SourceFilePosition(file, SourcePosition.UNKNOWN)));
        }
    }

    /**
     * Called before the merge starts.
     */
    void start(@NonNull DocumentBuilderFactory factory) throws ConsumerException;

    /**
     * Called after the merge ends.
     */
    void end() throws ConsumerException;

    /**
     * Adds an item. The item may already be existing. Calling {@link DataItem#isTouched()} will
     * indicate whether the item actually changed.
     *
     * @param item the new item.
     */
    void addItem(@NonNull I item) throws ConsumerException;

    /**
     * Removes an item. Optionally pass the item that will replace this one. This methods does not
     * do the replacement. The replaced item is just there in case the removal can be optimized when
     * it's a replacement vs. a removal.
     *
     * @param removedItem the removed item.
     * @param replacedBy  the optional item that replaces the removed item.
     */
    void removeItem(@NonNull I removedItem, @Nullable I replacedBy) throws ConsumerException;

    boolean ignoreItemInMerge(I item);

}
