package com.tyron.lookup;

import com.tyron.completions.CompletionResultSet;
import com.tyron.completions.InsertHandler;
import com.tyron.completions.InsertionContext;
import com.tyron.completions.PrioritizedLookupElement;
import com.tyron.util.ClassConditionKey;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

import java.util.Set;

/**
 * A decorator for {@link LookupElement}s, allowing to override various aspects of their behavior.
 * Decorated lookup elements can be unwrapped with {@link LookupElement#as(Class)} method if needed.
 * Please decorate only when necessary, e.g. when intercepting other contributors
 * ({@link CompletionResultSet#runRemainingContributors}).
 * There's usually no point in doing so when you create them yourself in the same place of code.
 * @author peter
 *
 * @see PrioritizedLookupElement
 */
public abstract class LookupElementDecorator<T extends LookupElement> extends LookupElement {
    private final @NotNull T myDelegate;

    protected LookupElementDecorator(@NotNull T delegate) {
        myDelegate = delegate;
        myDelegate.copyUserDataTo(this);
    }

    public @NotNull T getDelegate() {
        return myDelegate;
    }

    @Override
    public boolean isValid() {
        return myDelegate.isValid() && super.isValid();
    }

    @Override
    @NotNull
    public String getLookupString() {
        return myDelegate.getLookupString();
    }

    @Override
    public Set<String> getAllLookupStrings() {
        return myDelegate.getAllLookupStrings();
    }

    @NotNull
    @Override
    public Object getObject() {
        return myDelegate.getObject();
    }

    @Override
    public void handleInsert(@NotNull InsertionContext context) {
        myDelegate.handleInsert(context);
    }

    @Override
    public AutoCompletionPolicy getAutoCompletionPolicy() {
        return myDelegate.getAutoCompletionPolicy();
    }

    @Override
    public String toString() {
        return myDelegate.toString();
    }

    @Override
    public void renderElement(LookupElementPresentation presentation) {
        myDelegate.renderElement(presentation);
    }

    @Override
    public LookupElementRenderer<? extends LookupElement> getExpensiveRenderer() {
        //noinspection rawtypes
        LookupElementRenderer renderer = myDelegate.getExpensiveRenderer();
        return renderer == null ? null : new LookupElementRenderer<LookupElementDecorator<?>>() {
            @Override
            public void renderElement(LookupElementDecorator<?> element, LookupElementPresentation presentation) {
                //noinspection unchecked
                renderer.renderElement(element.myDelegate, presentation);
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LookupElementDecorator that = (LookupElementDecorator)o;

        if (!myDelegate.equals(that.myDelegate)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return myDelegate.hashCode();
    }

    /**
     * Wraps the given lookup element into a decorator that overrides its insertion behavior.
     */
    @NotNull
    public static <T extends LookupElement> LookupElementDecorator<T> withInsertHandler(@NotNull T element, @NotNull final InsertHandler<? super LookupElementDecorator<T>> insertHandler) {
        return new InsertingDecorator<>(element, insertHandler);
    }

    @NotNull
    public static <T extends LookupElement> LookupElementDecorator<T> withRenderer(@NotNull final T element, @NotNull final LookupElementRenderer<? super LookupElementDecorator<T>> visagiste) {
        return new VisagisteDecorator<>(element, visagiste);
    }

    @Override
    public <T> T as(ClassConditionKey<T> conditionKey) {
        final T t = super.as(conditionKey);
        return t == null ? myDelegate.as(conditionKey) : t;
    }

    @Override
    public <T> T as(Class<T> clazz) {
        final T t = super.as(clazz);
        return t == null ? myDelegate.as(clazz) : t;
    }

    @Override
    public boolean isCaseSensitive() {
        return myDelegate.isCaseSensitive();
    }

    @Override
    public boolean isWorthShowingInAutoPopup() {
        return myDelegate.isWorthShowingInAutoPopup();
    }

    @Nullable
    @Override
    public PsiElement getPsiElement() {
        return myDelegate.getPsiElement();
    }

    private static class InsertingDecorator<T extends LookupElement> extends LookupElementDecorator<T> {
        private final InsertHandler<? super LookupElementDecorator<T>> myInsertHandler;

        InsertingDecorator(T element, InsertHandler<? super LookupElementDecorator<T>> insertHandler) {
            super(element);
            myInsertHandler = insertHandler;
        }

        @Override
        public void handleInsert(@NotNull InsertionContext context) {
            myInsertHandler.handleInsert(context, this);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            InsertingDecorator that = (InsertingDecorator)o;

            if (!myInsertHandler.equals(that.myInsertHandler)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + myInsertHandler.hashCode();
            return result;
        }
    }

    private static class VisagisteDecorator<T extends LookupElement> extends LookupElementDecorator<T> {
        private final LookupElementRenderer<? super LookupElementDecorator<T>> myVisagiste;

        VisagisteDecorator(T element, LookupElementRenderer<? super LookupElementDecorator<T>> visagiste) {
            super(element);
            myVisagiste = visagiste;
        }

        @Override
        public void renderElement(final LookupElementPresentation presentation) {
            myVisagiste.renderElement(this, presentation);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            VisagisteDecorator that = (VisagisteDecorator)o;

            if (!myVisagiste.getClass().equals(that.myVisagiste.getClass())) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + myVisagiste.getClass().hashCode();
            return result;
        }
    }
}
