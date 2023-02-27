//package org.jetbrains.kotlin.com.intellij.util.indexing;
//
//import org.jetbrains.annotations.ApiStatus;
//import org.jetbrains.annotations.NonNls;
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import org.jetbrains.annotations.VisibleForTesting;
//import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
//import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
//import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
//import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.ControlFlowException;
//import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
//import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
//import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
//import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
//import org.jetbrains.kotlin.com.intellij.openapi.progress.impl.ProgressSuspender;
//import org.jetbrains.kotlin.com.intellij.openapi.project.MergeableQueueTask;
//import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
//import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
//import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.FilePropertyPusher;
//import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater;
//import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
//import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
//import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
//import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
//import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
//import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
//import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
//import org.jetbrains.kotlin.com.intellij.util.Function;
//import org.jetbrains.kotlin.com.intellij.util.SmartList;
//import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
//import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
//import org.jetbrains.kotlin.com.intellij.util.indexing.DependenciesIndexedStatusService.StatusMark;
//import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFileScanner;
//import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesDeduplicateFilter;
//import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesIterator;
//import org.jetbrains.kotlin.com.intellij.util.indexing.roots.kind.IndexableSetOrigin;
//import org.jetbrains.kotlin.com.intellij.util.indexing.roots.kind.ModuleRootOrigin;
//import org.jetbrains.kotlin.com.intellij.util.indexing.roots.kind.SdkOrigin;
//
//import java.util.*;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//
//@ApiStatus.Internal
//public class UnindexedFilesScanner implements MergeableQueueTask<UnindexedFilesScanner> {
//
//    @VisibleForTesting
//    public static final Key<Boolean> INDEX_PROJECT_WITH_MANY_UPDATERS_TEST_KEY =
//            new Key<>("INDEX_PROJECT_WITH_MANY_UPDATERS_TEST_KEY");
//
//    private static final Logger LOG = Logger.getInstance(UnindexedFilesScanner.class);
//            // only for test debugging purpose
//
//    public enum TestMode {
//        PUSHING,
//        PUSHING_AND_SCANNING
//    }
//
//    @SuppressWarnings("StaticNonFinalField")
//    @VisibleForTesting
//    public static volatile TestMode ourTestMode;
//
//    private static final @NotNull Key<Boolean> CONTENT_SCANNED = Key.create("CONTENT_SCANNED");
//    private static final @NotNull Key<Boolean> INDEX_UPDATE_IN_PROGRESS =
//            Key.create("INDEX_UPDATE_IN_PROGRESS");
//    private final CoreFileBasedIndex myIndex = (CoreFileBasedIndex) FileBasedIndex.getInstance();
//    protected final Project myProject;
//    private final boolean myStartSuspended;
//    private final boolean myOnProjectOpen;
//    private final @NotNull @NonNls String myIndexingReason;
//    private final @NotNull ScanningType myScanningType;
//    private final PushedFilePropertiesUpdater myPusher;
//    private final @Nullable StatusMark myProvidedStatusMark;
//    private final @Nullable List<IndexableFilesIterator> myPredefinedIndexableFilesIterators;
//
//    public UnindexedFilesScanner(@NotNull Project project,
//                                 boolean startSuspended,
//                                 boolean onProjectOpen,
//                                 @Nullable List<IndexableFilesIterator> predefinedIndexableFilesIterators,
//                                 @Nullable StatusMark mark,
//                                 @Nullable @NonNls String indexingReason,
//                                 @NotNull ScanningType scanningType) {
//        myProject = project;
//        myStartSuspended = startSuspended;
//        myOnProjectOpen = onProjectOpen;
//        myIndexingReason = (indexingReason != null) ? indexingReason : "<unknown>";
//        myScanningType = scanningType;
//        myPusher = PushedFilePropertiesUpdater.getInstance(myProject);
//        myProvidedStatusMark = predefinedIndexableFilesIterators == null ? null : mark;
//        myPredefinedIndexableFilesIterators = predefinedIndexableFilesIterators;
//        LOG.assertTrue(myPredefinedIndexableFilesIterators == null ||
//                       !myPredefinedIndexableFilesIterators.isEmpty());
//
//        if (isFullIndexUpdate()) {
//            myProject.putUserData(CONTENT_SCANNED, null);
//        }
//    }
//
//    protected boolean isFullIndexUpdate() {
//        return myPredefinedIndexableFilesIterators == null;
//    }
//
//    @Override
//    public void dispose() {
//
//    }
//
//    @Override
//    public @Nullable UnindexedFilesScanner tryMergeWith(@NotNull UnindexedFilesScanner oldTask) {
//        if (oldTask.getClass() != getClass()) {
//            return null;
//        }
//        if (!myProject.equals(oldTask.myProject)) {
//            return null;
//        }
//        String reason;
//        if (oldTask.isFullIndexUpdate()) {
//            reason = oldTask.myIndexingReason;
//        } else if (isFullIndexUpdate()) {
//            reason = myIndexingReason;
//        } else {
//            reason = "Merged " +
//                     StringUtil.trimStart(myIndexingReason, "Merged ") +
//                     " with " +
//                     StringUtil.trimStart(oldTask.myIndexingReason, "Merged ");
//        }
//        LOG.debug("Merged " + this + " task");
//        return new UnindexedFilesScanner(myProject,
//                myStartSuspended,
//                false,
//                mergeIterators(myPredefinedIndexableFilesIterators,
//                        oldTask.myPredefinedIndexableFilesIterators),
//                StatusMark.mergeStatus(myProvidedStatusMark, oldTask.myProvidedStatusMark),
//                reason,
//                ScanningType.Companion.merge(oldTask.myScanningType, oldTask.myScanningType));
//    }
//
//    private static @Nullable List<IndexableFilesIterator> mergeIterators(@Nullable List<?
//            extends IndexableFilesIterator> iterators,
//                                                                         @Nullable List<?
//                                                                                 extends IndexableFilesIterator> otherIterators) {
//        if (iterators == null || otherIterators == null) {
//            return null;
//        }
//        Map<IndexableSetOrigin, IndexableFilesIterator> uniqueIterators = new LinkedHashMap<>();
//        for (IndexableFilesIterator iterator : iterators) {
//            uniqueIterators.putIfAbsent(iterator.getOrigin(), iterator);
//        }
//        for (IndexableFilesIterator iterator : otherIterators) {
//            uniqueIterators.putIfAbsent(iterator.getOrigin(), iterator);
//        }
//        return new ArrayList<>(uniqueIterators.values());
//    }
//
//    public UnindexedFilesScanner(@NotNull Project project,
//                                 @Nullable @NonNls String indexingReason) {
//        this(project, false, false, null, null, indexingReason, ScanningType.FULL);
//    }
//
//
//    static boolean shouldScanInSmartMode() {
//        return !DumbServiceImpl.isSynchronousTaskExecution() &&
//               Registry.is("scanning.in.smart.mode", true);
//    }
//
//    private static @NotNull String getLogScanningCompletedStageMessage() {
////        List<JsonScanningStatistics> statistics = projectIndexingHistory.getScanningStatistics();
////        int numberOfScannedFiles = statistics.stream().mapToInt
////        (JsonScanningStatistics::getNumberOfScannedFiles).sum();
////        int numberOfFilesForIndexing = statistics.stream().mapToInt
////        (JsonScanningStatistics::getNumberOfFilesForIndexing).sum();
//        return "Scanning completed";
////               projectIndexingHistory.getProject().getName() +
////               ". Number of scanned files: " +
////               numberOfScannedFiles +
////               "; " +
////               "Number of files for indexing: " +
////               numberOfFilesForIndexing;
//    }
//
//    public static boolean isIndexUpdateInProgress(@NotNull Project project) {
//        return project.getUserData(INDEX_UPDATE_IN_PROGRESS) == Boolean.TRUE;
//    }
//
//    public static boolean isProjectContentFullyScanned(@NotNull Project project) {
//        return Boolean.TRUE.equals(project.getUserData(CONTENT_SCANNED));
//    }
//
//    @NotNull
//    private static Pair<@NotNull List<IndexableFilesIterator>, @Nullable StatusMark> collectProviders(
//            @NotNull Project project,
//            CoreFileBasedIndex index) {
////        boolean cache = DependenciesIndexedStatusService.shouldBeUsed();
//        List<IndexableFilesIterator> originalOrderedProviders;
//        StatusMark mark = null;
////        if (cache) {
////            DependenciesIndexedStatusService.getInstance(project).startCollectingStatus();
////        }
//        try {
//            originalOrderedProviders = index.getIndexableFilesProviders(project);
//        } finally {
////            if (cache) {
////                mark = DependenciesIndexedStatusService.getInstance(project)
////                .finishCollectingStatus();
////            }
//        }
//
//        List<IndexableFilesIterator> orderedProviders = new ArrayList<>();
//        originalOrderedProviders.stream()
//                .filter(p -> !(p.getOrigin() instanceof SdkOrigin))
//                .collect(Collectors.toCollection(() -> orderedProviders));
//
//        originalOrderedProviders.stream()
//                .filter(p -> p.getOrigin() instanceof SdkOrigin)
//                .collect(Collectors.toCollection(() -> orderedProviders));
//
//        return new Pair<>(orderedProviders, mark);
//    }
//
//    protected @Nullable Function<IndexedFile, Boolean> getForceReindexingTrigger() {
//        return null;
//    }
//
//    @Override
//    public void perform(@NotNull ProgressIndicator indicator) {
//        LOG.assertTrue(myProject.getUserData(INDEX_UPDATE_IN_PROGRESS) != Boolean.TRUE,
//                "Scanning is already in progress");
//        myProject.putUserData(INDEX_UPDATE_IN_PROGRESS, true);
//        try {
//            performScanningAndIndexing(indicator);
//        } finally {
//            myProject.putUserData(INDEX_UPDATE_IN_PROGRESS, false);
//        }
//    }
//
//    private void collectIndexableFilesConcurrently(
//            @NotNull Project project,
//            @NotNull ProgressIndicator indicator,
//            @NotNull List<? extends IndexableFilesIterator> providers
//    ) {
//        if (providers.isEmpty()) {
//            return;
//        }
//
//        List<IndexableFileScanner.ScanSession> sessions =
//                ContainerUtil.map(IndexableFileScanner.EP_NAME.getExtensionList(), scanner -> scanner.startSession(project));
//        UnindexedFilesFinder unindexedFileFinder = new UnindexedFilesFinder(project, myIndex, getForceReindexingTrigger());
//
//        IndexableFilesDeduplicateFilter indexableFilesDeduplicateFilter = IndexableFilesDeduplicateFilter.create();
//
//        indicator.setText("Scanning files to index");
//        indicator.setIndeterminate(false);
//        indicator.setFraction(0);
//
//        // Workaround for concurrent modification of the [projectIndexingHistory].
//        // PushedFilePropertiesUpdaterImpl.invokeConcurrentlyIfPossible may finish earlier than all its spawned tasks have completed.
//        // And some scanning statistics may be tried to be added to the [projectIndexingHistory],
//        // leading to ConcurrentModificationException in the statistics' processor.
//        Ref<Boolean> allTasksFinished = Ref.create(false);
//        List<Runnable> tasks = ContainerUtil.map(providers, provider -> {
//            IndexableSetOrigin origin = provider.getOrigin();
//            List<IndexableFileScanner.@NotNull IndexableFileVisitor> fileScannerVisitors =
//                    ContainerUtil.mapNotNull(sessions, s -> s.createVisitor(origin));
//
//            IndexableFilesDeduplicateFilter thisProviderDeduplicateFilter =
//                    IndexableFilesDeduplicateFilter.createDelegatingTo(indexableFilesDeduplicateFilter);
//
//            ProgressManager.checkCanceled(); // give a chance to suspend indexing
//
//            return () -> {
//                try (PerProviderSink perProviderSink = project.getService(PerProjectIndexingQueue.class).getSink(provider)) {
//                    CollectingIterator collectingIterator = new CollectingIterator(project, subTaskIndicator, provider, fileScannerVisitors,
//                            unindexedFileFinder, scanningStatistics, perProviderSink);
//                    provider.iterateFiles(project, collectingIterator, thisProviderDeduplicateFilter);
//                    perProviderSink.commit();
//                } catch (ProcessCanceledException pce) {
//                    throw pce;
//                }
//                catch (Exception e) {
//                    // CollectingIterator should skip failing files by itself. But if provider.iterateFiles cannot iterate files and throws exception,
//                    // we want to ignore whole origin and let other origins to complete normally.
//                    LOG.error("Error while scanning files of " + provider.getDebugName() + "\n" +
//                              "To reindex files under this origin IDEA has to be restarted", e);
//                }
//            };
//        });
//        LOG.info("Scanning of " + myProject.getName() + " uses " + UnindexedFilesUpdater.getNumberOfScanningThreads() + " scanning threads");
//        tasks.forEach(Runnable::run);
//    }
//
//    private void scan(
////            @NotNull PerformanceWatcher.Snapshot snapshot,
////                      @NotNull ProjectIndexingHistoryImpl projectIndexingHistory,
//                      @NotNull ProgressIndicator indicator,
//                      @NotNull Ref<? super StatusMark> markRef) {
//        if (isFullIndexUpdate()) {
//            myIndex.clearIndicesIfNecessary();
//        }
//
//        List<IndexableFilesIterator> orderedProviders;
//        if (myPredefinedIndexableFilesIterators == null) {
//            Pair<@NotNull List<IndexableFilesIterator>, @NotNull StatusMark> pair = collectProviders(myProject, myIndex);
//            orderedProviders = pair.getFirst();
//            markRef.set(pair.getSecond());
//        }
//        else {
//            orderedProviders = myPredefinedIndexableFilesIterators;
//        }
//
//        collectIndexableFilesConcurrently(myProject, indicator, orderedProviders);
//        if (isFullIndexUpdate()) {
//            myProject.putUserData(CONTENT_SCANNED, true);
//        }
//    }
//
//    private void scanAndUpdateUnindexedFiles(
////            @NotNull ProjectIndexingHistoryImpl projectIndexingHistory,
//            @NotNull ProgressIndicator indicator, @NotNull Ref<? super StatusMark> markRef) {
//        if (!IndexInfrastructure.hasIndices()) {
//            return;
//        }
//        LOG.info("Started scanning for indexing of " + myProject.getName() + ". Reason: " + myIndexingReason);
//
////        ProgressSuspender suspender = ProgressSuspender.getSuspender(indicator);
////        if (suspender != null) {
////            ApplicationManager.getApplication().getMessageBus().connect(this)
////                    .subscribe(ProgressSuspender.TOPIC, projectIndexingHistory.getSuspendListener(suspender));
////        }
//
//        indicator.setIndeterminate(true);
//        indicator.setText("Scanning for files to index");
//
//        Disposable scanningLifetime = Disposer.newDisposable();
//        try {
////            if (!shouldScanInSmartMode()) {
////                DumbModeProgressTitle.getInstance(myProject)
////                        .attachProgressTitleText(IndexingBundle.message("progress.indexing.scanning.title"), scanningLifetime);
////            }
//            scan(indicator, markRef);
//        }
//        finally {
//            Disposer.dispose(scanningLifetime);
//        }
//
//        boolean skipInitialRefresh = skipInitialRefresh();
//        boolean isUnitTestMode = ApplicationManager.getApplication().isUnitTestMode();
//        if (myOnProjectOpen && !isUnitTestMode && !skipInitialRefresh) {
//            // the full VFS refresh makes sense only after it's loaded, i.e. after scanning files to index is finished
//            scheduleInitialVfsRefresh();
//        }
//
//        if (shouldScanInSmartMode()) {
//            // Switch to dumb mode and index
//            myProject.getService(PerProjectIndexingQueue.class).flushNow(myIndexingReason);
//        }
//        else {
//            // Already in dumb mode. Just invoke indexer
//            myProject.getService(PerProjectIndexingQueue.class).flushNowSync(projectIndexingHistory, indicator);
//        }
//    }
//
//    protected void performScanningAndIndexing(@NotNull ProgressIndicator indicator) {
//        myIndex.loadIndexes();
//        myIndex.filesUpdateStarted(myProject, isFullIndexUpdate());
//
//        Ref<StatusMark> markRef = new Ref<>();
//        try {
//            scanAndUpdateUnindexedFiles(indicator, markRef);
//        } catch (Throwable e) {
////            projectIndexingHistory.setWasInterrupted(true);
//            if (e instanceof ControlFlowException) {
//                LOG.info("Cancelled indexing of " + myProject.getName());
//            }
//            throw e;
//        } finally {
//            myIndex.filesUpdateFinished(myProject);
////            projectIndexingHistory.finishTotalUpdatingTime();
////            if (DependenciesIndexedStatusService.shouldBeUsed() && IndexInfrastructure
////            .hasIndices()) {
////                DependenciesIndexedStatusService.getInstance(myProject)
////                        .indexingFinished(!projectIndexingHistory.getTimes().getWasInterrupted
////                        (), markRef.get());
////            }
////            IndexDiagnosticDumper.getInstance().onIndexingFinished(projectIndexingHistory);
//        }
//    }
//
//    @Override
//    public String toString() {
//        String partialInfo = myPredefinedIndexableFilesIterators != null ? (", " +
//                                                                            myPredefinedIndexableFilesIterators.size() +
//                                                                            " iterators") : "";
//        return "UnindexedFilesScanner[" + myProject.getName() + partialInfo + "]";
//    }
//
//    private static boolean skipInitialRefresh() {
//        return SystemProperties.getBooleanProperty("ij.indexes.skip.initial.refresh", false);
//    }
//
//    void queue(@NotNull Project project) {
//        project.getService(UnindexedFilesScannerExecutor.class).submitTask(this);
//    }
//
//    @Nullable List<IndexableFilesIterator> getPredefinedIndexableFilesIterators() {
//        return myPredefinedIndexableFilesIterators;
//    }
//
//    private static final class CollectingIterator implements ContentIterator {
//        private final Project project;
//        private final PerProviderSink perProviderSink;
//        private final SubTaskProgressIndicator subTaskIndicator;
//        private final List<IndexableFileScanner.@NotNull IndexableFileVisitor> fileScannerVisitors;
//        private final List<FilePropertyPusher<?>> pushers;
//        private final List<FilePropertyPusherEx<?>> pusherExs;
//        private final Object[] moduleValues;
//        private final UnindexedFilesFinder unindexedFileFinder;
//        private final ScanningStatistics scanningStatistics;
//        private final PushedFilePropertiesUpdater pushedFilePropertiesUpdater;
//
//        CollectingIterator(Project project,
//                           SubTaskProgressIndicator subTaskIndicator,
//                           IndexableFilesIterator provider,
//                           List<IndexableFileScanner.@NotNull IndexableFileVisitor> fileScannerVisitors,
//                           UnindexedFilesFinder unindexedFileFinder,
//                           ScanningStatistics scanningStatistics,
//                           PerProviderSink perProviderSink) {
//            this.project = project;
//            this.subTaskIndicator = subTaskIndicator;
//            this.fileScannerVisitors = fileScannerVisitors;
//            this.unindexedFileFinder = unindexedFileFinder;
//            this.scanningStatistics = scanningStatistics;
//
//            pushedFilePropertiesUpdater = PushedFilePropertiesUpdater.getInstance(project);
//
//            // We always need to properly dispose perProviderSink. Make this fact explicit to clients by requiring clients to provide an instance
//            this.perProviderSink = perProviderSink;
//
//            IndexableSetOrigin origin = provider.getOrigin();
//            if (origin instanceof ModuleRootOrigin && !((ModuleRootOrigin) origin).getModule().isDisposed()) {
//                pushers = FilePropertyPusher.EP_NAME.getExtensionList();
//                pusherExs = null;
//                moduleValues = ReadAction.compute(() -> getModuleImmediateValues(pushers,
//                        ((ModuleRootOrigin) origin).getModule()));
//            } else {
//                pushers = null;
//                List<FilePropertyPusherEx<?>> extendedPushers = new SmartList<>();
//                for (FilePropertyPusher<?> pusher : FilePropertyPusher.EP_NAME.getExtensionList()) {
//                    if (pusher instanceof FilePropertyPusherEx &&
//                        ((FilePropertyPusherEx<?>) pusher).acceptsOrigin(project, origin)) {
//                        extendedPushers.add((FilePropertyPusherEx<?>) pusher);
//                    }
//                }
//                if (extendedPushers.isEmpty()) {
//                    pusherExs = null;
//                    moduleValues = null;
//                } else {
//                    pusherExs = extendedPushers;
//                    moduleValues =
//                            ReadAction.compute(() -> getImmediateValuesEx(extendedPushers, origin));
//                }
//            }
//        }
//
//        @Override
//        public boolean processFile(@NotNull VirtualFile fileOrDir) {
//            ProgressManager.checkCanceled(); // give a chance to suspend indexing
//            if (subTaskIndicator.isCanceled()) {
//                return false;
//            }
//
//            try {
//                processFileRethrowExceptions(fileOrDir);
//            }
//            catch (ProcessCanceledException pce) {
//                throw pce;
//            }
//            catch (Exception e) {
//                LOG.error("Error while scanning " + fileOrDir.getPresentableUrl() + "\n" +
//                          "To reindex this file IDEA has to be restarted", e);
//            }
//            return true;
//        }
//
//        private void processFileRethrowExceptions(@NotNull VirtualFile fileOrDir) {
//            long scanningStart = System.nanoTime();
//            PushedFilePropertiesUpdaterImpl.applyScannersToFile(fileOrDir, fileScannerVisitors);
//            if (pushers != null && pushedFilePropertiesUpdater instanceof PushedFilePropertiesUpdaterImpl) {
//                ((PushedFilePropertiesUpdaterImpl)pushedFilePropertiesUpdater).applyPushersToFile(fileOrDir, pushers, moduleValues);
//            }
//            else if (pusherExs != null && pushedFilePropertiesUpdater instanceof PushedFilePropertiesUpdaterImpl) {
//                ((PushedFilePropertiesUpdaterImpl)pushedFilePropertiesUpdater).applyPushersToFile(fileOrDir, pusherExs, moduleValues);
//            }
//
//            UnindexedFileStatus status;
//            long statusTime = System.nanoTime();
//            try {
//                status = ourTestMode == TestMode.PUSHING ? null : unindexedFileFinder.getFileStatus(fileOrDir);
//            }
//            finally {
//                statusTime = System.nanoTime() - statusTime;
//            }
//            if (status != null) {
//                if (status.getShouldIndex() && ourTestMode == null) {
//                    perProviderSink.addFile(fileOrDir);
//                }
//                scanningStatistics.addStatus(fileOrDir, status, statusTime, project);
//            }
//            scanningStatistics.addScanningTime(System.nanoTime() - scanningStart);
//        }
//    }
//
//}
//}
//
