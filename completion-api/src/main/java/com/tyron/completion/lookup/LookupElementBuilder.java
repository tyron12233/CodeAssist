package com.tyron.completion.lookup;

import android.graphics.Color;
import android.graphics.drawable.Icon;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.InsertHandler;
import com.tyron.completion.InsertionContext;

import org.jetbrains.annotations.Contract;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiNamedElement;
import org.jetbrains.kotlin.com.intellij.psi.SmartPointerManager;
import org.jetbrains.kotlin.com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LookupElementBuilder extends LookupElement {

    @NonNull
    private final String myLookupString;
    @NonNull private final Object myObject;
    @Nullable private final SmartPsiElementPointer<?> myPsiElement;
    private final boolean myCaseSensitive;
    @Nullable private final InsertHandler<LookupElement> myInsertHandler;
    @Nullable private final LookupElementRenderer<LookupElement> myRenderer;
    @Nullable private final LookupElementRenderer<LookupElement> myExpensiveRenderer;
    @Nullable
    private final LookupElementPresentation myHardcodedPresentation;
    @NonNull private final Set<String> myAllLookupStrings;

    private LookupElementBuilder(@NonNull String lookupString, @NonNull Object object, @Nullable InsertHandler<LookupElement> insertHandler,
                                 @Nullable LookupElementRenderer<LookupElement> renderer,
                                 @Nullable LookupElementRenderer<LookupElement> expensiveRenderer,
                                 @Nullable LookupElementPresentation hardcodedPresentation,
                                 @Nullable SmartPsiElementPointer<?> psiElement,
                                 @NonNull Set<String> allLookupStrings,
                                 boolean caseSensitive) {
        myLookupString = validate(lookupString);
        myObject = object;
        myInsertHandler = insertHandler;
        myRenderer = renderer;
        myExpensiveRenderer = expensiveRenderer;
        myHardcodedPresentation = hardcodedPresentation;
        myPsiElement = psiElement;
        myAllLookupStrings = Collections.unmodifiableSet(allLookupStrings);
        myCaseSensitive = caseSensitive;
    }

    private String validate(String string) {
        StringUtil.assertValidSeparators(string);
        return string;
    }

    private LookupElementBuilder(@NonNull String lookupString, @NonNull Object object) {
        this(lookupString, object, null, null, null, null, null, Collections.singleton(lookupString), true);
    }

    public static @NonNull LookupElementBuilder create(@NonNull String lookupString) {
        return new LookupElementBuilder(lookupString, lookupString);
    }

    public static @NonNull LookupElementBuilder create(@NonNull Object object) {
        return new LookupElementBuilder(object.toString(), object);
    }

    public static @NonNull LookupElementBuilder createWithSmartPointer(@NonNull String lookupString, @NonNull PsiElement element) {
        PsiUtilCore.ensureValid(element);
        return new LookupElementBuilder(lookupString,
                SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element));
    }

    public static @NonNull LookupElementBuilder create(@NonNull PsiNamedElement element) {
        PsiUtilCore.ensureValid(element);
        return new LookupElementBuilder(StringUtil.notNullize(element.getName()), element);
    }

    public static @NonNull LookupElementBuilder createWithIcon(@NonNull PsiNamedElement element) {
//        PsiUtilCore.ensureValid(element);
//        return create(element).withIcon(element.getIcon(0));
        throw new UnsupportedOperationException();
    }

    public static @NonNull LookupElementBuilder create(@NonNull Object lookupObject, @NonNull String lookupString) {
        if (lookupObject instanceof PsiElement) {
            PsiUtilCore.ensureValid((PsiElement)lookupObject);
        }
        return new LookupElementBuilder(lookupString, lookupObject);
    }

    private @NonNull LookupElementBuilder cloneWithUserData(@NonNull String lookupString, @NonNull Object object,
                                                            @Nullable InsertHandler<LookupElement> insertHandler,
                                                            @Nullable LookupElementRenderer<LookupElement> renderer,
                                                            @Nullable LookupElementRenderer<LookupElement> expensiveRenderer,
                                                            @Nullable LookupElementPresentation hardcodedPresentation,
                                                            @Nullable SmartPsiElementPointer<?> psiElement,
                                                            @NonNull Set<String> allLookupStrings,
                                                            boolean caseSensitive) {
        LookupElementBuilder result = new LookupElementBuilder(lookupString, object, insertHandler, renderer, expensiveRenderer,
                hardcodedPresentation, psiElement, allLookupStrings, caseSensitive);
//        copyUserDataTo(result);
        return result;
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withInsertHandler(@Nullable InsertHandler<LookupElement> insertHandler) {
        return cloneWithUserData(myLookupString, myObject, insertHandler, myRenderer, myExpensiveRenderer, myHardcodedPresentation,
                myPsiElement, myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withRenderer(@Nullable LookupElementRenderer<LookupElement> renderer) {
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, renderer, myExpensiveRenderer, myHardcodedPresentation,
                myPsiElement, myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withExpensiveRenderer(@Nullable LookupElementRenderer<LookupElement> expensiveRenderer) {
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, myRenderer, expensiveRenderer, myHardcodedPresentation,
                myPsiElement, myAllLookupStrings, myCaseSensitive);
    }

    @Override
    @NonNull
    public Set<String> getAllLookupStrings() {
        return myAllLookupStrings;
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withIcon(@Nullable Icon icon) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setIcon(icon);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation, myPsiElement,
                myAllLookupStrings, myCaseSensitive);
    }

    @NonNull
    private LookupElementPresentation copyPresentation() {
        final LookupElementPresentation presentation = new LookupElementPresentation();
        if (myHardcodedPresentation != null) {
            presentation.copyFrom(myHardcodedPresentation);
        } else {
            presentation.setItemText(myLookupString);
        }
        return presentation;
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withLookupString(@NonNull String another) {
        final Set<String> set = new HashSet<>(myAllLookupStrings);
        set.add(another);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, myRenderer, myExpensiveRenderer, myHardcodedPresentation,
                myPsiElement, Collections.unmodifiableSet(set), myCaseSensitive);
    }
    @Contract(pure=true)
    public @NonNull LookupElementBuilder withLookupStrings(@NonNull Collection<String> another) {
        Set<String> set = new HashSet<>(myAllLookupStrings.size() + another.size());
        set.addAll(myAllLookupStrings);
        set.addAll(another);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, myRenderer, myExpensiveRenderer, myHardcodedPresentation,
                myPsiElement, Collections.unmodifiableSet(set), myCaseSensitive);
    }

    @Override
    public boolean isCaseSensitive() {
        return myCaseSensitive;
    }

    /**
     * @param caseSensitive if this lookup item should be completed in the same letter case as prefix
     * @return modified builder
     */
    @Contract(pure=true)
    public @NonNull LookupElementBuilder withCaseSensitivity(boolean caseSensitive) {
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, myRenderer, myExpensiveRenderer, myHardcodedPresentation,
                myPsiElement, myAllLookupStrings, caseSensitive);
    }

    /**
     * Allows to pass custom PSI that will be returned from {@link #getPsiElement()}.
     */
    @Contract(pure=true)
    public @NonNull LookupElementBuilder withPsiElement(@Nullable PsiElement psi) {
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, myRenderer, myExpensiveRenderer, myHardcodedPresentation,
                psi == null ? null : SmartPointerManager.createPointer(psi),
                myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withItemTextForeground(@NonNull Color itemTextForeground) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setItemTextForeground(itemTextForeground);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation,
                myPsiElement, myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withItemTextUnderlined(boolean underlined) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setItemTextUnderlined(underlined);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation,
                myPsiElement, myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withItemTextItalic(boolean italic) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setItemTextItalic(italic);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation,
                myPsiElement, myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withTypeText(@Nullable String typeText) {
        return withTypeText(typeText, false);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withTypeText(@Nullable String typeText, boolean grayed) {
        return withTypeText(typeText, null, grayed);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withTypeText(@Nullable String typeText, @Nullable Icon typeIcon, boolean grayed) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setTypeText(typeText, typeIcon);
        presentation.setTypeGrayed(grayed);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation, myPsiElement,
                myAllLookupStrings, myCaseSensitive);
    }

    public @NonNull LookupElementBuilder withTypeIconRightAligned(boolean typeIconRightAligned) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setTypeIconRightAligned(typeIconRightAligned);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation, myPsiElement,
                myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withPresentableText(@NonNull String presentableText) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setItemText(presentableText);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation, myPsiElement,
                myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder bold() {
        return withBoldness(true);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withBoldness(boolean bold) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setItemTextBold(bold);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation, myPsiElement,
                myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder strikeout() {
        return withStrikeoutness(true);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withStrikeoutness(boolean strikeout) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setStrikeout(strikeout);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation, myPsiElement,
                myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withTailText(@Nullable String tailText) {
        return withTailText(tailText, false);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder withTailText(@Nullable String tailText, boolean grayed) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.setTailText(tailText, grayed);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation, myPsiElement,
                myAllLookupStrings, myCaseSensitive);
    }

    @Contract(pure=true)
    public @NonNull LookupElementBuilder appendTailText(@NonNull String tailText, boolean grayed) {
        final LookupElementPresentation presentation = copyPresentation();
        presentation.appendTailText(tailText, grayed);
        return cloneWithUserData(myLookupString, myObject, myInsertHandler, null, myExpensiveRenderer, presentation, myPsiElement,
                myAllLookupStrings, myCaseSensitive);
    }

//    @Contract(pure=true)
//    public LookupElement withAutoCompletionPolicy(AutoCompletionPolicy policy) {
//        return policy.applyPolicy(this);
//    }

    @NonNull
    @Override
    public String getLookupString() {
        return myLookupString;
    }

    @Nullable
    public InsertHandler<LookupElement> getInsertHandler() {
        return myInsertHandler;
    }

    @Nullable
    public LookupElementRenderer<LookupElement> getRenderer() {
        return myRenderer;
    }

    @Override
    public @Nullable LookupElementRenderer<? extends LookupElement> getExpensiveRenderer() {
        return myExpensiveRenderer;
    }

    @NonNull
    @Override
    public Object getObject() {
        return myObject;
    }

    @Nullable
    @Override
    public PsiElement getPsiElement() {
        if (myPsiElement != null) return myPsiElement.getElement();
        return super.getPsiElement();
    }

    @Override
    public void handleInsert(@NonNull InsertionContext context) {
        if (myInsertHandler != null) {
            myInsertHandler.handleInsert(context, this);
        }
    }

    @Override
    public void renderElement(@NonNull LookupElementPresentation presentation) {
        if (myRenderer != null) {
            myRenderer.renderElement(this, presentation);
        }
        else if (myHardcodedPresentation != null) {
            presentation.copyFrom(myHardcodedPresentation);
        }
        else {
            presentation.setItemText(myLookupString);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LookupElementBuilder that = (LookupElementBuilder)o;

        final InsertHandler<LookupElement> insertHandler = that.myInsertHandler;
        if (myInsertHandler != null && insertHandler != null ? !myInsertHandler.getClass().equals(insertHandler.getClass())
                : myInsertHandler != insertHandler) return false;
        if (!myLookupString.equals(that.myLookupString)) return false;
        if (!myObject.equals(that.myObject)) return false;

        final LookupElementRenderer<LookupElement> renderer = that.myRenderer;
        if (myRenderer != null && renderer != null ? !myRenderer.getClass().equals(renderer.getClass()) : myRenderer != renderer) return false;

        return true;
    }

    @Override
    public String toString() {
        return "LookupElementBuilder: string=" + getLookupString() + "; handler=" + myInsertHandler;
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + (myInsertHandler != null ? myInsertHandler.getClass().hashCode() : 0);
        result = 31 * result + (myLookupString.hashCode());
        result = 31 * result + (myObject.hashCode());
        result = 31 * result + (myRenderer != null ? myRenderer.getClass().hashCode() : 0);
        return result;
    }
}
