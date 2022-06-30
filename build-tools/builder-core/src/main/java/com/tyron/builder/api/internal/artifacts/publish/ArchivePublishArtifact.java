package com.tyron.builder.api.internal.artifacts.publish;

import com.tyron.builder.api.artifacts.ConfigurablePublishArtifact;
import com.tyron.builder.api.tasks.bundling.AbstractArchiveTask;
import com.tyron.builder.util.internal.GUtil;

import java.io.File;
import java.util.Date;

public class ArchivePublishArtifact extends AbstractPublishArtifact implements ConfigurablePublishArtifact {
    private String name;
    private String extension;
    private String type;
    private String classifier;
    private Date date;
    private File file;

    private AbstractArchiveTask archiveTask;

    public ArchivePublishArtifact(AbstractArchiveTask archiveTask) {
        super(archiveTask);
        this.archiveTask = archiveTask;
    }

    @Override
    public ArchivePublishArtifact builtBy(Object... tasks) {
        super.builtBy(tasks);
        return this;
    }

    @Override
    public String getName() {
        if (name != null) {
            return name;
        }
        String baseName = archiveTask.getArchiveBaseName().getOrNull();
        if (baseName != null) {
            return withAppendix(baseName);
        }
        return archiveTask.getArchiveAppendix().getOrNull();
    }

    private String withAppendix(String baseName) {
        String appendix = archiveTask.getArchiveAppendix().getOrNull();
        return baseName + (GUtil.isTrue(appendix)? "-" + appendix : "");
    }

    @Override
    public String getExtension() {
        return GUtil.getOrDefault(extension, () -> archiveTask.getArchiveExtension().getOrNull());
    }

    @Override
    public String getType() {
        return GUtil.getOrDefault(type, () -> archiveTask.getArchiveExtension().getOrNull());
    }

    @Override
    public String getClassifier() {
        return GUtil.getOrDefault(classifier, () -> archiveTask.getArchiveClassifier().getOrNull());
    }

    @Override
    public File getFile() {
        return GUtil.getOrDefault(file, () -> archiveTask.getArchiveFile().get().getAsFile());
    }

    @Override
    public Date getDate() {
        return GUtil.getOrDefault(date, () -> new Date(archiveTask.getArchiveFile().get().getAsFile().lastModified()));
    }

    public AbstractArchiveTask getArchiveTask() {
        return archiveTask;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public void setExtension(String extension) {
        this.extension = extension;
    }

    @Override
    public void setType(String type) {
        this.type = type;
    }

    @Override
    public void setClassifier(String classifier) {
        this.classifier = classifier;
    }

    public void setDate(Date date) {
        this.date = date;
    }

    public void setFile(File file) {
        this.file = file;
    }

    @Override
    public boolean shouldBePublished() {
        throw new UnsupportedOperationException();
//        return archiveTask.();
    }
}
