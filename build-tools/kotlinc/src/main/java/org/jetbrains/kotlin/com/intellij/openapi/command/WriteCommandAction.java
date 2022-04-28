package org.jetbrains.kotlin.com.intellij.openapi.command;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.jetbrains.kotlin.com.intellij.codeInsight.FileModificationService2;
import org.jetbrains.kotlin.com.intellij.core.CoreBundle;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.BaseActionRunnable2;
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState;
import org.jetbrains.kotlin.com.intellij.openapi.application.Result;
import org.jetbrains.kotlin.com.intellij.openapi.application.RunResult;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public abstract class WriteCommandAction<T> extends BaseActionRunnable2<T> {
    private static final Logger LOG = Logger.getInstance(WriteCommandAction.class);

    private static final String DEFAULT_GROUP_ID = null;

    public interface Builder {

        @NonNull
        Builder withName(@Nullable
                                 String name);


        @NonNull
        Builder withGroupId(@Nullable String groupId);


        @NonNull
        Builder withUndoConfirmationPolicy(@NonNull UndoConfirmationPolicy policy);


        @NonNull
        Builder withGlobalUndo();


        @NonNull
        Builder shouldRecordActionForActiveDocument(boolean value);

        <E extends Throwable> void run(@NonNull ThrowableRunnable<E> action) throws E;

        <R, E extends Throwable> R compute(@NonNull ThrowableComputable<R, E> action) throws E;
    }

    private static final class BuilderImpl implements Builder {
        private final Project myProject;
        private final PsiFile[] myPsiFiles;
        private
        String myCommandName = getDefaultCommandName();
        private String myGroupId = DEFAULT_GROUP_ID;
        private UndoConfirmationPolicy myUndoConfirmationPolicy;
        private boolean myGlobalUndoAction;
        private boolean myShouldRecordActionForActiveDocument = true;

        private BuilderImpl(Project project, PsiFile... files) {
            myProject = project;
            myPsiFiles = files;
        }

        @NonNull
        @Override
        public Builder withName(
                String name) {
            myCommandName = name;
            return this;
        }

        @NonNull
        @Override
        public Builder withGlobalUndo() {
            myGlobalUndoAction = true;
            return this;
        }

        @NonNull
        @Override
        public Builder shouldRecordActionForActiveDocument(boolean value) {
            myShouldRecordActionForActiveDocument = value;
            return this;
        }

        @NonNull
        @Override
        public Builder withUndoConfirmationPolicy(@NonNull UndoConfirmationPolicy policy) {
            if (myUndoConfirmationPolicy != null) throw new IllegalStateException("do not call withUndoConfirmationPolicy() several times");
            myUndoConfirmationPolicy = policy;
            return this;
        }

        @NonNull
        @Override
        public Builder withGroupId(String groupId) {
            myGroupId = groupId;
            return this;
        }

        @Override
        public <E extends Throwable> void run(@NonNull final ThrowableRunnable<E> action) throws E {
            Application application = ApplicationManager.getApplication();
            boolean dispatchThread = application.isDispatchThread();

            if (!dispatchThread && application.isReadAccessAllowed()) {
                LOG.error("Must not start write action from within read action in the other thread - deadlock is coming");
                throw new IllegalStateException();
            }

            AtomicReference<E> thrown = new AtomicReference<>();
            if (dispatchThread) {
                thrown.set(doRunWriteCommandAction(action));
            }
            else {
                try {
                    ApplicationManager.getApplication().invokeAndWait(() -> thrown.set(doRunWriteCommandAction(action)), ModalityState.defaultModalityState());
                }
                catch (ProcessCanceledException ignored) {
                }
            }
            if (thrown.get() != null) {
                throw thrown.get();
            }
        }

        private <E extends Throwable> E doRunWriteCommandAction(@NonNull ThrowableRunnable<E> action) {
            if (myPsiFiles.length > 0 && !FileModificationService2.getInstance().preparePsiElementsForWrite(myPsiFiles)) {
                return null;
            }

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Runnable wrappedRunnable = () -> {
                if (myGlobalUndoAction) {
                   // CommandProcessor.getInstance().markCurrentCommandAsGlobal(myProject);
                }
                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        action.run();
                    }
                    catch (Throwable e) {
                        thrown.set(e);
                    }
                });
            };
            CommandProcessor.getInstance().executeCommand(myProject, wrappedRunnable, myCommandName,
                    ObjectUtils.notNull(myUndoConfirmationPolicy, UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION));
            //noinspection unchecked
            return (E)thrown.get();
        }

        @Override
        public <R, E extends Throwable> R compute(@NonNull final ThrowableComputable<R, E> action) throws E {
            AtomicReference<R> result = new AtomicReference<>();
            run(() -> result.set(action.compute()));
            return result.get();
        }
    }

    @NonNull
    public static Builder writeCommandAction(Project project) {
        return new BuilderImpl(project);
    }

    @NonNull
    public static Builder writeCommandAction(@NonNull PsiFile first, PsiFile... others) {
        return new BuilderImpl(first.getProject(), ArrayUtil.prepend(first, others));
    }

    @NonNull
    public static Builder writeCommandAction(Project project, PsiFile... files) {
        return new BuilderImpl(project, files);
    }

    private final
    String myCommandName;
    private final String myGroupID;
    private final Project myProject;
    private final PsiFile[] myPsiFiles;

    /**
     * @deprecated Use {@link #writeCommandAction(Project, PsiFile...)}{@code .run()} instead
     */
    @Deprecated
    protected WriteCommandAction(@Nullable Project project, PsiFile... files) {
        this(project, getDefaultCommandName(), files);
    }

    /**
     * @deprecated Use {@link #writeCommandAction(Project, PsiFile...)}{@code .withName(commandName).run()} instead
     */
    @Deprecated
    protected WriteCommandAction(@Nullable Project project, @Nullable
            String commandName, PsiFile... files) {
        this(project, commandName, DEFAULT_GROUP_ID, files);
    }

    /**
     * @deprecated Use {@link #writeCommandAction(Project, PsiFile...)}{@code .withName(commandName).withGroupId(groupID).run()} instead
     */
    @Deprecated
    protected WriteCommandAction(@Nullable Project project,
                                 @Nullable
                                         String commandName,
                                 @Nullable String groupID,
                                 PsiFile... files) {
        myCommandName = commandName;
        myGroupID = groupID;
        myProject = project;
        myPsiFiles = files.length == 0 ? PsiFile.EMPTY_ARRAY : files;
    }

    public final Project getProject() {
        return myProject;
    }

    public final
    String getCommandName() {
        return myCommandName;
    }

    public String getGroupID() {
        return myGroupID;
    }

    /**
     * @deprecated Use {@code #writeCommandAction(Project).run()} or compute() instead
     */
    @Deprecated
    @NonNull
    @Override
    public RunResult<T> execute() {
        Application application = ApplicationManager.getApplication();
        boolean dispatchThread = application.isDispatchThread();

        if (!dispatchThread && application.isReadAccessAllowed()) {
            LOG.error("Must not start write action from within read action in the other thread - deadlock is coming");
            throw new IllegalStateException();
        }

        final RunResult<T> result = new RunResult<T>(this);
        if (dispatchThread) {
            performWriteCommandAction(result);
        }
        else {
            try {
                ApplicationManager.getApplication().invokeAndWait(() -> performWriteCommandAction(result), ModalityState.defaultModalityState());
            }
            catch (ProcessCanceledException ignored) {
            }
        }
        return result;
    }

    private void performWriteCommandAction(@NonNull RunResult<T> result) {
        if (myPsiFiles.length > 0 && !FileModificationService2.getInstance().preparePsiElementsForWrite(Arrays.asList(myPsiFiles))) {
            return;
        }

        // this is needed to prevent memory leak, since the command is put into undo queue
        Ref<RunResult<?>> resultRef = new Ref<>(result);
        doExecuteCommand(() -> ApplicationManager.getApplication().runWriteAction(() -> {
            resultRef.get().run();
            resultRef.set(null);
        }));
    }

    /**
     * @deprecated Use {@link #writeCommandAction(Project)}.withGlobalUndo() instead
     */
    @Deprecated
    protected boolean isGlobalUndoAction() {
        return false;
    }

    /**
     * See {@link CommandProcessor#executeCommand(Project, Runnable, String, Object, UndoConfirmationPolicy, boolean)} for details.
     *
     * @deprecated Use {@link #writeCommandAction(Project)}.withUndoConfirmationPolicy() instead
     */
    @Deprecated
    @NonNull
    protected UndoConfirmationPolicy getUndoConfirmationPolicy() {
        return UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION;
    }

    private void doExecuteCommand(@NonNull Runnable runnable) {
        Runnable wrappedRunnable = () -> {
           // if (isGlobalUndoAction()) CommandProcessor.getInstance().markCurrentCommandAsGlobal(getProject());
            runnable.run();
        };
        CommandProcessorEx.getInstance().executeCommand(getProject(), wrappedRunnable, getCommandName(),
                getUndoConfirmationPolicy());
    }

    /**
     * WriteCommandAction without result
     *
     * @deprecated Use {@link #writeCommandAction(Project)}.run() or .compute() instead
     */
    @Deprecated
    public abstract static class Simple<T> extends WriteCommandAction<T> {
        protected Simple(Project project, /*@NonNull*/ PsiFile... files) {
            super(project, files);
        }

        protected Simple(Project project,
                         String commandName, /*@NonNull*/ PsiFile... files) {
            super(project, commandName, files);
        }

        protected Simple(Project project,
                         String name, String groupID, /*@NonNull*/ PsiFile... files) {
            super(project, name, groupID, files);
        }

        @Override
        protected void run(@NonNull Result<T> result) throws Throwable {
            run();
        }

        protected abstract void run() throws Throwable;
    }

    /**
     * If run a write command using this method then "Undo" action always shows "Undefined" text.
     *
     * Please use {@link #runWriteCommandAction(Project, String, String, Runnable, PsiFile...)} instead.
     */
    @VisibleForTesting
    public static void runWriteCommandAction(Project project, @NonNull Runnable runnable) {
        runWriteCommandAction(project, getDefaultCommandName(), DEFAULT_GROUP_ID, runnable);
    }

    private static
    String getDefaultCommandName() {
        return CoreBundle.message("command.name.undefined");
    }

    public static void runWriteCommandAction(Project project,
                                             @Nullable
                                             final String commandName,
                                             @Nullable final String groupID,
                                             @NonNull final Runnable runnable,
                                             PsiFile... files) {
        writeCommandAction(project, files).withName(commandName).withGroupId(groupID).run(() -> runnable.run());
    }

    public static <T> T runWriteCommandAction(Project project, @NonNull final Computable<T> computable) {
        return writeCommandAction(project).compute(() -> computable.compute());
    }

    public static <T, E extends Throwable> T runWriteCommandAction(Project project, @NonNull final ThrowableComputable<T, E> computable)
            throws E {
        return writeCommandAction(project).compute(computable);
    }
}
