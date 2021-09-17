//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.jetbrains.kotlin.com.intellij.ide.plugins;

import com.github.marschall.com.sun.nio.zipfs.ZipFileSystemProvider;

import gnu.trove.THashMap;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * I have modified this class to use {@link com.github.marschall.com.sun.nio.zipfs.ZipFileSystem} because
 * android doesn't provide a ZipFileSystem
 */
final class DescriptorLoadingContext implements AutoCloseable {
    private final Map<Path, FileSystem> openedFiles = new THashMap<>();
    final DescriptorListLoadingContext parentContext;
    final boolean isBundled;
    final boolean isEssential;

    final PathBasedJdomXIncluder.PathResolver<?> pathResolver;

    /**
     * parentContext is null only for CoreApplicationEnvironment - it is not valid otherwise because in this case XML is not interned.
     */
    DescriptorLoadingContext(DescriptorListLoadingContext parentContext,
                             boolean isBundled,
                             boolean isEssential,
                             PathBasedJdomXIncluder.PathResolver<?> pathResolver) {
        this.parentContext = parentContext;
        this.isBundled = isBundled;
        this.isEssential = isEssential;
        this.pathResolver = pathResolver;
    }

     FileSystem open(Path file) throws IOException {
        FileSystem result = openedFiles.get(file);
        if (result == null) {
            result = new ZipFileSystemProvider().newFileSystem(file, Collections.emptyMap());
            openedFiles.put(file, result);
        }
        return result;
    }

    @Override
    public void close() {
        for (FileSystem file : openedFiles.values()) {
            try {
                file.close();
            }
            catch (IOException ignore) {
            }
        }
    }


    public DescriptorLoadingContext copy(boolean isEssential) {
        return new DescriptorLoadingContext(parentContext, isBundled, isEssential, pathResolver);
    }
}