package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.internal.fingerprint.AbsolutePathInputNormalizer;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.internal.tasks.properties.FileParameterUtils;
import com.tyron.builder.api.internal.tasks.properties.InputFilePropertyType;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.api.tasks.PathSensitivity;
import com.tyron.builder.api.tasks.TaskInputFilePropertyBuilder;

public class DefaultTaskInputFilePropertyRegistration extends AbstractTaskFilePropertyRegistration implements TaskInputFilePropertyRegistration {

    private final InputFilePropertyType filePropertyType;
    private boolean skipWhenEmpty;
    @SuppressWarnings("deprecation")
    private DirectorySensitivity directorySensitivity = DirectorySensitivity.UNSPECIFIED;
    private LineEndingSensitivity lineEndingSensitivity = LineEndingSensitivity.DEFAULT;
    private Class<? extends FileNormalizer> normalizer = AbsolutePathInputNormalizer.class;

    public DefaultTaskInputFilePropertyRegistration(StaticValue value, InputFilePropertyType filePropertyType) {
        super(value);
        this.filePropertyType = filePropertyType;
    }

    @Override
    public InputFilePropertyType getFilePropertyType() {
        return filePropertyType;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal withPropertyName(String propertyName) {
        setPropertyName(propertyName);
        return this;
    }

    @Override
    public boolean isSkipWhenEmpty() {
        return skipWhenEmpty;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal skipWhenEmpty(boolean skipWhenEmpty) {
        this.skipWhenEmpty = skipWhenEmpty;
        return this;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal skipWhenEmpty() {
        return skipWhenEmpty(true);
    }

    @Override
    public TaskInputFilePropertyBuilderInternal optional(boolean optional) {
        setOptional(optional);
        return this;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal optional() {
        return optional(true);
    }

    @Override
    public TaskInputFilePropertyBuilderInternal withPathSensitivity(PathSensitivity sensitivity) {
        return withNormalizer(FileParameterUtils.determineNormalizerForPathSensitivity(sensitivity));
    }

    @Override
    public TaskInputFilePropertyBuilderInternal withNormalizer(Class<? extends FileNormalizer> normalizer) {
        this.normalizer = normalizer;
        return this;
    }

    @Override
    public Class<? extends FileNormalizer> getNormalizer() {
        return normalizer;
    }

    @Override
    public DirectorySensitivity getDirectorySensitivity() {
        return directorySensitivity;
    }

    @Override
    public TaskInputFilePropertyBuilder ignoreEmptyDirectories() {
        this.directorySensitivity = DirectorySensitivity.IGNORE_DIRECTORIES;
        return this;
    }

    @Override
    public TaskInputFilePropertyBuilder ignoreEmptyDirectories(boolean ignoreDirectories) {
        this.directorySensitivity = ignoreDirectories ? DirectorySensitivity.IGNORE_DIRECTORIES : DirectorySensitivity.DEFAULT;
        return this;
    }

    @Override
    public LineEndingSensitivity getLineEndingNormalization() {
        return lineEndingSensitivity;
    }

    @Override
    public TaskInputFilePropertyBuilder normalizeLineEndings() {
        this.lineEndingSensitivity = LineEndingSensitivity.NORMALIZE_LINE_ENDINGS;
        return this;
    }

    @Override
    public TaskInputFilePropertyBuilder normalizeLineEndings(boolean ignoreLineEndings) {
        this.lineEndingSensitivity = ignoreLineEndings ? LineEndingSensitivity.NORMALIZE_LINE_ENDINGS : LineEndingSensitivity.DEFAULT;
        return this;
    }

    @Override
    public String toString() {
        return getPropertyName() + " (" + getNormalizer().getSimpleName().replace("Normalizer", "") + ")";
    }
}