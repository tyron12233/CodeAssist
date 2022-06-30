package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.file.FileCopyDetails;
import com.tyron.builder.api.file.RelativePath;

public class RenamingCopyAction implements Action<FileCopyDetails> {
    private final Transformer<String, String> transformer;

    public RenamingCopyAction(Transformer<String, String> transformer) {
        this.transformer = transformer;
    }

    @Override
    public void execute(FileCopyDetails fileCopyDetails) {
        RelativePath path = fileCopyDetails.getRelativePath();
        String newName = transformer.transform(path.getLastName());
        if (newName != null) {
            path = path.replaceLastName(newName);
            fileCopyDetails.setRelativePath(path);
        }
    }
}
