package com.tyron.psi.patterns;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiDirectory;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author spleaner
 * @see PlatformPatterns#psiFile()
 */
public class PsiFilePattern<T extends PsiFile, Self extends PsiFilePattern<T, Self>> extends PsiElementPattern<T, Self> {

    protected PsiFilePattern(@NotNull final InitialPatternCondition<T> condition) {
        super(condition);
    }

    protected PsiFilePattern(final Class<T> aClass) {
        super(aClass);
    }

    public Self withParentDirectoryName(final StringPattern namePattern) {
        return with(new PatternCondition<T>("withParentDirectoryName") {
            @Override
            public boolean accepts(@NotNull final T t, final ProcessingContext context) {
                PsiDirectory directory = t.getContainingDirectory();
                return directory != null && namePattern.accepts(directory.getName(), context);
            }
        });
    }

    public Self withOriginalFile(final ElementPattern<? extends T> filePattern) {
        return with(new PatternCondition<T>("withOriginalFile") {
            @Override
            public boolean accepts(@NotNull T file, ProcessingContext context) {
                return filePattern.accepts(file.getOriginalFile());
            }
        });
    }

    public Self withVirtualFile(final ElementPattern<? extends VirtualFile> vFilePattern) {
        return with(new PatternCondition<T>("withVirtualFile") {
            @Override
            public boolean accepts(@NotNull T file, ProcessingContext context) {
                return vFilePattern.accepts(file.getVirtualFile(), context);
            }
        });
    }

    public Self withFileType(final ElementPattern<? extends FileType> fileTypePattern) {
        return with(new PatternCondition<T>("withFileType") {
            @Override
            public boolean accepts(@NotNull T file, ProcessingContext context) {
                return fileTypePattern.accepts(file.getFileType(), context);
            }
        });
    }

    public static class Capture<T extends PsiFile> extends PsiFilePattern<T, Capture<T>> {

        protected Capture(final Class<T> aClass) {
            super(aClass);
        }

        public Capture(@NotNull final InitialPatternCondition<T> condition) {
            super(condition);
        }
    }
}