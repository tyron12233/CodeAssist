package org.jetbrains.kotlin.com.intellij.pom;

import static org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeUtil.CONTAINING_FILE_KEY_AFTER_REPARSE;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.lang.ASTNode;
import org.jetbrains.kotlin.com.intellij.lang.FileASTNode;
import org.jetbrains.kotlin.com.intellij.model.ModelBranch;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicatorProvider;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.pom.core.impl.PomModelImpl;
import org.jetbrains.kotlin.com.intellij.pom.event.PomModelEvent;
import org.jetbrains.kotlin.com.intellij.pom.event.PomModelListener;
import org.jetbrains.kotlin.com.intellij.pom.impl.PomTransactionBase;
import org.jetbrains.kotlin.com.intellij.pom.tree.TreeAspect;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.SmartPointerManager;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.ChangedPsiRangeUtil;
import org.jetbrains.kotlin.com.intellij.psi.impl.DebugUtil;
import org.jetbrains.kotlin.com.intellij.psi.impl.DiffLog;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiToDocumentSynchronizer;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangeEventImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartPointerManagerImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.DummyHolder;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.FileElement;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.lang.CompoundRuntimeException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.Stack;

public class MockPomModel extends UserDataHolderBase implements PomModel {

    private final TreeAspect myTreeAspect;
    private final Project myProject;
    private final Collection<PomModelListener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();


    public MockPomModel(Project project) {
        myProject = project;

        myTreeAspect = new TreeAspect();
    }

