package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.tyron.builder.api.transform.QualifiedContent;
import com.tyron.builder.api.transform.SecondaryFile;
import com.tyron.builder.api.transform.SecondaryInput;
import com.tyron.builder.api.transform.Status;
import com.tyron.builder.api.transform.TransformException;
import com.tyron.builder.api.transform.TransformInput;
import com.android.ide.common.util.ReferenceHolder;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;

@CacheableTask
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.COMPILED_CLASSES, secondaryTaskCategories = {TaskCategory.SOURCE_PROCESSING})
public abstract class IncrementalTransformTask extends TransformTask {
    @TaskAction
    void transform(final IncrementalTaskInputs incrementalTaskInputs)
            throws IOException, TransformException, InterruptedException {

        final ReferenceHolder<List<TransformInput>> consumedInputs = ReferenceHolder.empty();
        final ReferenceHolder<List<TransformInput>> referencedInputs = ReferenceHolder.empty();
        final ReferenceHolder<Boolean> isIncremental = ReferenceHolder.empty();
        final ReferenceHolder<Collection<SecondaryInput>> changedSecondaryInputs =
                ReferenceHolder.empty();

        isIncremental.setValue(
                getTransform().isIncremental() && incrementalTaskInputs.isIncremental());

//        GradleTransformExecution preExecutionInfo =
//                GradleTransformExecution.newBuilder()
//                        .setType(
//                                AnalyticsUtil.getTransformType(getTransform().getClass())
//                                        .getNumber())
//                        .setIsIncremental(isIncremental.getValue())
//                        .setTransformClassName(getTransform().getClass().getName())
//                        .build();
//
//        AnalyticsService analyticsService = getAnalyticsService().get();
//        analyticsService.recordBlock(
//                GradleBuildProfileSpan.ExecutionType.TASK_TRANSFORM_PREPARATION,
//                preExecutionInfo,
//                getProjectPath().get(),
//                getVariantName(),
//                new Recorder.VoidBlock() {
//                    @Override
//                    public void call() {
//
//
//                    }
//                });

        Map<File, Status> changedMap = Maps.newHashMap();
        Set<File> removedFiles = Sets.newHashSet();
        if (isIncremental.getValue()) {
            // gather the changed files first.
            gatherChangedFiles(
                    getLogger(), incrementalTaskInputs, changedMap, removedFiles);

            // and check against secondary files, which disables
            // incremental mode.
            isIncremental.setValue(checkSecondaryFiles(changedMap, removedFiles));
        }

        if (isIncremental.getValue()) {
            // ok create temporary incremental data
            List<IncrementalTransformInput> incInputs =
                    createIncrementalInputs(consumedInputStreams);
            List<IncrementalTransformInput> incReferencedInputs =
                    createIncrementalInputs(referencedInputStreams);

            // then compare to changed list and create final Inputs
            if (isIncremental.setValue(
                    updateIncrementalInputsWithChangedFiles(
                            incInputs,
                            incReferencedInputs,
                            changedMap,
                            removedFiles))) {
                consumedInputs.setValue(convertToImmutable(incInputs));
                referencedInputs.setValue(convertToImmutable(incReferencedInputs));
            }
        }

        // at this point if we do not have incremental mode, got with
        // default TransformInput with no inc data.
        if (!isIncremental.getValue()) {
            consumedInputs.setValue(
                    computeNonIncTransformInput(consumedInputStreams));
            referencedInputs.setValue(
                    computeNonIncTransformInput(referencedInputStreams));
            changedSecondaryInputs.setValue(ImmutableList.of());
        } else {
            // gather all secondary input changes.
            changedSecondaryInputs.setValue(
                    gatherSecondaryInputChanges(changedMap, removedFiles));
        }

        runTransform(
                consumedInputs.getValue(),
                referencedInputs.getValue(),
                isIncremental.getValue(),
                changedSecondaryInputs.getValue()
//                preExecutionInfo,
//                analyticsService
        );
    }

