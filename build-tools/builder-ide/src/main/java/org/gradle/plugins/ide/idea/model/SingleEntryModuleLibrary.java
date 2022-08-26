package org.gradle.plugins.ide.idea.model;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.gradle.api.artifacts.ModuleVersionIdentifier;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collections;
import java.util.Set;

/**
 * Single entry module library
 */
public class SingleEntryModuleLibrary extends ModuleLibrary {

    private ModuleVersionIdentifier moduleVersion;

    /**
     * Creates single entry module library
     *
     * @param library a path to jar or class folder in idea format
     * @param javadoc paths to javadoc jars or javadoc folders
     * @param source paths to source jars or source folders
     * @param scope scope
     */
    public SingleEntryModuleLibrary(FilePath library, Set<FilePath> javadoc, Set<FilePath> source, String scope) {
        super(Collections.singletonList(library), javadoc, source, Lists.<JarDirectory>newArrayList(), scope);
    }

    /**
     * Creates single entry module library
     *
     * @param library a path to jar or class folder in idea format
     * @param javadoc path to javadoc jars or javadoc folders
     * @param source paths to source jars or source folders
     * @param scope scope
     */
    public SingleEntryModuleLibrary(FilePath library, @Nullable FilePath javadoc, @Nullable FilePath source, String scope) {
        super(
            Collections.singletonList(library),
            javadoc != null ? Collections.singletonList(javadoc) : Lists.<Path>newArrayList(),
            source != null ? Collections.singletonList(source) : Lists.<Path>newArrayList(),
            Sets.<JarDirectory>newLinkedHashSet(),
            scope
        );
    }

    /**
     * Creates single entry module library
     *
     * @param library a path to jar or class folder in Path format
     * @param scope scope
     */
    public SingleEntryModuleLibrary(FilePath library, String scope) {
        this(library, Sets.<FilePath>newLinkedHashSet(), Sets.<FilePath>newLinkedHashSet(), scope);
    }

    /**
     * Module version of the library, if any.
     */
    @Nullable
    public ModuleVersionIdentifier getModuleVersion() {
        return moduleVersion;
    }

    public void setModuleVersion(@Nullable ModuleVersionIdentifier moduleVersion) {
        this.moduleVersion = moduleVersion;
    }

    /**
     * Returns a single jar or class folder
     */
    public File getLibraryFile() {
        return ((FilePath) this.getClasses().iterator().next()).getFile();
    }

    /**
     * Returns a single javadoc jar or javadoc folder
     */
    public File getJavadocFile() {
        if (getJavadoc().size() > 0) {
            return ((FilePath) this.getJavadoc().iterator().next()).getFile();
        } else {
            return null;
        }
    }

    /**
     * Returns a single source jar or source folder
     */
    public File getSourceFile() {
        if (getSources().size() > 0) {
            return ((FilePath) this.getSources().iterator().next()).getFile();
        } else {
            return null;
        }
    }
}
