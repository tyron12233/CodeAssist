package com.tyron.psi.lookup;

import com.tyron.psi.completion.InsertHandler;
import com.tyron.psi.completion.InsertionContext;
import com.tyron.psi.util.ClassConditionKey;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * @author peter
 */
public abstract class LookupElementDecorator<T extends LookupElement> extends LookupElement {
    private final T myDelegate;

    protected LookupElementDecorator(T delegate) {
        myDelegate = delegate;
        myDelegate.copyUserDataTo(this);
    }

    public T getDelegate() {
        return myDelegate;
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
    public void handleInsert(InsertionContext context) {
        myDelegate.handleInsert(context);
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

    @NotNull
    public static <T extends LookupElement> LookupElementDecorator<T> withInsertHandler(@NotNull T element, @NotNull final InsertHandler<? super LookupElementDecorator<T>> insertHandler) {
        return new InsertingDecorator<T>(element, insertHandler);
    }

    @NotNull
    public static <T extends LookupElement> LookupElementDecorator<T> withRenderer(@NotNull final T element, @NotNull final LookupElementRenderer<? super LookupElementDecorator<T>> visagiste) {
        return new VisagisteDecorator<T>(element, visagiste);
    }

    @Override
    public <T> T as(ClassConditionKey<T> conditionKey) {
        final T t = super.as(conditionKey);
        return t == null ? myDelegate.as(conditionKey) : t;
    }

    @Override
    public boolean isCaseSensitive() {
        return myDelegate.isCaseSensitive();
    }

    private static class InsertingDecorator<T extends LookupElement> extends LookupElementDecorator<T> {
        private final InsertHandler<? super LookupElementDecorator<T>> myInsertHandler;

        public InsertingDecorator(T element, InsertHandler<? super LookupElementDecorator<T>> insertHandler) {
            super(element);
            myInsertHandler = insertHandler;
        }

        @Override
        public void handleInsert(InsertionContext context) {
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

        public VisagisteDecorator(T element, LookupElementRenderer<? super LookupElementDecorator<T>> visagiste) {
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