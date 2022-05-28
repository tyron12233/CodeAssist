package com.tyron.builder.api.internal.artifacts.publish;

import com.tyron.builder.api.artifacts.ConfigurablePublishArtifact;
import com.tyron.builder.api.internal.tasks.TaskResolver;

import java.io.File;
import java.util.Date;

public class DefaultPublishArtifact extends AbstractPublishArtifact implements ConfigurablePublishArtifact {
    private String name;
    private String extension;
    private String type;
    private String classifier;
    private Date date;
    private File file;

    public DefaultPublishArtifact(TaskResolver resolver,
                                  String name, String extension, String type,
                                  String classifier, Date date, File file, Object... tasks) {
        super(resolver, tasks);
        this.name = name;
        this.extension = extension;
        this.type = type;
        this.date = date;
        this.classifier = classifier;
        this.file = file;
    }

    public DefaultPublishArtifact(String name, String extension, String type,
                                  String classifier, Date date, File file, Object... tasks) {
        super(tasks);
        this.name = name;
        this.extension = extension;
        this.type = type;
        this.date = date;
        this.classifier = classifier;
        this.file = file;
    }

    @Override
    public DefaultPublishArtifact builtBy(Object... tasks) {
        super.builtBy(tasks);
        return this;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getExtension() {
        return extension;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getClassifier() {
        return classifier;
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public Date getDate() {
        return date;
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
        return true;
    }
}
