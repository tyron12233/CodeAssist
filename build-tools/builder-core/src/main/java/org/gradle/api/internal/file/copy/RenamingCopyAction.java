package org.gradle.api.internal.file.copy;

import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.RelativePath;

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
