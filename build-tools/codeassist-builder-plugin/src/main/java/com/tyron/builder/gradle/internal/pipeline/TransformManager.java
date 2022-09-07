package com.tyron.builder.gradle.internal.pipeline;

import static com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.CLASSES;
import static com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.RESOURCES;

import com.android.annotations.NonNull;
import com.tyron.builder.api.transform.QualifiedContent;
import com.tyron.builder.api.transform.QualifiedContent.ContentType;
import com.tyron.builder.api.transform.QualifiedContent.Scope;
import com.tyron.builder.api.transform.QualifiedContent.ScopeType;
import com.tyron.builder.api.transform.Transform;
import com.tyron.builder.gradle.internal.InternalScope;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.tasks.factory.TaskFactory;
import com.tyron.builder.errors.IssueReporter;
import com.tyron.builder.errors.IssueReporter.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskProvider;

/**
 * Manages the transforms for a variant.
 *
 * <p>The actual execution is handled by Gradle through the tasks.
 * Instead it's a means to more easily configure a series of transforms that consume each other's
 * inputs when several of these transform are optional.
 */
public class TransformManager extends FilterableStreamCollection {

    private static final boolean DEBUG = true;

    private static final String FD_TRANSFORMS = "transforms";

    public static final Set<ScopeType> EMPTY_SCOPES = ImmutableSet.of();

    public static final Set<ContentType> CONTENT_CLASS = ImmutableSet.of(CLASSES);
    public static final Set<ContentType> CONTENT_JARS = ImmutableSet.of(CLASSES, RESOURCES);
    public static final Set<ContentType> CONTENT_RESOURCES = ImmutableSet.of(RESOURCES);
    public static final Set<ContentType> CONTENT_DEX = ImmutableSet.of(ExtendedContentType.DEX);
    public static final Set<ContentType> CONTENT_DEX_WITH_RESOURCES =
            ImmutableSet.of(ExtendedContentType.DEX, RESOURCES);
    public static final Set<ScopeType> PROJECT_ONLY = ImmutableSet.of(Scope.PROJECT);
    public static final Set<ScopeType> SCOPE_FULL_PROJECT =
            ImmutableSet.of(Scope.PROJECT, Scope.SUB_PROJECTS, Scope.EXTERNAL_LIBRARIES);
    public static final Set<ScopeType> SCOPE_FULL_WITH_FEATURES =
            new ImmutableSet.Builder<ScopeType>()
                    .addAll(SCOPE_FULL_PROJECT)
                    .add(InternalScope.FEATURES)
                    .build();
    public static final Set<ScopeType> SCOPE_FEATURES = ImmutableSet.of(InternalScope.FEATURES);
    public static final Set<ScopeType> SCOPE_FULL_LIBRARY_WITH_LOCAL_JARS =
            ImmutableSet.of(Scope.PROJECT, InternalScope.LOCAL_DEPS);
    public static final Set<ScopeType> SCOPE_FULL_PROJECT_WITH_LOCAL_JARS =
            new ImmutableSet.Builder<ScopeType>()
                    .addAll(SCOPE_FULL_PROJECT)
                    .add(InternalScope.LOCAL_DEPS)
                    .build();

    @NonNull
    private final Project project;
    @NonNull private final IssueReporter issueReporter;
    @NonNull private final Logger logger;

    /**
     * These are the streams that are available for new Transforms to consume.
     *
     * <p>Once a new transform is added, the streams that it consumes are removed from this list,
     * and the streams it produces are put instead.
     *
     * <p>When all the transforms have been added, the remaining streams should be consumed by
     * standard Tasks somehow.
     *
     * @see #getStreams(StreamFilter)
     */
    @NonNull private final List<TransformStream> streams = Lists.newArrayList();
    @NonNull
    private final List<Transform> transforms = Lists.newArrayList();

    public TransformManager(
            @NonNull Project project,
            @NonNull IssueReporter issueReporter) {
        this.project = project;
        this.issueReporter = issueReporter;
        this.logger = Logging.getLogger(TransformManager.class);
    }

    @NonNull
    @Override
    Project getProject() {
        return project;
    }

