package com.tyron.completion.xml.v2.project;

import static com.android.ide.common.util.PathStringUtil.toPathString;

import androidx.annotation.GuardedBy;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.Locale;
import com.android.projectmodel.RecursiveResourceFolder;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.tyron.builder.model.CodeAssistAndroidLibrary;
import com.tyron.builder.model.CodeAssistLibrary;
import com.tyron.builder.project.ExternalAndroidLibrary;
import com.tyron.builder.project.ExternalLibraryImpl;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.impl.AndroidModuleImpl;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.xml.v2.aar.AarResourceRepository;
import com.tyron.completion.xml.v2.model.Namespacing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

public class ResourceRepositoryManager {

    private static final Key<ResourceRepositoryManager> KEY = Key.create(ResourceRepositoryManager.class.getName());

    private static final Object APP_RESOURCES_LOCK = new Object();
    private static final Object PROJECT_RESOURCES_LOCK = new Object();
    private static final Object MODULE_RESOURCES_LOCK = new Object();
    private static final Object TEST_RESOURCES_LOCK = new Object();

    @NotNull private final AndroidModule myFacet;
    @NotNull private final Namespacing myNamespacing;

    /**
     * If the module is namespaced, this is the shared {@link ResourceNamespace} instance corresponding to the package name from the manifest.
     */
    @Nullable private ResourceNamespace mySharedNamespaceInstance;
    @Nullable private ResourceNamespace mySharedTestNamespaceInstance;

    @GuardedBy("APP_RESOURCES_LOCK")
    private AppResourceRepository myAppResources;

    @GuardedBy("PROJECT_RESOURCES_LOCK")
    private ProjectResourceRepository myProjectResources;

    @GuardedBy("MODULE_RESOURCES_LOCK")
    private LocalResourceRepository myModuleResources;

    @GuardedBy("TEST_RESOURCES_LOCK")
    private LocalResourceRepository myTestAppResources;

    @GuardedBy("TEST_RESOURCES_LOCK")
    private LocalResourceRepository myTestModuleResources;

    @GuardedBy("PROJECT_RESOURCES_LOCK")
    private CachedValue<LocalesAndLanguages> myLocalesAndLanguages;

    /** Libraries and their corresponding resource repositories. */
    @GuardedBy("myLibraryLock")
    private Map<ExternalAndroidLibrary, AarResourceRepository> myLibraryResourceMap;

    private final Object myLibraryLock = new Object();

    @NotNull
    public static ResourceRepositoryManager getInstance(@NotNull AndroidModule facet) {
        Namespacing namespacing = Strings.isNullOrEmpty(facet.getNamespace()) ?
                Namespacing.DISABLED
                : Namespacing.REQUIRED;
        ResourceRepositoryManager instance = facet.getUserData(KEY);
        if (instance != null && instance.myNamespacing != namespacing) {
//            if (facet.replace(KEY, instance, null)) {
//                Disposer.dispose(instance);
//            }
            instance = null;
        }

        if (instance == null) {
            ResourceRepositoryManager manager = new ResourceRepositoryManager(facet, namespacing);
            instance = facet.putUserDataIfAbsent(KEY, manager);
//            if (instance == manager) {
//                // Our object ended up stored in the facet.
//                Disposer.register(facet, instance);
//                AndroidProjectRootListener.ensureSubscribed(manager.getProject());
//            }
        }
        return instance;
    }

    private ResourceRepositoryManager(@NotNull AndroidModule facet, @NotNull Namespacing namespacing) {
        myFacet = facet;
        myNamespacing = namespacing;
    }
    /**
     * Computes and returns the project resources.
     *
     * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
     * action lock.
     *
     * @return the resource repository or null if the module is not an Android module
     * @see #getProjectResources()
     */
    @Nullable
    public static LocalResourceRepository getProjectResources(@NotNull Module module) {
        if (!(module instanceof AndroidModule)) {
            return null;
        }

        return getProjectResources(((AndroidModule) module));
    }

    /**
     * Computes and returns the module resources.
     *
     * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
     * action lock.
     *
     * @return the resource repository or null if the module is not an Android module
     * @see #getModuleResources()
     */
    @Nullable
    public static LocalResourceRepository getModuleResources(@NotNull Module module) {
        if (!(module instanceof AndroidModule)) {
            return null;
        }
        return getModuleResources(((AndroidModule) module));
    }

    /**
     * Computes and returns the module resources.
     *
     * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
     * action lock.
     *
     * @see #getModuleResources()
     */
    @NotNull
    public static LocalResourceRepository getModuleResources(@NotNull AndroidModule facet) {
        return getInstance(facet).getModuleResources();
    }


