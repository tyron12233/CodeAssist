package com.tyron.builder.dexing.r8;

import com.android.tools.r8.ClassFileResourceProvider;
import com.android.tools.r8.DirectoryClassFileProvider;
import com.android.tools.r8.ProgramResource;
import com.google.common.base.Suppliers;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Provides {@link ClassFileResourceProvider} suitable for D8/R8 classpath and bootclasspath
 * entries. Some of those may be shared.
 */
public class ClassFileProviderFactory implements Closeable {

    /**
     * Ordered class file provider. When looking for a descriptor, it searches from the first, until
     * the last specified provider.
     */
    private static class OrderedClassFileResourceProvider implements ClassFileResourceProvider {
        private final Supplier<Map<String, ClassFileResourceProvider>> descriptors;

        OrderedClassFileResourceProvider(List<ClassFileResourceProvider> providers) {
            this.descriptors =
                    Suppliers.memoize(
                            () -> {
                                Map<String, ClassFileResourceProvider> descs = Maps.newHashMap();
                                for (ClassFileResourceProvider provider : providers) {
                                    for (String s : provider.getClassDescriptors()) {
                                        if (!descs.containsKey(s)) {
                                            descs.put(s, provider);
                                        }
                                    }
                                }
                                return descs;
                            });
        }

        @Override
        public Set<String> getClassDescriptors() {
            return descriptors.get().keySet();
        }

        @Override
        public ProgramResource getProgramResource(String descriptor) {
            ClassFileResourceProvider provider = descriptors.get().get(descriptor);
            if (provider == null) {
                return null;
            }
            return provider.getProgramResource(descriptor);
        }
    }

    @NotNull
    private static final AtomicLong nextId = new AtomicLong();

    @NotNull private List<ClassFileResourceProvider> providers;
    @NotNull private final OrderedClassFileResourceProvider orderedClassFileResourceProvider;
    private final long id;

    public ClassFileProviderFactory(@NotNull Collection<Path> paths) throws IOException {
        id = nextId.addAndGet(1);

        providers = Lists.newArrayListWithExpectedSize(paths.size());
        for (Path path : paths) {
            if (path.toFile().exists()) {
                providers.add(createProvider(path));
            }
        }

        orderedClassFileResourceProvider = new OrderedClassFileResourceProvider(providers);
    }

    public long getId() {
        return id;
    }

    @Override
    public void close() throws IOException {
        // Close providers and clear
        for (ClassFileResourceProvider provider : providers) {
            if (provider instanceof Closeable) {
                ((Closeable) provider).close();
            }
        }
        providers.clear();
    }

    @NotNull
    public ClassFileResourceProvider getOrderedProvider() {
        return orderedClassFileResourceProvider;
    }

    @NotNull
    private static ClassFileResourceProvider createProvider(@NotNull Path entry)
            throws IOException {
        if (Files.isRegularFile(entry)) {
            return new CachingArchiveClassFileProvider(entry);
        } else if (Files.isDirectory(entry)) {
            return DirectoryClassFileProvider.fromDirectory(entry);
        } else {
            throw new FileNotFoundException(entry.toString());
        }
    }
}