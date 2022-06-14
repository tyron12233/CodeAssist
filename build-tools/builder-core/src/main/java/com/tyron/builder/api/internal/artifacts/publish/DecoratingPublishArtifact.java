package com.tyron.builder.api.internal.artifacts.publish;

import com.tyron.builder.api.artifacts.ConfigurablePublishArtifact;
import com.tyron.builder.api.artifacts.PublishArtifact;
import com.tyron.builder.api.internal.artifacts.PublishArtifactInternal;
import com.tyron.builder.util.internal.GUtil;

import java.io.File;
import java.util.Date;

public class DecoratingPublishArtifact extends AbstractPublishArtifact implements ConfigurablePublishArtifact {
    private String name;
    private String extension;
    private String type;
    private String classifier;
    private final PublishArtifact publishArtifact;
    private boolean classifierSet;

    public DecoratingPublishArtifact(PublishArtifact publishArtifact) {
        super(publishArtifact.getBuildDependencies());
        this.publishArtifact = publishArtifact;
    }

    public PublishArtifact getPublishArtifact() {
        return publishArtifact;
    }

    @Override
    public DecoratingPublishArtifact builtBy(Object... tasks) {
        super.builtBy(tasks);
        return this;
    }

    @Override
    public String getName() {
        return GUtil.getOrDefault(name, publishArtifact::getName);
    }

    @Override
    public String getExtension() {
        return GUtil.getOrDefault(extension, publishArtifact::getExtension);
    }

    @Override
    public String getType() {
        return GUtil.getOrDefault(type, publishArtifact::getType);
    }

    @Override
    public String getClassifier() {
        if (classifierSet) {
            return classifier;
        }
        return publishArtifact.getClassifier();
    }

    @Override
    public File getFile() {
        return publishArtifact.getFile();
    }

    @Override
    public Date getDate() {
        return publishArtifact.getDate();
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
        this.classifierSet = true;
    }

    @Override
    public boolean shouldBePublished() {
        return PublishArtifactInternal.shouldBePublished(publishArtifact);
    }
}