    /**
     * Computes and returns the project resources.
     *
     * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
     * action lock.
     *
     * @see #getProjectResources()
     */
    @NotNull
    public static LocalResourceRepository getProjectResources(@NotNull AndroidModule facet) {
        return getInstance(facet).getProjectResources();
    }

    // instance methods
    /**
     * Returns the resource repository for a module along with all its (local) module dependencies.
     * The repository doesn't contain resources from AAR dependencies.
     *
     * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time,
     * or block waiting for a read action lock.
     *
     * @return the computed repository
     * @see #getCachedProjectResources()
     */
    @NotNull
    public LocalResourceRepository getProjectResources() {
        synchronized (PROJECT_RESOURCES_LOCK) {
            if (myProjectResources == null) {
//                if (myFacet.isDisposed()) {
//                    return new EmptyRepository(getNamespace());
//                }
                myProjectResources = ProjectResourceRepository.create(myFacet);
//                Disposer.register(this, myProjectResources);
            }
            return myProjectResources;
        }
    }

    /**
     * Returns the previously computed resource repository for a module along with all its (local) module dependencies.
     * The repository doesn't contain resources from AAR dependencies.
     *
     * @return the repository, or null if the repository hasn't been created yet
     * @see #getProjectResources()
     */
    @Nullable
    public LocalResourceRepository getCachedProjectResources() {
        synchronized (PROJECT_RESOURCES_LOCK) {
            return myProjectResources;
        }
    }

    /**
     * Returns the resource repository for a single module (which can possibly have multiple resource folders).
     * Does not include resources from any dependencies.
     *
     * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time,
     * or block waiting for a read action lock.
     *
     * @return the computed repository
     * @see #getCachedModuleResources()
     */
    @NotNull
    public LocalResourceRepository getModuleResources() {
        LocalResourceRepository moduleResources = getCachedModuleResources();
        if (moduleResources != null) {
            return moduleResources;
        }

        synchronized (MODULE_RESOURCES_LOCK) {
            if (myModuleResources == null) {
//                if (myFacet.isDisposed()) {
//                    return new EmptyRepository(getNamespace());
//                }
                myModuleResources = ModuleResourceRepository.forMainResources(myFacet, getNamespace());
//                registerIfDisposable(this, myModuleResources);
            }
            return myModuleResources;
        }
    }

    /**
     * Returns the previously computed resource repository for a single module (which can possibly have multiple
     * resource folders). Does not include resources from any dependencies.
     *
     * @return the repository, or null if the repository hasn't been created yet
     * @see #getModuleResources()
     */
    @Nullable
    public LocalResourceRepository getCachedModuleResources() {
        synchronized (MODULE_RESOURCES_LOCK) {
            return myModuleResources;
        }
    }

    /**
     * Returns the repository with all non-framework resources available to a given module (in the current variant).
     * This includes not just the resources defined in this module, but in any other modules that this module depends
     * on, as well as any libraries those modules may depend on (e.g. appcompat). This repository also contains sample
     * data resources associated with the {@link ResourceNamespace#TOOLS} namespace.
     *
     * <p>When a layout is rendered in the layout editor, it is getting resources from the app resource repository:
     * it should see all the resources just like the app does.
     *
     * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time,
     * or block waiting for a read action lock.
     *
     * @return the computed repository
     * @see #getCachedAppResources()
     */
    @NotNull
    public LocalResourceRepository getAppResources() {
        LocalResourceRepository appResources = getCachedAppResources();
        if (appResources != null) {
            return appResources;
        }

        getLibraryResources(); // Precompute library resources to do less work inside the read action below.

        synchronized (APP_RESOURCES_LOCK) {
            if (myAppResources == null) {
                if (myFacet == null) {
                    return new LocalResourceRepository.EmptyRepository(getNamespace());
                }
                myAppResources = AppResourceRepository.create(myFacet, getLibraryResources());
//                Disposer.register(this, myAppResources);
            }
            return myAppResources;
        }
    }

    /**
     * Returns the previously computed repository with all non-framework resources available to a given module
     * (in the current variant). This includes not just the resources defined in this module, but in any other
     * modules that this module depends on, as well as any AARs those modules depend on (e.g. appcompat). This
     * repository also contains sample data resources associated with the {@link ResourceNamespace#TOOLS} namespace.
     *
     * @return the repository, or null if the repository hasn't been created yet
     * @see #getAppResources()
     */
    @Nullable
    public LocalResourceRepository getCachedAppResources() {
        synchronized (APP_RESOURCES_LOCK) {
            return myAppResources;
        }
    }

