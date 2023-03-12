package com.tyron.completion;

import com.tyron.completion.impl.CompletionAssertions;
import com.tyron.completion.impl.CompletionServiceImpl;
import com.tyron.completion.impl.OffsetsInFile;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.editor.Editor;
import com.tyron.legacyEditor.Caret;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;

import io.github.rosemoe.sora.text.Cursor;

@SuppressWarnings("deprecation")
public class CodeCompletionHandlerBase {

    private static final Logger LOG = Logger.getInstance(CodeCompletionHandlerBase.class);
    private static final Key<Boolean> CARET_PROCESSED = Key.create("CodeCompletionHandlerBase.caretProcessed");

    /**
     * If this key is set for a lookup element, the framework will only call handleInsert() on the lookup element when it is selected,
     * and will not perform any additional processing such as multi-caret handling or insertion of completion character.
     */
    public static final Key<Boolean> DIRECT_INSERTION = Key.create("CodeCompletionHandlerBase.directInsertion");

    @NotNull
    final CompletionType completionType;
    final boolean invokedExplicitly;
    final boolean synchronous;
    final boolean autopopup;
//    private static int ourAutoInsertItemTimeout = Registry.intValue("ide.completion.auto.insert.item.timeout", 2000);

    public static CodeCompletionHandlerBase createHandler(@NotNull CompletionType completionType) {
        return createHandler(completionType, true, false, true);
    }

    public static CodeCompletionHandlerBase createHandler(@NotNull CompletionType completionType, boolean invokedExplicitly, boolean autopopup, boolean synchronous) {
       return new CodeCompletionHandlerBase(completionType, invokedExplicitly, autopopup, synchronous);
    }

    public CodeCompletionHandlerBase(@NotNull CompletionType completionType) {
        this(completionType, true, false, true);
    }

    public CodeCompletionHandlerBase(@NotNull CompletionType completionType, boolean invokedExplicitly, boolean autopopup, boolean synchronous) {
        this.completionType = completionType;
        this.invokedExplicitly = invokedExplicitly;
        this.autopopup = autopopup;
        this.synchronous = synchronous;

        if (autopopup) {
            assert !invokedExplicitly;
        }
    }

    public void handleCompletionElementSelected(@NotNull LookupElement item,
                                                char completionChar, OffsetMap offsetMap, OffsetsInFile hostOffsets, Editor editor, Integer initialOffset) {

    }

    public final void invokeCompletion(final Project project, final Editor editor) {
        invokeCompletion(project, editor, 1);
    }

    public final void invokeCompletion(@NotNull final Project project, @NotNull final Editor editor, int time) {
        invokeCompletion(project, editor, time, false);
    }

    public final void invokeCompletion(@NotNull Project project, @NotNull Editor editor, int time, boolean hasModifiers) {
//        clearCaretMarkers(editor);
        invokeCompletion(project, editor, time, hasModifiers, editor.getCaret());
    }

    private void invokeCompletion(@NotNull Project project, @NotNull Editor editor, int time, boolean hasModifiers, @NotNull Caret cursor) {
//        markCaretAsProcessed(caret);

        if (invokedExplicitly) {
//            StatisticsUpdate.applyLastCompletionStatisticsUpdate();
        }

//        checkNoWriteAccess();

        CompletionAssertions.checkEditorValid(editor);

        int offset = editor.getCaret().getStart();
//        if (editor.isViewer() || editor.getDocument().getRangeGuard(offset, offset) != null) {
//            editor.getDocument().fireReadOnlyModificationAttempt();
//            EditorModificationUtil.checkModificationAllowed(editor);
//            return;
//        }

//        if (!FileDocumentManager.getInstance().requestWriting(editor.getDocument(), project)) {
//            return;
//        }

//        CompletionPhase phase = CompletionServiceImpl.getCompletionPhase();
    }
}
