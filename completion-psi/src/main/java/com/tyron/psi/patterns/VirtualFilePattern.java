package com.tyron.psi.patterns;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @see PlatformPatterns#virtualFile()
 */
public class VirtualFilePattern extends TreeElementPattern<VirtualFile, VirtualFile, VirtualFilePattern> {
    public VirtualFilePattern() {
        super(VirtualFile.class);
    }

    public VirtualFilePattern ofType(final FileType type) {
        // Avoid capturing FileType instance if plugin providing the file type is unloaded
        String fileTypeName = type.getName();
        return with(new PatternCondition<VirtualFile>("ofType") {
            @Override
            public boolean accepts(@NotNull final VirtualFile virtualFile, final ProcessingContext context) {
                return virtualFile.getFileType().getName().equals(fileTypeName);
            }
        });
    }

    /**
     * @see #withName(ElementPattern)
     */
    public VirtualFilePattern withName(final String name) {
        return withName(StandardPatterns.string().equalTo(name));
    }

    public VirtualFilePattern withExtension(@NotNull @NonNls String... alternatives) {
        return with(new PatternCondition<VirtualFile>("withExtension") {
            @Override
            public boolean accepts(@NotNull final VirtualFile virtualFile, final ProcessingContext context) {
                final String extension = virtualFile.getExtension();
                for (String alternative : alternatives) {
                    if (alternative.equals(extension)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    public VirtualFilePattern withExtension(@NonNls @NotNull final String extension) {
        return with(new PatternCondition<VirtualFile>("withExtension") {
            @Override
            public boolean accepts(@NotNull final VirtualFile virtualFile, final ProcessingContext context) {
                return extension.equals(virtualFile.getExtension());
            }
        });
    }

    /**
     * @see #withName(String)
     */
    public VirtualFilePattern withName(final ElementPattern<String> namePattern) {
        return with(new PatternCondition<VirtualFile>("withName") {
            @Override
            public boolean accepts(@NotNull final VirtualFile virtualFile, final ProcessingContext context) {
                return namePattern.accepts(virtualFile.getName(), context);
            }
        });
    }

    public VirtualFilePattern withPath(final ElementPattern<String> pathPattern) {
        return with(new PatternCondition<VirtualFile>("withName") {
            @Override
            public boolean accepts(@NotNull final VirtualFile virtualFile, final ProcessingContext context) {
                return pathPattern.accepts(virtualFile.getPath(), context);
            }
        });
    }

    @Override
    protected VirtualFile getParent(@NotNull final VirtualFile t) {
        return t.getParent();
    }

    protected VirtualFile[] getChildren(@NotNull final VirtualFile file) {
        return file.getChildren();
    }
}