    /**
     * Returns resource repositories for all libraries the app depends upon directly or indirectly.
     *
     * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time,
     * or block waiting for a read action lock.
     */
    @NotNull
    public Collection<AarResourceRepository> getLibraryResources() {
        return getLibraryResourceMap().values();
    }

    @NotNull
    private Map<ExternalAndroidLibrary, AarResourceRepository> getLibraryResourceMap() {
        synchronized (myLibraryLock) {
            if (myLibraryResourceMap == null) {
                myLibraryResourceMap = computeLibraryResourceMap();
            }
            return myLibraryResourceMap;
        }
    }

    @NotNull
    private Map<ExternalAndroidLibrary, AarResourceRepository> computeLibraryResourceMap() {
        List<CodeAssistAndroidLibrary> codeAssistLibraries =
                ((AndroidModuleImpl) myFacet).getCodeAssistLibraries().stream()
                        .filter(it -> it instanceof CodeAssistAndroidLibrary)
                        .map(it -> (CodeAssistAndroidLibrary) it)
                        .collect(Collectors.toList());
        AarResourceRepositoryCache aarResourceRepositoryCache = AarResourceRepositoryCache.getInstance();
        Function<ExternalAndroidLibrary, AarResourceRepository> factory = myNamespacing == Namespacing.DISABLED ?
                aarResourceRepositoryCache::getSourceRepository :
                aarResourceRepositoryCache::getProtoRepository;

        ExecutorService executor = Executors.newSingleThreadExecutor();

        // Construct the repositories in parallel.
        Map<ExternalAndroidLibrary, Future<AarResourceRepository>> futures = Maps.newHashMapWithExpectedSize(codeAssistLibraries.size());
        for (CodeAssistAndroidLibrary library : codeAssistLibraries) {
            if (!library.getResFolder().exists() && library.getResStaticLibrary() == null) {
                continue;
            }
            ExternalLibraryImpl externalLibrary = new ExternalLibraryImpl(library.getDeclaration(),
                    null,
                    null,
                    "",
                    new RecursiveResourceFolder(toPathString(library.getResFolder())),
                    null,
                    toPathString(library.getSymbolFile()),
                    library.getResStaticLibrary() == null ? null : toPathString(library.getResStaticLibrary())
            );
            futures.put(externalLibrary, executor.submit(() -> factory.apply(externalLibrary)));
        }

        // Gather all the results.
        ImmutableMap.Builder<ExternalAndroidLibrary, AarResourceRepository> map = ImmutableMap.builder();
        for (Map.Entry<ExternalAndroidLibrary, Future<AarResourceRepository>> entry : futures.entrySet()) {
            try {
                map.put(entry.getKey(), entry.getValue().get());
            } catch (ExecutionException e) {
                cancelPendingTasks(futures.values());
                Throwables.throwIfUnchecked(e.getCause());
                throw new UncheckedExecutionException(e.getCause());
            } catch (InterruptedException e) {
                cancelPendingTasks(futures.values());
                throw new ProcessCanceledException(e);
            }
        }
        return map.build();
    }

    private static void cancelPendingTasks(Collection<Future<AarResourceRepository>> futures) {
        futures.forEach(f -> f.cancel(true));
    }

    /**
     * Returns the {@link ResourceNamespace} used by the current module.
     *
     * <p>This is read from the manifest, so needs to be run inside a read action.
     */
    @NotNull
    public ResourceNamespace getNamespace() {
        if (myNamespacing == Namespacing.DISABLED) {
            return ResourceNamespace.RES_AUTO;
        }

        String namespace = myFacet.getNamespace();
        String packageName = myFacet.getPackageName();

        if (packageName == null && namespace == null) {
            return ResourceNamespace.RES_AUTO;
        }

        if (namespace == null) {
            namespace = packageName;
        }

        if (mySharedNamespaceInstance == null || !namespace.equals(mySharedNamespaceInstance.getPackageName())) {
            mySharedNamespaceInstance = ResourceNamespace.fromPackageName(namespace);
        }

        return mySharedNamespaceInstance;
    }

    private static class LocalesAndLanguages {
        @NotNull final ImmutableList<Locale> locales;
        @NotNull final ImmutableSortedSet<String> languages;

        LocalesAndLanguages(@NotNull ImmutableList<Locale> locales, @NotNull ImmutableSortedSet<String> languages) {
            this.locales = locales;
            this.languages = languages;
        }
    }
}
