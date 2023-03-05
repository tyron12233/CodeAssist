package com.tyron.builder.gradle.internal.dependency;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.gradle.api.TestedComponentIdentifier;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.File;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.component.Artifact;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;

/**
 * Implementation of a {@link ArtifactCollection} on top of another ArtifactCollection and a {@link
 * FileCollection} that contains extra artifacts.
 *
 * <p>Getting the list of ResolvedArtifactResult on this ArtifactCollection will fail.
 */
public class ArtifactCollectionWithExtraArtifact implements ArtifactCollection {

    public interface ExtraComponentIdentifier extends ComponentIdentifier {}

    private enum ExtraArtifactType {
        TEST,
        OTHER
    }

    /** the parent artifact collection */
    @NonNull private final ArtifactCollection parentArtifacts;

    /** extra artifact */
    @NonNull private final Provider<FileCollection> extraArtifact;

    /** extra artifact type */
    @NonNull private final ExtraArtifactType extraArtifactType;

    @NonNull private final String projectPath;
    @Nullable private final String variantName;

    @NonNull private final FileCollection combinedCollection;

    private Set<ResolvedArtifactResult> artifactResults = null;

    public static ArtifactCollectionWithExtraArtifact makeExtraCollectionForTest(
            @NonNull ArtifactCollection parentArtifacts,
            @NonNull FileCollection combinedCollection,
            @NonNull Provider<FileCollection> extraArtifact,
            @NonNull String projectPath,
            @Nullable String variantName) {
        return new ArtifactCollectionWithExtraArtifact(
                parentArtifacts,
                combinedCollection,
                extraArtifact,
                ExtraArtifactType.TEST,
                projectPath,
                variantName);
    }

    public static ArtifactCollectionWithExtraArtifact makeExtraCollection(
            @NonNull ArtifactCollection parentArtifacts,
            @NonNull FileCollection combinedCollection,
            @NonNull Provider<FileCollection> extraArtifact,
            @NonNull String projectPath) {

        return new ArtifactCollectionWithExtraArtifact(
                parentArtifacts,
                combinedCollection,
                extraArtifact,
                ExtraArtifactType.OTHER,
                projectPath,
                null);
    }

    private ArtifactCollectionWithExtraArtifact(
            @NonNull ArtifactCollection parentArtifacts,
            @NonNull FileCollection combinedCollection,
            @NonNull Provider<FileCollection> extraArtifact,
            @NonNull ExtraArtifactType extraArtifactType,
            @NonNull String projectPath,
            @Nullable String variantName) {
        this.parentArtifacts = parentArtifacts;
        this.extraArtifact = extraArtifact;
        this.extraArtifactType = extraArtifactType;
        this.projectPath = projectPath;
        this.variantName = variantName;
        this.combinedCollection = combinedCollection;
    }

    @Override
    public FileCollection getArtifactFiles() {
        return combinedCollection;
    }

    @Override
    public Set<ResolvedArtifactResult> getArtifacts() {
        if (artifactResults == null) {
            artifactResults = Sets.newLinkedHashSet();

            artifactResults.addAll(computeExtraArtifactResults());
            artifactResults.addAll(parentArtifacts.getArtifacts());
        }

        return artifactResults;
    }

    @Override
    public Collection<Throwable> getFailures() {
        return parentArtifacts.getFailures();
    }

    @NonNull
    @Override
    public Iterator<ResolvedArtifactResult> iterator() {
        return getArtifacts().iterator();
    }

    @Override
    public void forEach(Consumer<? super ResolvedArtifactResult> action) {
        getArtifacts().forEach(action);
    }

    @Override
    public Spliterator<ResolvedArtifactResult> spliterator() {
        return getArtifacts().spliterator();
    }

    /** Returns the base underlying {@link ArtifactCollection} without the extra artifact. */
    @NonNull
    public ArtifactCollection getParentArtifacts() {
        return parentArtifacts;
    }

    @NonNull
    private List<ResolvedArtifactResult> computeExtraArtifactResults() {
        Set<File> testedFiles = extraArtifact.get().getFiles();
        List<ResolvedArtifactResult> list = Lists.newArrayListWithCapacity(testedFiles.size());

        ExtraComponentArtifactIdentifier artifactId =
                new ExtraComponentArtifactIdentifier(
                        extraArtifactType == ExtraArtifactType.TEST
                                ? new TestedComponentIdentifierImpl(projectPath, variantName)
                                : new ExtraComponentIdentifierImpl(projectPath));

        for (File file : testedFiles) {
            list.add(new ExtraResolvedArtifactResult(file, artifactId));
        }

        return list;
    }

    @NonNull
    @SuppressWarnings({"UnstableApiUsage"})
    @Override
    public Provider<Set<ResolvedArtifactResult>> getResolvedArtifacts() {
        return parentArtifacts
                .getResolvedArtifacts()
                .zip(extraArtifact, (resolvedArtifactResults, files) -> getArtifacts());
    }

    private static final class ExtraResolvedArtifactResult implements ResolvedArtifactResult {

        @NonNull private final File artifactFile;
        @NonNull private final ExtraComponentArtifactIdentifier artifactId;

        private ExtraResolvedArtifactResult(
                @NonNull File artifactFile, @NonNull ExtraComponentArtifactIdentifier artifactId) {
            this.artifactFile = artifactFile;
            this.artifactId = artifactId;
        }

        @Override
        public File getFile() {
            return artifactFile;
        }

        @Override
        public ResolvedVariantResult getVariant() {
            throw new UnsupportedOperationException(
                    "Call to ExtraResolvedArtifactResult.getVariant is not allowed");
        }

        @Override
        public ComponentArtifactIdentifier getId() {
            return artifactId;
        }

        @Override
        public Class<? extends Artifact> getType() {
            throw new UnsupportedOperationException(
                    "Call to ExtraResolvedArtifactResult.getType is not allowed");
        }
    }

    private static final class ExtraComponentArtifactIdentifier
            implements ComponentArtifactIdentifier {

        @NonNull private final ComponentIdentifier id;

        public ExtraComponentArtifactIdentifier(@NonNull ComponentIdentifier id) {
            this.id = id;
        }

        @Override
        public ComponentIdentifier getComponentIdentifier() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return id.getDisplayName();
        }
    }

    public static final class TestedComponentIdentifierImpl
            implements TestedComponentIdentifier, ExtraComponentIdentifier {
        // this should be here to disambiguate between different component identifier
        private final String projectPath;
        @NonNull private final String variantName;

        public TestedComponentIdentifierImpl(
                @NonNull String projectPath, @NonNull String variantName) {
            this.projectPath = projectPath;
            this.variantName = variantName;
        }

        @Override
        @NonNull
        public String getVariantName() {
            return variantName;
        }

        @Override
        public String getDisplayName() {
            return "__tested_artifact__:" + projectPath;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TestedComponentIdentifierImpl that = (TestedComponentIdentifierImpl) o;
            return Objects.equals(projectPath, that.projectPath)
                    && Objects.equals(variantName, that.variantName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectPath, variantName);
        }
    }

    public static final class ExtraComponentIdentifierImpl implements ExtraComponentIdentifier {
        // this should be here to disambiguate between different component identifier
        private final String projectPath;

        public ExtraComponentIdentifierImpl(String projectPath) {
            this.projectPath = projectPath;
        }

        @Override
        public String getDisplayName() {
            return "__extra_artifact__:" + projectPath;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ExtraComponentIdentifierImpl that = (ExtraComponentIdentifierImpl) o;
            return Objects.equals(projectPath, that.projectPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectPath);
        }
    }
}
