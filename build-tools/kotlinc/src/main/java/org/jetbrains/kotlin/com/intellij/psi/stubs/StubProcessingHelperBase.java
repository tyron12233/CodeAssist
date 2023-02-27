package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiBinaryFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileWithStubSupport;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.StubbedSpine;
import org.jetbrains.kotlin.com.intellij.psi.search.FileTypeIndex;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Collections;
import java.util.List;

/**
 * Author: dmitrylomov
 */
public abstract class StubProcessingHelperBase {
  protected static final Logger LOG = Logger.getInstance(StubProcessingHelperBase.class);

  public <Psi extends PsiElement> boolean processStubsInFile(@NonNull Project project,
                                                             @NonNull VirtualFile file,
                                                             @NonNull StubIdList value,
                                                             @NonNull Processor<? super Psi> processor,
                                                             @Nullable GlobalSearchScope scope,
                                                             @NonNull Class<Psi> requiredClass) {
    PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
    if (psiFile == null) {
      LOG.error("Stub index points to a file without PSI: " +
                getFileTypeInfo(file, project) + ", " +
                "indexing stamp info = " + StubTreeLoader.getInstance().getIndexingStampInfo(file) + ", " +
                "used scope = " + scope);
      onInternalError(file);
      return true;
    }

    if (value.size() == 1 && value.get(0) == 0) {
      //noinspection unchecked
      return !checkType(requiredClass, psiFile, psiFile) || processor.process((Psi)psiFile);
    }

    List<StubbedSpine> spines = getAllSpines(psiFile);
    if (spines.isEmpty()) {
      return handleNonPsiStubs(file, processor, requiredClass, psiFile);
    }

    for (int i = 0, size = value.size(); i < size; i++) {
      PsiElement psi = getStubPsi(spines, value.get(i));
        if (!checkType(requiredClass, psiFile, psi)) {
            break;
        }
      //noinspection unchecked
        if (!processor.process((Psi) psi)) {
            return false;
        }
    }
    return true;
  }

  @NonNull
  private static List<StubbedSpine> getAllSpines(PsiFile psiFile) {
    if (!(psiFile instanceof PsiFileImpl) && psiFile instanceof PsiFileWithStubSupport) {
      return Collections.singletonList(((PsiFileWithStubSupport)psiFile).getStubbedSpine());
    }

    return ContainerUtil.map(StubTreeBuilder.getStubbedRoots(psiFile.getViewProvider()), t -> ((PsiFileImpl)t.second).getStubbedSpine());
  }

  private <Psi extends PsiElement> boolean checkType(@NonNull Class<Psi> requiredClass, PsiFile psiFile, PsiElement psiElement) {
      if (requiredClass.isInstance(psiElement)) {
          return true;
      }

    StubTree stubTree = ((PsiFileWithStubSupport)psiFile).getStubTree();
      if (stubTree == null && psiFile instanceof PsiFileImpl) {
          stubTree = ((PsiFileImpl) psiFile).calcStubTree();
      }
    inconsistencyDetected(stubTree, (PsiFileWithStubSupport)psiFile);
    return false;
  }

  private static PsiElement getStubPsi(List<? extends StubbedSpine> spines, int index) {
      if (spines.size() == 1) {
          return spines.get(0).getStubPsi(index);
      }

    for (StubbedSpine spine : spines) {
      int count = spine.getStubCount();
      if (index < count) {
        return spine.getStubPsi(index);
      }
      index -= count;
    }
    return null;
  }

  // e.g. DOM indices
  private <Psi extends PsiElement> boolean handleNonPsiStubs(@NonNull VirtualFile file,
                                                             @NonNull Processor<? super Psi> processor,
                                                             @NonNull Class<Psi> requiredClass,
                                                             @NonNull PsiFile psiFile) {
    if (BinaryFileStubBuilders.INSTANCE.forFileType(psiFile.getFileType()) == null) {
      LOG.error("unable to get stub builder for file with " + getFileTypeInfo(file, psiFile.getProject()) + ", " +
                StubTreeLoader.getFileViewProviderMismatchDiagnostics(psiFile.getViewProvider()));
      onInternalError(file);
      return true;
    }

    if (psiFile instanceof PsiBinaryFile) {
      // a file can be indexed as containing stubs,
      // but then in a specific project FileViewProviderFactory can decide not to create stub-aware PSI
      // because the file isn't in expected location
      return true;
    }

    ObjectStubTree objectStubTree = StubTreeLoader.getInstance().readFromVFile(psiFile.getProject(), file);
    if (objectStubTree == null) {
      LOG.error("Stub index points to a file without indexed stubs: " + getFileTypeInfo(file, psiFile.getProject()));
      onInternalError(file);
      return true;
    }
    if (objectStubTree instanceof StubTree) {
      LOG.error("Stub index points to a file with PSI stubs (instead of non-PSI ones): " + getFileTypeInfo(file, psiFile.getProject()));
      onInternalError(file);
      return true;
    }
    if (!requiredClass.isInstance(psiFile)) {
      inconsistencyDetected(objectStubTree, (PsiFileWithStubSupport)psiFile);
      return true;
    }
    //noinspection unchecked
    return processor.process((Psi)psiFile);
  }

  private void inconsistencyDetected(@Nullable ObjectStubTree stubTree, @NonNull PsiFileWithStubSupport psiFile) {
    try {
      StubTextInconsistencyException.checkStubTextConsistency(psiFile);
      LOG.error(StubTreeLoader.getInstance().stubTreeAndIndexDoNotMatch(stubTree, psiFile, null));
    }
    finally {
      onInternalError(psiFile.getVirtualFile());
    }
  }

  protected abstract void onInternalError(VirtualFile file);

  @NonNull
  protected static String getFileTypeInfo(@NonNull VirtualFile file, @NonNull Project project) {
    return "file = " + file + (file.isValid() ? "" : " (invalid)") + ", " +
           "file type = " + file.getFileType() + ", " +
           "indexed file type = " + FileTypeIndex.getIndexedFileType(file, project);
  }

}