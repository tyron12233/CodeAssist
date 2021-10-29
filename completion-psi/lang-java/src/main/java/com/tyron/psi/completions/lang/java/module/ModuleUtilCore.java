package com.tyron.psi.completions.lang.java.module;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.*;

import java.util.Objects;
import java.util.Set;

public class ModuleUtilCore {

    public static final Key<Module> KEY_MODULE = new Key<>("Module");

    @Nullable
    public static Module findModuleForPsiElement(@NotNull PsiElement element) {
        PsiFile containingFile = element.getContainingFile();
        if (!Objects.requireNonNullElse(containingFile, element).isValid()) {
            return null;
        }

        Project project = (containingFile == null ? element : containingFile).getProject();
        if (project.isDefault()) return null;
//        final ProjectFileIndex fileIndex = ProjectFileIndex.SERVICE.getInstance(project);
//
//        if (element instanceof PsiFileSystemItem && (!(element instanceof PsiFile) || element.getContext() == null)) {
//            VirtualFile vFile = ((PsiFileSystemItem)element).getVirtualFile();
//            if (vFile == null) {
//                vFile = containingFile == null ? null : containingFile.getOriginalFile().getVirtualFile();
//                if (vFile == null) {
//                    return element.getUserData(KEY_MODULE);
//                }
//            }
//            if (fileIndex.isInLibrary(vFile)) {
//                final List<OrderEntry> orderEntries = fileIndex.getOrderEntriesForFile(vFile);
//                if (orderEntries.isEmpty()) {
//                    return null;
//                }
//                if (orderEntries.size() == 1) {
//                    return orderEntries.get(0).getOwnerModule();
//                }
//                Set<Module> modules = new HashSet<>();
//                for (OrderEntry orderEntry : orderEntries) {
//                    modules.add(orderEntry.getOwnerModule());
//                }
//                final Module[] candidates = modules.toArray(Module.EMPTY_ARRAY);
//                Arrays.sort(candidates, ModuleManager.getInstance(project).moduleDependencyComparator());
//                return candidates[0];
//            }
//            return fileIndex.getModuleForFile(vFile);
//        }
//        if (containingFile != null) {
//            PsiElement context;
//            while ((context = containingFile.getContext()) != null) {
//                final PsiFile file = context.getContainingFile();
//                if (file == null) break;
//                containingFile = file;
//            }
//
//            if (containingFile.getUserData(KEY_MODULE) != null) {
//                return containingFile.getUserData(KEY_MODULE);
//            }
//
//            final PsiFile originalFile = containingFile.getOriginalFile();
//            if (originalFile.getUserData(KEY_MODULE) != null) {
//                return originalFile.getUserData(KEY_MODULE);
//            }
//
//            final VirtualFile virtualFile = originalFile.getVirtualFile();
//            if (virtualFile != null) {
//                return fileIndex.getModuleForFile(virtualFile);
//            }
//        }

        return element.getUserData(KEY_MODULE);
    }
}
