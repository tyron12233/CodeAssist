package org.jetbrains.kotlin.com.intellij.openapi.project;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.CachedSingletonsRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public abstract class ProjectLocator {

    // called very often by StubUpdatingIndex
    private static class InstanceHolder {
        private static final ProjectLocator INSTANCE = ApplicationManager.getApplication().getService(ProjectLocator.class);
    }

    private static final Supplier<ProjectLocator> ourInstance = () -> InstanceHolder.INSTANCE;

    private static final ThreadLocal<Map<VirtualFile, Project>> ourPreferredProjects =
            ThreadLocal.withInitial(HashMap::new);

    public static ProjectLocator getInstance() {
        return ourInstance.get();
    }

    /**
     * Returns an open project which contains the given file.
     * This is a guess-method, so if several projects contain the file, only one will be returned.
     * @param file file to be located in projects.
     * @return project which probably contains the file, or null if couldn't guess (for example, there are no open projects).
     */
    @Nullable
    public abstract Project guessProjectForFile(@NotNull VirtualFile file);

    /**
     * Gets all open projects containing the given file.
     * If none does, an empty list is returned.
     * @param file file to be located in projects.
     * @return list of open projects containing this file.
     */
    @NotNull
    public abstract Collection<Project> getProjectsForFile(@NotNull VirtualFile file);

    /**
     * Execute {@code runnable}, making sure that within this computation every call to
     * {@link #guessProjectForFile(VirtualFile)} for the {@code file} will return {@code preferredProject}
     */
    public static <T, E extends Throwable> T computeWithPreferredProject(@NotNull VirtualFile file,
                                                                         @NotNull Project preferredProject,
                                                                         @NotNull ThrowableComputable<T, E> runnable) throws E {
        Map<VirtualFile, Project> local = ourPreferredProjects.get();
        Project prev = local.put(file, preferredProject);
        try {
            return runnable.compute();
        }
        finally {
            if (prev == null) {
                local.remove(file);
            }
            else {
                local.put(file, prev);
            }
        }
    }

    @Nullable
    static Project getPreferredProject(@NotNull VirtualFile file) {
        return ourPreferredProjects.get().get(file);
    }
}