    public static MockPomModel newInstance(Project project) {
        return new MockPomModel(project);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends PomModelAspect> T getModelAspect(@NonNull Class<T> aClass) {
        if (aClass.equals(TreeAspect.class)) {
            return (T) myTreeAspect;
        }
        return null;
    }

    private final ThreadLocal<Stack<PomTransaction>> myTransactionStack = ThreadLocal.withInitial(Stack::new);

    @Override
    public void runTransaction(@NonNull PomTransaction transaction) throws IncorrectOperationException{
        if (!isAllowPsiModification()) {
            throw new IncorrectOperationException("Must not modify PSI inside save listener");
        }
        ProgressManager.getInstance().executeNonCancelableSection(() -> {
            PsiElement changeScope = transaction.getChangeScope();
            PsiFile containingFileByTree = getContainingFileByTree(changeScope);
            Document document = startTransaction(transaction, containingFileByTree);

            PomTransaction block = getBlockingTransaction(changeScope);
            if (block != null) {
                block.getAccumulatedEvent().beforeNestedTransaction();
            }

            List<Throwable> throwables = new ArrayList<>(0);
            DebugUtil.performPsiModification(null, ()->{
                try{
                    Stack<PomTransaction> blockedAspects = myTransactionStack.get();
                    blockedAspects.push(transaction);

                    final PomModelEvent event;
                    try{
                        transaction.run();
                        event = transaction.getAccumulatedEvent();
                    }
                    catch (ProcessCanceledException e) {
                        throw e;
                    }
                    catch(Exception e){
                        throwables.add(e);
                        return;
                    }
                    finally{
                        blockedAspects.pop();
                    }
                    if(block != null){
                        block.getAccumulatedEvent().merge(event);
                        return;
                    }

//                    if (event.getChangedAspects().contains(myTreeAspect)) {
//                        updateDependentAspects(event);
//                    }

                    for (final PomModelListener listener : myListeners) {
                        final Set<PomModelAspect> changedAspects = event.getChangedAspects();
                        for (PomModelAspect modelAspect : changedAspects) {
                            if (listener.isAspectChangeInteresting(modelAspect)) {
                                listener.modelChanged(event);
                                break;
                            }
                        }
                    }
                }
                catch (ProcessCanceledException e) {
                    throw e;
                }
                catch (Throwable t) {
                    throwables.add(t);
                }
                finally {
                    try {
                        if (containingFileByTree != null) {
                            commitTransaction(containingFileByTree, document);
                        }
                    }
                    catch (ProcessCanceledException e) {
                        throw e;
                    }
                    catch (Throwable t) {
                        throwables.add(t);
                    }
                    if (!throwables.isEmpty()) CompoundRuntimeException.throwIfNotEmpty(throwables);
                }
            });
        });
    }

    @Nullable
    private PomTransaction getBlockingTransaction(PsiElement changeScope) {
        Stack<PomTransaction> blockedAspects = myTransactionStack.get();
        ListIterator<PomTransaction> iterator = blockedAspects.listIterator(blockedAspects.size());
        while (iterator.hasPrevious()) {
            PomTransaction transaction = iterator.previous();
            if (PsiTreeUtil.isAncestor(getContainingFileByTree(transaction.getChangeScope()), changeScope, false)) {
                return transaction;
            }
        }
        return null;
    }

    private void commitTransaction(@NonNull PsiFile containingFileByTree, @Nullable Document document) {
        final PsiDocumentManagerBase manager = (PsiDocumentManagerBase)PsiDocumentManager.getInstance(myProject);
        final PsiToDocumentSynchronizer synchronizer = manager.getSynchronizer();

        boolean isFromCommit = manager.isCommitInProgress();
        boolean isPhysicalPsiChange = !isFromCommit && !synchronizer.isIgnorePsiEvents();
        if (isPhysicalPsiChange) {
            reparseParallelTrees(containingFileByTree, synchronizer);
        }

        boolean docSynced = false;
        if (document != null) {
            final int oldLength = containingFileByTree.getTextLength();
            docSynced = synchronizer.commitTransaction(document);
            if (docSynced) {
                sendAfterChildrenChangedEvent(containingFileByTree, oldLength);
            }
        }

        if (isPhysicalPsiChange && docSynced) {
            containingFileByTree.getViewProvider().contentsSynchronized();
        }

    }

    private void reparseParallelTrees(PsiFile changedFile, PsiToDocumentSynchronizer synchronizer) {
        List<PsiFile> allFiles = changedFile.getViewProvider().getAllFiles();
        if (allFiles.size() > 1) {
            CharSequence newText = changedFile.getNode().getChars();

            for (PsiFile file : allFiles) {
                FileElement fileElement =
                        file == changedFile ? null : ((PsiFileImpl) file).getTreeElement();
                Runnable changeAction =
                        fileElement == null ? null : this.reparseFile(file, fileElement, newText);
                if (changeAction != null) {
                    synchronizer.setIgnorePsiEvents(true);

                    try {
                        CodeStyleManager.getInstance(file.getProject())
                                .performActionWithFormatterDisabled(changeAction);
                    } finally {
                        synchronizer.setIgnorePsiEvents(false);
                    }
                }
            }

        }
    }

    /**
     * Reparses the file and returns a runnable which actually changes the PSI structure to match the new text.
     */
    @Nullable
    public Runnable reparseFile(@NonNull PsiFile file, @NonNull FileElement treeElement, @NonNull CharSequence newText) {
        TextRange changedPsiRange = ChangedPsiRangeUtil.getChangedPsiRange(file, treeElement, newText);
        if (changedPsiRange == null) return null;

        ProgressIndicator indicator = EmptyProgressIndicator.notNullize(ProgressIndicatorProvider.getGlobalProgressIndicator());
        DiffLog log = BlockSupport.getInstance(myProject).reparseRange(file, treeElement, changedPsiRange, newText, indicator,
                treeElement.getText());
        return () -> runTransaction(new PomTransactionBase(file) {
            @Override
            public @NonNull PomModelEvent runInner() throws IncorrectOperationException {
                return new PomModelEvent(MockPomModel.this, log.performActualPsiChange(file));
            }
        });
    }

    private Document startTransaction(@NonNull PomTransaction transaction, @Nullable PsiFile psiFile) {
        final PsiDocumentManagerBase manager = (PsiDocumentManagerBase)PsiDocumentManager.getInstance(myProject);
        final PsiToDocumentSynchronizer synchronizer = manager.getSynchronizer();
        final PsiElement changeScope = transaction.getChangeScope();

        if (psiFile != null && !(psiFile instanceof DummyHolder) && !manager.isCommitInProgress()) {
            PsiUtilCore.ensureValid(psiFile);
        }

        boolean physical = changeScope.isPhysical();
        if (synchronizer.toProcessPsiEvent()) {
            // fail-fast to prevent any psi modifications that would cause psi/document text mismatch
            if (isDocumentUncommitted(psiFile)) {
                throw new IllegalStateException("Attempt to modify PSI for non-committed Document!");
            }
            CommandProcessor commandProcessor = CommandProcessor.getInstance();
            if (physical && !commandProcessor.isUndoTransparentActionInProgress() && commandProcessor.getCurrentCommand() == null) {
                throw new IncorrectOperationException("Must not change PSI outside command or undo-transparent action. See com.intellij.openapi.command.WriteCommandAction or com.intellij.openapi.command.CommandProcessor");
            }
        }

        VirtualFile vFile = psiFile == null ? null : psiFile.getViewProvider().getVirtualFile();
        if (psiFile != null) {
            ((SmartPointerManagerImpl) SmartPointerManager.getInstance(myProject)).fastenBelts(vFile);
            if (psiFile instanceof PsiFileImpl) {
                ((PsiFileImpl)psiFile).beforeAstChange();
            }
        }

        sendBeforeChildrenChangeEvent(changeScope);
        Document document = psiFile == null || psiFile instanceof DummyHolder ? null :
                physical || ModelBranch.getPsiBranch(psiFile) != null ? FileDocumentManager.getInstance().getDocument(vFile) :
                        FileDocumentManager.getInstance().getCachedDocument(vFile);
        if (document != null) {
            synchronizer.startTransaction(myProject, document, psiFile);
        }
        return document;
    }

    private boolean isDocumentUncommitted(@Nullable PsiFile file) {
        if (file == null) return false;

        PsiDocumentManager manager = PsiDocumentManager.getInstance(myProject);
        Document cachedDocument = manager.getCachedDocument(file);
        return cachedDocument != null && manager.isUncommited(cachedDocument);
    }

    @Nullable
    private static PsiFile getContainingFileByTree(@NonNull final PsiElement changeScope) {
        // there could be pseudo physical trees (JSPX/JSP/etc.) which must not translate
        // any changes to document and not to fire any PSI events
        final PsiFile psiFile;
        final ASTNode node = changeScope.getNode();
        if (node == null) {
            psiFile = changeScope.getContainingFile();
        }
        else {
            final FileASTNode fileElement = getFileElement(node);
            // assert fileElement != null : "Can't find file element for node: " + node;
            // Hack. the containing tree can be invalidated if updating supplementary trees like HTML in JSP.
            if (fileElement == null) return null;

            psiFile = (PsiFile)fileElement.getPsi();
        }
        return psiFile.getNode() != null ? psiFile : null;
    }

    private static FileASTNode getFileElement(@NonNull ASTNode element) {
        ASTNode parent = element;
        while (parent != null && !(parent instanceof FileASTNode)) {
            parent = parent.getTreeParent();
        }
        if (parent == null) {
            parent = element.getUserData(CONTAINING_FILE_KEY_AFTER_REPARSE);
        }
        return (FileASTNode)parent;
    }

    private static volatile boolean allowPsiModification = true;
    public static <T extends Throwable> void guardPsiModificationsIn(@NonNull ThrowableRunnable<T> runnable) throws T {
        ApplicationManager.getApplication().assertWriteAccessAllowed();
        boolean old = allowPsiModification;
        try {
            allowPsiModification = false;
            runnable.run();
        }
        finally {
            allowPsiModification = old;
        }
    }

    public static boolean isAllowPsiModification() {
        return allowPsiModification;
    }

    private void sendBeforeChildrenChangeEvent(@NonNull PsiElement scope) {
        if (!shouldFirePhysicalPsiEvents(scope)) {
            getPsiManager().beforeChange(false);
            return;
        }
        PsiTreeChangeEventImpl event = new PsiTreeChangeEventImpl(getPsiManager());
        event.setParent(scope);
        event.setFile(scope.getContainingFile());
        TextRange range = scope.getTextRange();
        event.setOffset(range == null ? 0 : range.getStartOffset());
        event.setOldLength(scope.getTextLength());
        // the "generic" event is being sent on every PSI change. It does not carry any specific info except the fact that "something has changed"
        event.setGenericChange(true);
        getPsiManager().beforeChildrenChange(event);
    }

    public static boolean shouldFirePhysicalPsiEvents(@NonNull PsiElement scope) {
        return scope.isPhysical() &&
               ModelBranch.getPsiBranch(scope) == null; // injections are physical even in non-physical PSI :(
    }

    private void sendAfterChildrenChangedEvent(@NonNull PsiFile scope, int oldLength) {
        if (!shouldFirePhysicalPsiEvents(scope)) {
            getPsiManager().afterChange(false);
            return;
        }
        PsiTreeChangeEventImpl event = new PsiTreeChangeEventImpl(getPsiManager());
        event.setParent(scope);
        event.setFile(scope);
        event.setOffset(0);
        event.setOldLength(oldLength);
        event.setGenericChange(true);
        getPsiManager().childrenChanged(event);
    }

    @NonNull
    private PsiManagerImpl getPsiManager() {
        return (PsiManagerImpl) PsiManager.getInstance(myProject);
    }
}
