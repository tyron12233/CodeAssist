package com.tyron.psi.completion;

import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupValueWithPsiElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.diagnostic.ThreadDumper;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Attachment;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.UnmodifiableIterator;

import java.util.ConcurrentModificationException;
import java.util.Iterator;

public class CompletionUtil {

    @Nullable
    public static PsiElement getTargetElement(LookupElement lookupElement) {
        PsiElement psiElement = lookupElement.getPsiElement();
        if (psiElement != null && psiElement.isValid()) {
            return getOriginalElement(psiElement);
        }

        Object object = lookupElement.getObject();
        if (object instanceof LookupValueWithPsiElement) {
            final PsiElement element = ((LookupValueWithPsiElement)object).getElement();
            if (element != null && element.isValid()) return getOriginalElement(element);
        }

        return null;
    }

    @Nullable
    public static <T extends PsiElement> T getOriginalElement(@NotNull T psi) {
        return CompletionUtilCoreImpl.getOriginalElement(psi);
    }
    @NotNull
    public static <T extends PsiElement> T getOriginalOrSelf(@NotNull T psi) {
        final T element = getOriginalElement(psi);
        return element == null ? psi : element;
    }


    public static Iterable<String> iterateLookupStrings(@NotNull final LookupElement element) {
        return new Iterable<String>() {
            @NotNull
            @Override
            public Iterator<String> iterator() {
                final Iterator<String> original = element.getAllLookupStrings().iterator();
                return new UnmodifiableIterator<String>(original) {
                    @Override
                    public boolean hasNext() {
                        try {
                            return super.hasNext();
                        }
                        catch (ConcurrentModificationException e) {
                            throw handleCME(e);
                        }
                    }

                    @Override
                    public String next() {
                        try {
                            return super.next();
                        }
                        catch (ConcurrentModificationException e) {
                            throw handleCME(e);
                        }
                    }

                    private RuntimeException handleCME(ConcurrentModificationException cme) {
                        RuntimeExceptionWithAttachments ewa = new RuntimeExceptionWithAttachments(
                                "Error while traversing lookup strings of " + element + " of " + element.getClass(),
                                (String)null,
                                new Attachment("threadDump.txt", ThreadDumper.dumpThreadsToString()));
                        ewa.initCause(cme);
                        return ewa;
                    }
                };
            }
        };
    }

}
