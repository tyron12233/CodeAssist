package com.tyron.actions;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.Supplier;

public abstract class AnAction {

    private Presentation mTemplatePresentation;

    public AnAction() {

    }

    public AnAction(Drawable icon) {
        this(Presentation.NULL_STRING, Presentation.NULL_STRING, icon);
    }

    public AnAction(@Nullable String text) {
        this(text, null, null);
    }

    public AnAction(@NonNull Supplier<String> dynamicText) {
        this(dynamicText, Presentation.NULL_STRING, null);
    }

    public AnAction(@Nullable String text,
                    @Nullable String description,
                    @Nullable Drawable icon) {
        this(() -> text, () -> description, icon);
    }

    public AnAction(@NonNull Supplier<String> dynamicText, @Nullable Drawable icon) {
        this(dynamicText, Presentation.NULL_STRING, icon);
    }

    public AnAction(@NonNull Supplier<String> dynamicText,
                    @NonNull Supplier<String> description,
                    @Nullable Drawable icon) {
        Presentation presentation = getTemplatePresentation();
        presentation.setText(dynamicText);
        presentation.setDescription(description);
        presentation.setIcon(icon);
    }

    public final Presentation getTemplatePresentation() {
        if (mTemplatePresentation == null) {
            mTemplatePresentation = createTemplatePresentation();
        }
        return mTemplatePresentation;
    }

    private Presentation createTemplatePresentation() {
        return Presentation.createTemplatePresentation();
    }

    public boolean displayTextInToolbar() {
        return false;
    }

    public boolean useSmallerFontForTextInToolbar() {
        return false;
    }

    public void update(@NonNull AnActionEvent event) {

    }

    public void beforeActionPerformedUpdate(@NonNull AnActionEvent e) {
        update(e);
    }

    public abstract void actionPerformed(@NonNull AnActionEvent e);

    public String getTemplateText() {
        return getTemplatePresentation().getText();
    }
}
