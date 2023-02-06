package com.tyron.completion.lookup.impl;

import androidx.annotation.Nullable;

import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementPresentation;
import com.tyron.completion.lookup.LookupElementRenderer;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.meta.PsiMetaData;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;

public class DefaultLookupItemRenderer extends LookupElementRenderer<LookupItem<?>> {

    public static final DefaultLookupItemRenderer INSTANCE = new DefaultLookupItemRenderer();

    @Override
    public void renderElement(LookupItem<?> item, LookupElementPresentation presentation) {
//        presentation.setIcon(getRawIcon(item));

        presentation.setItemText(getName(item));
        presentation.setItemTextBold(item.getAttribute(LookupItem.HIGHLIGHTED_ATTR) != null);
        presentation.setTailText(getText2(item), item.getAttribute(LookupItem.TAIL_TEXT_SMALL_ATTR) != null);
        presentation.setTypeText(getText3(item), null);
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static String getText3(LookupItem<?> item) {
        Object o = item.getObject();
        String text;
//        if (o instanceof LookupValueWithUIHint) {
//            text = ((LookupValueWithUIHint)o).getTypeHint();
//        }
//        el?s?e {
            text = (String)item.getAttribute(LookupItem.TYPE_TEXT_ATTR);
//        }?
        return text;
    }

    private static String getText2(LookupItem<?> item) {
        return (String)item.getAttribute(LookupItem.TAIL_TEXT_ATTR);
    }

    @SuppressWarnings("deprecation")
    private static String getName(LookupItem<?> item){
        final String presentableText = item.getPresentableText();
        if (presentableText != null) return presentableText;
        final Object o = item.getObject();
        String name = null;
        if (o instanceof PsiElement) {
            final PsiElement element = (PsiElement)o;
            if (element.isValid()) {
                name = PsiUtilCore.getName(element);
            }
        }
        else if (o instanceof PsiMetaData) {
            name = ((PsiMetaData)o).getName();
        }
//        else if (o instanceof PresentableLookupValue ) {
//            name = ((PresentableLookupValue)o).getPresentation();
//        }
        else {
            name = String.valueOf(o);
        }
        if (name == null){
            name = "";
        }

        return name;
    }
}