    private Collection<SecondaryInput> gatherSecondaryInputChanges(
            Map<File, Status> changedMap, Set<File> removedFiles) {

        ImmutableList.Builder<SecondaryInput> builder = ImmutableList.builder();
        for (final SecondaryFile secondaryFile : getAllSecondaryInputs()) {
            for (File file : getSecondaryInputFiles(secondaryFile)) {
                final Status status =
                        changedMap.containsKey(file)
                                ? changedMap.get(file)
                                : removedFiles.contains(file) ? Status.REMOVED : Status.NOTCHANGED;

                builder.add(
                        new SecondaryInput() {
                            @Override
                            public SecondaryFile getSecondaryInput() {
                                return secondaryFile;
                            }

                            @Override
                            public Status getStatus() {
                                return status;
                            }
                        });
            }
        }
        return builder.build();
    }
    /** Returns a list of IncrementalTransformInput for all the inputs. */
    @NonNull
    private static List<IncrementalTransformInput> createIncrementalInputs(
            @NonNull Collection<TransformStream> streams) {
        return streams.stream()
                .map(TransformStream::asIncrementalInput)
                .collect(Collectors.toList());
    }

    private synchronized Collection<SecondaryFile> getAllSecondaryInputs() {
        if (secondaryFiles == null) {
            ImmutableList.Builder<SecondaryFile> builder = ImmutableList.builder();
            builder.addAll(getTransform().getSecondaryFiles());
            //noinspection deprecation
            builder.addAll(
                    getTransform().getSecondaryFileInputs().stream()
                            .map(SecondaryFile::nonIncremental)
                            .iterator());
            secondaryFiles = builder.build();
        }
        return secondaryFiles;
    }

    private static void gatherChangedFiles(
            @NonNull Logger logger,
            @NonNull IncrementalTaskInputs incrementalTaskInputs,
            @NonNull final Map<File, Status> changedFileMap,
            @NonNull final Set<File> removedFiles) {
        logger.info("Transform inputs calculations based on following changes");
        incrementalTaskInputs.outOfDate(
                inputFileDetails -> {
                    logger.info(
                            inputFileDetails.getFile().getAbsolutePath()
                                    + ":"
                                    + IntermediateFolderUtils.inputFileDetailsToStatus(
                                            inputFileDetails));
                    if (inputFileDetails.isAdded()) {
                        changedFileMap.put(inputFileDetails.getFile(), Status.ADDED);
                    } else if (inputFileDetails.isModified()) {
                        changedFileMap.put(inputFileDetails.getFile(), Status.CHANGED);
                    }
                });

        incrementalTaskInputs.removed(
                inputFileDetails -> {
                    logger.info(inputFileDetails.getFile().getAbsolutePath() + ":REMOVED");
                    removedFiles.add(inputFileDetails.getFile());
                });
    }

