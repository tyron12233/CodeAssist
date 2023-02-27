package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.DependencyScope;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ExportableOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.JdkOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.LibraryOrSdkOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.LibraryOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootModel;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleSourceOrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerationHandler;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumeratorSettings;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootsEnumerator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootModelProvider;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootPolicy;
import org.jetbrains.kotlin.com.intellij.openapi.roots.libraries.Library;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.util.NotNullFunction;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

abstract class OrderEnumeratorBase extends OrderEnumerator implements OrderEnumeratorSettings {
    private static final Logger LOG = Logger.getInstance(OrderEnumeratorBase.class);
    private boolean myProductionOnly;
    private boolean myCompileOnly;
    private boolean myRuntimeOnly;
    private boolean myWithoutJdk;
    private boolean myWithoutLibraries;
    boolean myWithoutDepModules;
    private boolean myWithoutModuleSourceEntries;
    boolean myRecursively;
    boolean myRecursivelyExportedOnly;
    private boolean myExportedOnly;
    private Condition<? super OrderEntry> myCondition;
    RootModelProvider myModulesProvider;
    private final OrderRootsCache myCache;

    OrderEnumeratorBase(@Nullable OrderRootsCache cache) {
        myCache = cache;
    }

    @NonNull
    static List<OrderEnumerationHandler> getCustomHandlers(@NonNull Module module) {
        List<OrderEnumerationHandler> customHandlers = null;
        for (OrderEnumerationHandler.Factory handlerFactory : OrderEnumerationHandler.EP_NAME.getExtensions()) {
            if (handlerFactory.isApplicable(module)) {
                if (customHandlers == null) {
                    customHandlers = new SmartList<>();
                }
                customHandlers.add(handlerFactory.createHandler(module));
            }
        }
        return customHandlers == null ? Collections.emptyList() : customHandlers;
    }

