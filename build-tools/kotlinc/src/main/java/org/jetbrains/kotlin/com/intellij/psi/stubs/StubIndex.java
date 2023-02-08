package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.CachedSingletonsRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.ClearableLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.tree.StubFileElementType;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.Processors;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public abstract class StubIndex {
    private static final ClearableLazyValue<StubIndex> ourInstance =
            CachedSingletonsRegistry.markLazyValue(new ClearableLazyValue<StubIndex>() {
                @NonNull
                @Override
                protected StubIndex compute() {
                    return ApplicationManager.getApplication().getService(StubIndex.class);
                }
            });

    public static StubIndex getInstance() {
        return ourInstance.getValue();
    }

    /**
     * @deprecated use {@link #getElements(StubIndexKey, Object, Project, GlobalSearchScope, Class)}
     */
    @Deprecated(forRemoval = true)
    public <Key, Psi extends PsiElement> Collection<Psi> get(@NonNull StubIndexKey<Key, Psi> indexKey,
                                                             @NonNull Key key,
                                                             @NonNull Project project,
                                                             @Nullable final GlobalSearchScope scope) {
        List<Psi> result = new SmartList<>();
        //noinspection unchecked
        processElements(indexKey,
                key,
                project,
                scope,
                (Class<Psi>) PsiElement.class,
                Processors.cancelableCollectProcessor(result));
        return result;
    }

    public <Key, Psi extends PsiElement> boolean processElements(@NonNull StubIndexKey<Key, Psi> indexKey,
                                                                 @NonNull Key key,
                                                                 @NonNull Project project,
                                                                 @Nullable GlobalSearchScope scope,
                                                                 @NonNull Class<Psi> requiredClass,
                                                                 @NonNull Processor<? super Psi> processor) {
        return processElements(indexKey, key, project, scope, null, requiredClass, processor);
    }

    public <Key, Psi extends PsiElement> boolean processElements(@NonNull StubIndexKey<Key, Psi> indexKey,
                                                                 @NonNull Key key,
                                                                 @NonNull Project project,
                                                                 @Nullable GlobalSearchScope scope,
                                                                 @Nullable IdFilter idFilter,
                                                                 @NonNull Class<Psi> requiredClass,
                                                                 @NonNull Processor<? super Psi> processor) {
        return processElements(indexKey, key, project, scope, requiredClass, processor);
    }

    @NonNull
    public abstract <Key> Collection<Key> getAllKeys(@NonNull StubIndexKey<Key, ?> indexKey,
                                                     @NonNull Project project);

    public <K> boolean processAllKeys(@NonNull StubIndexKey<K, ?> indexKey,
                                      @NonNull Project project,
                                      @NonNull Processor<? super K> processor) {
        return processAllKeys(indexKey, processor, GlobalSearchScope.allScope(project), null);
    }

    public <K> boolean processAllKeys(@NonNull StubIndexKey<K, ?> indexKey,
                                      @NonNull Processor<? super K> processor,
                                      @NonNull GlobalSearchScope scope) {
        return processAllKeys(indexKey, processor, scope, null);
    }

    public <K> boolean processAllKeys(@NonNull StubIndexKey<K, ?> indexKey,
                                      @NonNull Processor<? super K> processor,
                                      @NonNull GlobalSearchScope scope,
                                      @Nullable IdFilter idFilter) {
        return processAllKeys(indexKey, Objects.requireNonNull(scope.getProject()), processor);
    }

    @NonNull
    public static <Key, Psi extends PsiElement> Collection<Psi> getElements(@NonNull StubIndexKey<Key, Psi> indexKey,
                                                                            @NonNull Key key,
                                                                            @NonNull final Project project,
                                                                            @Nullable final GlobalSearchScope scope,
                                                                            @NonNull Class<Psi> requiredClass) {
        return getElements(indexKey, key, project, scope, null, requiredClass);
    }

    @NonNull
    public static <Key, Psi extends PsiElement> Collection<Psi> getElements(@NonNull StubIndexKey<Key, Psi> indexKey,
                                                                            @NonNull Key key,
                                                                            @NonNull final Project project,
                                                                            @Nullable final GlobalSearchScope scope,
                                                                            @Nullable IdFilter idFilter,
                                                                            @NonNull Class<Psi> requiredClass) {
        final List<Psi> result = new SmartList<>();
        Processor<Psi> processor = Processors.cancelableCollectProcessor(result);
        getInstance().processElements(indexKey,
                key,
                project,
                scope,
                idFilter,
                requiredClass,
                processor);
        return result;
    }

    /**
     * @return lazily reified iterator of VirtualFile's.
     */
    @NonNull
    public abstract <Key> Iterator<VirtualFile> getContainingFilesIterator(@NonNull StubIndexKey<Key, ?> indexKey,
                                                                           @NonNull Key dataKey,
                                                                           @NonNull Project project,
                                                                           @NonNull GlobalSearchScope scope);

    /**
     * @deprecated use {@link StubIndex#getContainingFilesIterator(StubIndexKey, Object, Project, GlobalSearchScope)}
     */
    @Deprecated(forRemoval = true)
    @NonNull
    public <Key> Set<VirtualFile> getContainingFiles(@NonNull StubIndexKey<Key, ?> indexKey,
                                                     @NonNull Key dataKey,
                                                     @NonNull Project project,
                                                     @NonNull GlobalSearchScope scope) {
        Set<VirtualFile> files = new HashSet<>();
        Iterator<VirtualFile> containingFilesIterator =
                getContainingFilesIterator(indexKey, dataKey, project, scope);
        containingFilesIterator.forEachRemaining(files::add);
        return files;
    }

    public abstract <Key> int getMaxContainingFileCount(@NonNull StubIndexKey<Key, ?> indexKey,
                                                        @NonNull Key dataKey,
                                                        @NonNull Project project,
                                                        @NonNull GlobalSearchScope scope);


    public abstract void forceRebuild(@NonNull Throwable e);

    /**
     * @param fileElementType {@link StubFileElementType} to track changes for.
     * @return {@link ModificationTracker} that changes stamp on every file update (with
     * corresponding {@link StubFileElementType})
     * for which the stub has changed.
     * @implNote doesn't track changes of files with binary content. Modification tracking
     * happens before the StubIndex update, so one can use
     * this tracker to react on stub changes without performing the index update. File is
     * considered modified if a stub for its actual content
     * differs from what is stored in the index. Modification detector might react
     * false-positively when the number of changed files is big.
     */
    public abstract @NonNull ModificationTracker getPerFileElementTypeModificationTracker(@NonNull StubFileElementType<?> fileElementType);

    public abstract @NonNull ModificationTracker getStubIndexModificationTracker(@NonNull Project project);
}