    private boolean checkSecondaryFiles(
            @NonNull Map<File, Status> changedMap, @NonNull Set<File> removedFiles) {

        for (SecondaryFile secondaryFile : getAllSecondaryInputs()) {
            Set<File> files = getSecondaryInputFiles(secondaryFile);
            if ((!Sets.intersection(files, changedMap.keySet()).isEmpty()
                            || !Sets.intersection(files, removedFiles).isEmpty())
                    && !secondaryFile.supportsIncrementalBuild()) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    private Set<File> getSecondaryInputFiles(SecondaryFile secondaryFile) {
        Set<File> secondaryInputFiles = Sets.newHashSet();
        FileCollection secondaryInputFileCollection = secondaryFile.getFileCollection();
        if (secondaryInputFileCollection != null) {
            secondaryInputFiles.addAll(secondaryInputFileCollection.getFiles());
        } else {
            secondaryInputFiles.add(secondaryFile.getFile());
        }
        return secondaryInputFiles;
    }

    private boolean isSecondaryFile(File file) {
        for (FileCollection secondaryFileInput : getSecondaryFileInputs()) {
            if (secondaryFileInput.contains(file)) {
                return true;
            }
        }
        return false;
    }

    private boolean updateIncrementalInputsWithChangedFiles(
            @NonNull List<IncrementalTransformInput> consumedInputs,
            @NonNull List<IncrementalTransformInput> referencedInputs,
            @NonNull Map<File, Status> changedFilesMap,
            @NonNull Set<File> removedFiles) {

        // we're going to concat both list multiple times, and the Iterators API ultimately put
        // all the iterators to concat in a list. So let's reuse a list.
        List<Iterator<IncrementalTransformInput>> iterators = Lists.newArrayListWithCapacity(2);

        Splitter splitter = Splitter.on(File.separatorChar);

        final Sets.SetView<? super QualifiedContent.Scope> scopes =
                Sets.union(getTransform().getScopes(), getTransform().getReferencedScopes());
        final Set<QualifiedContent.ContentType> inputTypes = getTransform().getInputTypes();

        // start with the removed files as they carry the risk of removing incremental mode.
        // If we detect such a case, we stop immediately.
        for (File removedFile : removedFiles) {
            List<String> removedFileSegments =
                    Lists.newArrayList(splitter.split(removedFile.getAbsolutePath()));

            Iterator<IncrementalTransformInput> iterator =
                    getConcatIterator(consumedInputs, referencedInputs, iterators);

            boolean found = false;
            while (iterator.hasNext()) {
                IncrementalTransformInput next = iterator.next();
                if (next.checkRemovedJarFile(scopes, inputTypes, removedFile, removedFileSegments)
                        || next.checkRemovedFolderFile(
                                scopes, inputTypes, removedFile, removedFileSegments)) {
                    found = true;
                    break;
                }
            }

            if (!found && !isSecondaryFile(removedFile)) {
                // this deleted file breaks incremental because we cannot figure out where it's
                // coming from and what types/scopes is associated with it.
                return false;
            }
        }

        // now handle the added/changed files.

        for (Map.Entry<File, Status> entry : changedFilesMap.entrySet()) {
            File changedFile = entry.getKey();
            Status changedStatus = entry.getValue();

            // first go through the jars first as it's a faster check.
            Iterator<IncrementalTransformInput> iterator =
                    getConcatIterator(consumedInputs, referencedInputs, iterators);
            boolean found = false;
            while (iterator.hasNext()) {
                if (iterator.next().checkForJar(changedFile, changedStatus)) {
                    // we can skip to the next changed file.
                    found = true;
                    break;
                }
            }

            if (found) {
                continue;
            }

            // now go through the folders. First get a segment list for the path.
            iterator = getConcatIterator(consumedInputs, referencedInputs, iterators);
            List<String> changedSegments =
                    Lists.newArrayList(splitter.split(changedFile.getAbsolutePath()));

            while (iterator.hasNext()) {
                if (iterator.next().checkForFolder(changedFile, changedSegments, changedStatus)) {
                    // we can skip to the next changed file.
                    break;
                }
            }
        }

        return true;
    }

    @NonNull
    private static Iterator<IncrementalTransformInput> getConcatIterator(
            @NonNull List<IncrementalTransformInput> consumedInputs,
            @NonNull List<IncrementalTransformInput> referencedInputs,
            List<Iterator<IncrementalTransformInput>> iterators) {
        iterators.clear();
        iterators.add(consumedInputs.iterator());
        iterators.add(referencedInputs.iterator());
        return Iterators.concat(iterators.iterator());
    }

    @NonNull
    private static List<TransformInput> convertToImmutable(
            @NonNull List<IncrementalTransformInput> inputs) {
        return inputs.stream()
                .map(IncrementalTransformInput::asImmutable)
                .collect(Collectors.toList());
    }
}