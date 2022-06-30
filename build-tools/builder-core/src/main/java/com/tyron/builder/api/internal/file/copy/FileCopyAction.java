package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.internal.file.CopyActionProcessingStreamAction;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.WorkResults;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.util.internal.GFileUtils;

import java.io.File;
import java.util.Objects;

public class FileCopyAction implements CopyAction {

    private final PathToFileResolver fileResolver;

    public FileCopyAction(PathToFileResolver fileResolver) {
        this.fileResolver = fileResolver;
    }

    @Override
    public WorkResult execute(CopyActionProcessingStream stream) {
        FileCopyDetailsInternalAction action = new FileCopyDetailsInternalAction();
        stream.process(action);
        return WorkResults.didWork(action.didWork);
    }

    private class FileCopyDetailsInternalAction implements CopyActionProcessingStreamAction {
        private boolean didWork;

        @Override
        public void processFile(FileCopyDetailsInternal details) {
            File target = fileResolver.resolve(details.getRelativePath().getPathString());
            renameIfCaseChanged(target);
            boolean copied = details.copyTo(target);
            if (copied) {
                didWork = true;
            }
        }

        private void renameIfCaseChanged(File target) {
            if (target.exists()) {
                File canonicalizedTarget = GFileUtils.canonicalize(target);
                if (!Objects.equals(target.getName(), canonicalizedTarget.getName())) {
                    canonicalizedTarget.renameTo(target);
                }
            }
        }
    }
}