    public void addStream(@NonNull TransformStream stream) {
        streams.add(stream);
    }

    /**
     * Adds a Transform.
     *
     * <p>This makes the current transform consumes whatever Streams are currently available and
     * creates new ones for the transform output.
     *
     * <p>This also creates a {@link TransformTask} to run the transform and wire it up with the
     * dependencies of the consumed streams.
     *
     * @param taskFactory the task factory
     * @param creationConfig the current scope
     * @param transform the transform to add
     * @param <T> the type of the transform
     * @return {@code Optional<AndroidTask<Transform>>} containing the AndroidTask if it was able to
     *     create it
     */
    @NonNull
    public <T extends Transform> Optional<TaskProvider<TransformTask>> addTransform(
            @NonNull TaskFactory taskFactory,
            @NonNull ComponentCreationConfig creationConfig,
            @NonNull T transform) {

        return Optional.empty();
    }


    @Override
    @NonNull
    public List<TransformStream> getStreams() {
        return streams;
    }

    /**
     * <p>This method will remove all streams matching the specified scopes and types from the
     * available streams.
     *
     * @deprecated Use this method only for migration from transforms to tasks.
     */
    @Deprecated
    public void consumeStreams(
            @NonNull Set<? super Scope> requestedScopes, @NonNull Set<ContentType> requestedTypes) {
        consumeStreams(requestedScopes, requestedTypes, new ArrayList<>());
    }

    private void consumeStreams(
            @NonNull Set<? super Scope> requestedScopes,
            @NonNull Set<ContentType> requestedTypes,
            @NonNull List<TransformStream> inputStreams) {
        // list to hold the list of unused streams in the manager after everything is done.
        // they'll be put back in the streams collection, along with the new outputs.
        List<TransformStream> oldStreams = Lists.newArrayListWithExpectedSize(streams.size());

        for (TransformStream stream : streams) {
            // streams may contain more than we need. In this case, we'll make a copy of the stream
            // with the remaining types/scopes. It'll be up to the TransformTask to make
            // sure that the content of the stream is usable (for instance when a stream
            // may contain two scopes, these scopes could be combined or not, impacting consumption)
            Set<ContentType> availableTypes = stream.getContentTypes();
            Set<? super Scope> availableScopes = stream.getScopes();

            Set<ContentType> commonTypes = Sets.intersection(requestedTypes,
                    availableTypes);
            Set<? super Scope> commonScopes = Sets.intersection(requestedScopes, availableScopes);
            if (!commonTypes.isEmpty() && !commonScopes.isEmpty()) {

                // check if we need to make another stream from this one with less scopes/types.
                if (!commonScopes.equals(availableScopes) || !commonTypes.equals(availableTypes)) {
                    // first the stream that gets consumed. It consumes only the common types/scopes
                    inputStreams.add(stream.makeRestrictedCopy(commonTypes, commonScopes));

                    // Now we could have two more streams. One with the requestedScope but the remainingTypes, and the other one with the remaining scopes and all the types.
                    // compute remaining scopes/types.
                    Sets.SetView<ContentType> remainingTypes =
                            Sets.difference(availableTypes, commonTypes);
                    Sets.SetView<? super Scope> remainingScopes = Sets.difference(availableScopes, commonScopes);

                    if (!remainingTypes.isEmpty()) {
                        oldStreams.add(
                                stream.makeRestrictedCopy(
                                        remainingTypes.immutableCopy(), availableScopes));
                    }
                    if (!remainingScopes.isEmpty()) {
                        oldStreams.add(
                                stream.makeRestrictedCopy(
                                        availableTypes, remainingScopes.immutableCopy()));
                    }
                } else {
                    // stream is an exact match (or at least subset) for the request,
                    // so we add it as it.
                    inputStreams.add(stream);
                }
            } else {
                // stream is not used, keep it around.
                oldStreams.add(stream);
            }
        }

        // update the list of available streams.
        streams.clear();
        streams.addAll(oldStreams);
    }

