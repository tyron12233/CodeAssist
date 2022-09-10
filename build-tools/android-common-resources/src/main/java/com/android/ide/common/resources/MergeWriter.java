package com.android.ide.common.resources;

import com.android.annotations.NonNull;
import com.android.ide.common.workers.WorkerExecutorFacade;
import java.io.File;
import java.io.Serializable;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;

/** A {@link MergeConsumer} that writes the result on the disk. */
public abstract class MergeWriter<I extends DataItem, U extends Serializable>
        implements MergeConsumer<I> {

    @NonNull
    private final File mRootFolder;
    @NonNull private final WorkerExecutorFacade mExecutor;

    public MergeWriter(
            @NonNull File rootFolder, @NonNull WorkerExecutorFacade workerExecutorFacade) {
        mRootFolder = rootFolder;
        mExecutor = workerExecutorFacade;
    }

    public void start(@NonNull DocumentBuilderFactory factory) throws ConsumerException {
    }

    @Override
    public void end() throws ConsumerException {
        try {
            postWriteAction();

            getExecutor().await();
        } catch (ConsumerException e) {
            throw e;
        } catch (Exception e) {
            throw new ConsumerException(e);
        }
    }

    /**
     * Called after all the items have been added/removed. This is called by {@link #end()}.
     *
     * @throws ConsumerException wrapper for any underlying exception.
     */
    protected void postWriteAction() throws ConsumerException {}

    @NonNull
    protected WorkerExecutorFacade getExecutor() {
        return mExecutor;
    }

    @NonNull
    protected File getRootFolder() {
        return mRootFolder;
    }
}