    @NonNull
    @Override
    public OrderEnumerator productionOnly() {
        myProductionOnly = true;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator compileOnly() {
        myCompileOnly = true;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator runtimeOnly() {
        myRuntimeOnly = true;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator withoutSdk() {
        myWithoutJdk = true;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator withoutLibraries() {
        myWithoutLibraries = true;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator withoutDepModules() {
        myWithoutDepModules = true;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator withoutModuleSourceEntries() {
        myWithoutModuleSourceEntries = true;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator recursively() {
        myRecursively = true;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator exportedOnly() {
        if (myRecursively) {
            myRecursivelyExportedOnly = true;
        }
        else {
            myExportedOnly = true;
        }
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator satisfying(@NonNull Condition<? super OrderEntry> condition) {
        myCondition = condition;
        return this;
    }

    @NonNull
    @Override
    public OrderEnumerator using(@NonNull RootModelProvider provider) {
        myModulesProvider = provider;
        return this;
    }

    @NonNull
    @Override
    public OrderRootsEnumerator classes() {
        return new OrderRootsEnumeratorImpl(this, OrderRootType.CLASSES);
    }

    @NonNull
    @Override
    public OrderRootsEnumerator sources() {
        return new OrderRootsEnumeratorImpl(this, OrderRootType.SOURCES);
    }

    @NonNull
    @Override
    public OrderRootsEnumerator roots(@NonNull OrderRootType rootType) {
        return new OrderRootsEnumeratorImpl(this, rootType);
    }

    @NonNull
    @Override
    public OrderRootsEnumerator roots(@NonNull NotNullFunction<? super OrderEntry, ? extends OrderRootType> rootTypeProvider) {
        return new OrderRootsEnumeratorImpl(this, rootTypeProvider);
    }

    ModuleRootModel getRootModel(@NonNull Module module) {
        if (myModulesProvider != null) {
            return myModulesProvider.getRootModel(module);
        }
        return ModuleRootManager.getInstance(module);
    }

    @NonNull
    OrderRootsCache getCache() {
        LOG.assertTrue(myCache != null, "Caching is not supported for ModifiableRootModel");
        LOG.assertTrue(myCondition == null, "Caching not supported for OrderEnumerator with 'satisfying(Condition)' option");
        LOG.assertTrue(myModulesProvider == null, "Caching not supported for OrderEnumerator with 'using(ModulesProvider)' option");
        return myCache;
    }

    public int getFlags() {
        int flags = 0;
        if (myProductionOnly) flags |= 1;
        flags <<= 1;
        if (myCompileOnly) flags |= 1;
        flags <<= 1;
        if (myRuntimeOnly) flags |= 1;
        flags <<= 1;
        if (myWithoutJdk) flags |= 1;
        flags <<= 1;
        if (myWithoutLibraries) flags |= 1;
        flags <<= 1;
        if (myWithoutDepModules) flags |= 1;
        flags <<= 1;
        if (myWithoutModuleSourceEntries) flags |= 1;
        flags <<= 1;
        if (myRecursively) flags |= 1;
        flags <<= 1;
        if (myRecursivelyExportedOnly) flags |= 1;
        flags <<= 1;
        if (myExportedOnly) flags |= 1;
        return flags;
    }

    @Override
    public boolean shouldRecurse(@NonNull ModuleOrderEntry entry, @NonNull List<? extends OrderEnumerationHandler> handlers) {
        ProcessEntryAction action = shouldAddOrRecurse(entry, true, handlers);
        return action.type == ProcessEntryActionType.RECURSE;
    }

    // Should process, should recurse, or not process at all.
    protected enum ProcessEntryActionType {
        SKIP,
        RECURSE,
        PROCESS
    }

    protected static final class ProcessEntryAction {
        @NonNull
        public ProcessEntryActionType type;
        @Nullable Module recurseOnModule;

        private ProcessEntryAction(@NonNull ProcessEntryActionType type) {
            this.type = type;
        }

        public static final ProcessEntryAction SKIP = new ProcessEntryAction(ProcessEntryActionType.SKIP);

        @NonNull
        static ProcessEntryAction RECURSE(@NonNull Module module) {
            ProcessEntryAction result = new ProcessEntryAction(ProcessEntryActionType.RECURSE);
            result.recurseOnModule = module;
            return result;
        }

        public static final ProcessEntryAction PROCESS = new ProcessEntryAction(ProcessEntryActionType.PROCESS);
    }

    @NonNull
    private ProcessEntryAction shouldAddOrRecurse(@NonNull OrderEntry entry, boolean firstLevel, @NonNull List<? extends OrderEnumerationHandler> customHandlers) {
        if (myCondition != null && !myCondition.value(entry)) return ProcessEntryAction.SKIP;

        if (entry instanceof JdkOrderEntry && (myWithoutJdk || !firstLevel)) {
            return ProcessEntryAction.SKIP;
        }
        if (myWithoutLibraries && entry instanceof LibraryOrderEntry) return ProcessEntryAction.SKIP;
        if (myWithoutDepModules) {
            if (!myRecursively && entry instanceof ModuleOrderEntry) return ProcessEntryAction.SKIP;
            if (entry instanceof ModuleSourceOrderEntry && !isRootModuleModel(((ModuleSourceOrderEntry)entry).getRootModel())) {
                return ProcessEntryAction.SKIP;
            }
        }
        if (myWithoutModuleSourceEntries && entry instanceof ModuleSourceOrderEntry) return ProcessEntryAction.SKIP;

        OrderEnumerationHandler.AddDependencyType shouldAdd = OrderEnumerationHandler.AddDependencyType.DEFAULT;
        for (OrderEnumerationHandler handler : customHandlers) {
            shouldAdd = handler.shouldAddDependency(entry, this);
            if (shouldAdd != OrderEnumerationHandler.AddDependencyType.DEFAULT) break;
        }
        if (shouldAdd == OrderEnumerationHandler.AddDependencyType.DO_NOT_ADD) {
            return ProcessEntryAction.SKIP;
        }

        boolean exported = !(entry instanceof JdkOrderEntry);
        if (entry instanceof ExportableOrderEntry) {
            ExportableOrderEntry exportableEntry = (ExportableOrderEntry) entry;
            if (shouldAdd == OrderEnumerationHandler.AddDependencyType.DEFAULT) {
                final DependencyScope scope = exportableEntry.getScope();
                boolean forTestCompile = scope.isForTestCompile() ||
                                         scope == DependencyScope.RUNTIME && shouldAddRuntimeDependenciesToTestCompilationClasspath(customHandlers);
                if (myCompileOnly && !scope.isForProductionCompile() && !forTestCompile) return ProcessEntryAction.SKIP;
                if (myRuntimeOnly && !scope.isForProductionRuntime() && !scope.isForTestRuntime()) return ProcessEntryAction.SKIP;
                if (myProductionOnly) {
                    if (!scope.isForProductionCompile() && !scope.isForProductionRuntime() ||
                        myCompileOnly && !scope.isForProductionCompile() ||
                        myRuntimeOnly && !scope.isForProductionRuntime()) {
                        return ProcessEntryAction.SKIP;
                    }
                }
            }
            exported = exportableEntry.isExported();
        }
        if (!exported) {
            if (myExportedOnly) {
                return ProcessEntryAction.SKIP;
            }
            if (myRecursivelyExportedOnly && !firstLevel) {
                return ProcessEntryAction.SKIP;
            }
        }
        if (myRecursively && entry instanceof ModuleOrderEntry) {
            ModuleOrderEntry moduleOrderEntry = (ModuleOrderEntry) entry;
            final Module depModule = moduleOrderEntry.getModule();
            if (depModule != null && shouldProcessRecursively(customHandlers)) {
                return ProcessEntryAction.RECURSE(depModule);
            }
        }
        if (myWithoutDepModules && entry instanceof ModuleOrderEntry) return ProcessEntryAction.SKIP;
        return ProcessEntryAction.PROCESS;
    }

    protected void processEntries(@NonNull ModuleRootModel rootModel,
                                  @Nullable Set<? super Module> processed,
                                  boolean firstLevel,
                                  @NonNull List<? extends OrderEnumerationHandler> customHandlers,
                                  @NonNull PairProcessor<? super OrderEntry, ? super List<? extends OrderEnumerationHandler>> processor) {
        ProgressManager.checkCanceled();
        if (processed != null && !processed.add(rootModel.getModule())) return;

        for (OrderEntry entry : rootModel.getOrderEntries()) {
            ProcessEntryAction action = shouldAddOrRecurse(entry, firstLevel, customHandlers);

            if (action.type == ProcessEntryActionType.SKIP) {
                continue;
            }
            if (action.type == ProcessEntryActionType.RECURSE) {
                processEntries(getRootModel(action.recurseOnModule), processed, false, customHandlers, processor);
                continue;
            }
            assert action.type == ProcessEntryActionType.PROCESS;
            if (!processor.process(entry, customHandlers)) {
                return;
            }
        }
    }

    private static boolean shouldAddRuntimeDependenciesToTestCompilationClasspath(@NonNull List<? extends OrderEnumerationHandler> customHandlers) {
        for (OrderEnumerationHandler handler : customHandlers) {
            if (handler.shouldAddRuntimeDependenciesToTestCompilationClasspath()) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldProcessRecursively(@NonNull List<? extends OrderEnumerationHandler> customHandlers) {
        for (OrderEnumerationHandler handler : customHandlers) {
            if (!handler.shouldProcessDependenciesRecursively()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void forEach(@NonNull final Processor<? super OrderEntry> processor) {
        forEach((entry, __) -> processor.process(entry));
    }

    protected abstract void forEach(@NonNull PairProcessor<? super OrderEntry, ? super List<? extends OrderEnumerationHandler>> processor);

    @Override
    public void forEachLibrary(@NonNull final Processor<? super Library> processor) {
        forEach((entry, __) -> {
            if (entry instanceof LibraryOrderEntry) {
                final Library library = ((LibraryOrderEntry)entry).getLibrary();
                if (library != null) {
                    return processor.process(library);
                }
            }
            return true;
        });
    }

    @Override
    public void forEachModule(@NonNull final Processor<? super Module> processor) {
        forEach((orderEntry, customHandlers) -> {
            if (myRecursively && orderEntry instanceof ModuleSourceOrderEntry) {
                final Module module = ((ModuleSourceOrderEntry)orderEntry).getRootModel().getModule();
                return processor.process(module);
            }
            if (orderEntry instanceof ModuleOrderEntry && (!myRecursively || !shouldProcessRecursively(customHandlers))) {
                final Module module = ((ModuleOrderEntry)orderEntry).getModule();
                if (module != null) {
                    return processor.process(module);
                }
            }
            return true;
        });
    }

    @Override
    public <R> R process(@NonNull final RootPolicy<R> policy, final R initialValue) {
        final OrderEntryProcessor<R> processor = new OrderEntryProcessor<>(policy, initialValue);
        forEach(processor);
        return processor.myValue;
    }

    static boolean shouldIncludeTestsFromDependentModulesToTestClasspath(@NonNull List<? extends OrderEnumerationHandler> customHandlers) {
        for (OrderEnumerationHandler handler : customHandlers) {
            if (!handler.shouldIncludeTestsFromDependentModulesToTestClasspath()) {
                return false;
            }
        }
        return true;
    }

    static boolean addCustomRootsForLibraryOrSdk(@NonNull LibraryOrSdkOrderEntry forOrderEntry,
                                                 @NonNull OrderRootType type,
                                                 @NonNull Collection<? super VirtualFile> result,
                                                 @NonNull List<? extends OrderEnumerationHandler> customHandlers) {
        for (OrderEnumerationHandler handler : customHandlers) {
            final List<String> urls = new ArrayList<>();
            final boolean added =
                    handler.addCustomRootsForLibraryOrSdk(forOrderEntry, type, urls);
            for (String url : urls) {
                ContainerUtil.addIfNotNull(result, VirtualFileManager.getInstance().findFileByUrl(url));
            }
            if (added) {
                return true;
            }
        }
        return false;
    }

    static boolean addCustomRootUrlsForLibraryOrSdk(@NonNull LibraryOrSdkOrderEntry forOrderEntry,
                                                    @NonNull OrderRootType type,
                                                    @NonNull Collection<? super String> result,
                                                    @NonNull List<? extends OrderEnumerationHandler> customHandlers) {
        for (OrderEnumerationHandler handler : customHandlers) {
            final List<String> urls = new ArrayList<>();
            final boolean added =
                    handler.addCustomRootsForLibraryOrSdk(forOrderEntry, type, urls);
            result.addAll(urls);
            if (added) {
                return true;
            }
        }
        return false;
    }

    static boolean addCustomRootsForModule(@NonNull OrderRootType type,
                                           @NonNull ModuleRootModel rootModel,
                                           @NonNull Collection<? super VirtualFile> result,
                                           boolean includeProduction,
                                           boolean includeTests,
                                           @NonNull List<? extends OrderEnumerationHandler> customHandlers) {
        for (OrderEnumerationHandler handler : customHandlers) {
            final List<String> urls = new ArrayList<>();
            final boolean added = handler.addCustomModuleRoots(type, rootModel, urls, includeProduction, includeTests);
            for (String url : urls) {
                ContainerUtil.addIfNotNull(result, VirtualFileManager.getInstance().findFileByUrl(url));
            }

            if (added) return true;
        }
        return false;
    }

    static void addCustomRootsUrlsForModule(@NonNull OrderRootType type,
                                            @NonNull ModuleRootModel rootModel,
                                            @NonNull Collection<String> result,
                                            boolean includeProduction,
                                            boolean includeTests,
                                            @NonNull List<? extends OrderEnumerationHandler> customHandlers) {
        for (OrderEnumerationHandler handler : customHandlers) {
            handler.addCustomModuleRoots(type, rootModel, result, includeProduction, includeTests);
        }
    }

    @Override
    public boolean isRuntimeOnly() {
        return myRuntimeOnly;
    }

    @Override
    public boolean isCompileOnly() {
        return myCompileOnly;
    }

    @Override
    public boolean isProductionOnly() {
        return myProductionOnly;
    }

    public boolean isRootModuleModel(@NonNull ModuleRootModel rootModel) {
        return false;
    }

    /**
     * Runs processor on each module that this enumerator was created on.
     *
     * @param processor processor
     */
    public abstract void processRootModules(@NonNull Processor<? super Module> processor);

    private static final class OrderEntryProcessor<R> implements PairProcessor<OrderEntry, List<? extends OrderEnumerationHandler>> {
        private R myValue;
        private final RootPolicy<R> myPolicy;

        private OrderEntryProcessor(@NonNull RootPolicy<R> policy, R initialValue) {
            myPolicy = policy;
            myValue = initialValue;
        }

        @Override
        public boolean process(OrderEntry orderEntry, List<? extends OrderEnumerationHandler> __) {
            myValue = orderEntry.accept(myPolicy, myValue);
            return true;
        }
    }
}