    @NonNull
    private List<TransformStream> grabReferencedStreams(@NonNull Transform transform) {
        Set<? super Scope> requestedScopes = transform.getReferencedScopes();
        if (requestedScopes.isEmpty()) {
            return ImmutableList.of();
        }

        List<TransformStream> streamMatches = Lists.newArrayListWithExpectedSize(streams.size());

        Set<ContentType> requestedTypes = transform.getInputTypes();
        for (TransformStream stream : streams) {
            // streams may contain more than we need. In this case, we'll provide the whole
            // stream as-is since it's not actually consumed.
            // It'll be up to the TransformTask to make sure that the content of the stream is
            // usable (for instance when a stream
            // may contain two scopes, these scopes could be combined or not, impacting consumption)
            Set<ContentType> availableTypes = stream.getContentTypes();
            Set<? super Scope> availableScopes = stream.getScopes();

            Set<ContentType> commonTypes = Sets.intersection(requestedTypes,
                    availableTypes);
            Set<? super Scope> commonScopes = Sets.intersection(requestedScopes, availableScopes);

            if (!commonTypes.isEmpty() && !commonScopes.isEmpty()) {
                streamMatches.add(stream);
            }
        }

        return streamMatches;
    }

    private boolean validateTransform(@NonNull Transform transform) {
        // check the content type are of the right Type.
        if (!checkContentTypes(transform.getInputTypes(), transform)
                || !checkContentTypes(transform.getOutputTypes(), transform)) {
            return false;
        }

        // check some scopes are not consumed.
        Set<? super Scope> scopes = transform.getScopes();
        if (scopes.contains(Scope.PROVIDED_ONLY)) {
            issueReporter.reportError(
                    Type.GENERIC,
                    String.format(
                            "PROVIDED_ONLY scope cannot be consumed by Transform '%1$s'",
                            transform.getName()));
            return false;
        }
        if (scopes.contains(Scope.TESTED_CODE)) {
            issueReporter.reportError(
                    Type.GENERIC,
                    String.format(
                            "TESTED_CODE scope cannot be consumed by Transform '%1$s'",
                            transform.getName()));
            return false;
        }

        if (!transform
                .getClass()
                .getCanonicalName()
                .startsWith("com.tyron.builder.gradle.internal.transforms")) {
            checkScopeDeprecation(transform.getScopes(), transform.getName());
            checkScopeDeprecation(transform.getReferencedScopes(), transform.getName());
        }

        return true;
    }

    @SuppressWarnings("deprecation")
    private void checkScopeDeprecation(
            @NonNull Set<? super Scope> scopes, @NonNull String transformName) {
        if (scopes.contains(Scope.PROJECT_LOCAL_DEPS)) {
            final String message =
                    String.format(
                            "Transform '%1$s' uses scope %2$s which is deprecated and replaced with %3$s",
                            transformName,
                            Scope.PROJECT_LOCAL_DEPS.name(),
                            Scope.EXTERNAL_LIBRARIES.name());
            if (!scopes.contains(Scope.EXTERNAL_LIBRARIES)) {
                issueReporter.reportError(Type.GENERIC, message);
            }
        }

        if (scopes.contains(Scope.SUB_PROJECTS_LOCAL_DEPS)) {
            final String message =
                    String.format(
                            "Transform '%1$s' uses scope %2$s which is deprecated and replaced with %3$s",
                            transformName,
                            Scope.SUB_PROJECTS_LOCAL_DEPS.name(),
                            Scope.EXTERNAL_LIBRARIES.name());
            if (!scopes.contains(Scope.EXTERNAL_LIBRARIES)) {
                issueReporter.reportError(Type.GENERIC, message);
            }
        }
    }

    private boolean checkContentTypes(
            @NonNull Set<ContentType> contentTypes,
            @NonNull Transform transform) {
        for (ContentType contentType : contentTypes) {
            if (!(contentType instanceof QualifiedContent.DefaultContentType
                    || contentType instanceof ExtendedContentType)) {
                issueReporter.reportError(
                        Type.GENERIC,
                        String.format(
                                "Custom content types (%1$s) are not supported in transforms (%2$s)",
                                contentType.getClass().getName(), transform.getName()));
                return false;
            }
        }
        return true;
    }
}
