package com.tyron.psi.lookup;


import com.tyron.psi.completion.InsertHandler;
import com.tyron.psi.completion.InsertionContext;
import com.tyron.psi.tailtype.TailType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;

/**
 * Consider using {@link InsertHandler} instead
 * @author peter
 */
public abstract class TailTypeDecorator<T extends LookupElement> extends LookupElementDecorator<T> {
    public TailTypeDecorator(T delegate) {
        super(delegate);
    }

    public static <T extends LookupElement> TailTypeDecorator<T> withTail(T element, final TailType type) {
        return new TailTypeDecorator<T>(element) {
            @Override
            protected TailType computeTailType(InsertionContext context) {
                return type;
            }
        };
    }

    @Nullable
    protected abstract TailType computeTailType(InsertionContext context);

    @Override
    public void handleInsert(@NotNull InsertionContext context) {
        TailType tailType = computeTailType(context);

        getDelegate().handleInsert(context);
        if (tailType != null && tailType.isApplicable(context)) {
            PsiDocumentManager.getInstance(context.getProject()).doPostponedOperationsAndUnblockDocument(context.getDocument());
            int tailOffset = context.getTailOffset();
            if (tailOffset < 0) {
                throw new AssertionError("tailOffset < 0: delegate=" + getDelegate() + "; this=" + this + "; tail=" + tailType);
            }
            tailType.processTail(context.getEditor(), tailOffset);
        }
    }

}
