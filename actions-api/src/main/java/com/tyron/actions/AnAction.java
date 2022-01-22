package com.tyron.actions;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.Supplier;

/**
 * Represents a menu that can be performed.
 *
 * For an action to do something, override the {@link AnAction#actionPerformed(AnActionEvent)}
 * and optionally override {@link AnAction#update(AnActionEvent)}. By implementing the
 * {@link AnAction#update(AnActionEvent)} method, you can dynamically change the action's
 * presentation depending on the place. For more information on places see {@link ActionPlaces}
 *
 * <pre>
 *     public class MyAction extends AnAction {
 *         public MyAction() {
 *             // ...
 *         }
 *
 *         public void update(AnActionEvent e) {
 *             Presentation presentation = e.getPresentation();
 *             presentation.setVisible(true);
 *             presentation.setText(e.getPlace());
 *         }
 *
 *         public void actionPerformed(AnActionEvent e) {
 *             // do something when this action is pressed
 *         }
 *     }
 * </pre>
 *
 * This implementation is partially adopted from IntelliJ's Actions API.
 *
 * @see AnActionEvent
 * @see Presentation
 * @see ActionPlaces
 */
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

    /**
     * Updates the state of this action. Default implementation does nothing.
     * Override this method to provide the ability to dynamically change action's
     * state and(or) presentation depending on the context. (For example
     * when your action state depends on the selection you can check for the
     * selection and change the state accordingly.)
     *
     * This method can be called frequently and on UI thread.
     * This means that this method is supposed to work really fast,
     * no work should be done at this phase. For example checking values such as editor
     * selection is fine but working with the file system such as reading/writing to a file is not.
     * If the action state cannot be determined, do the checks in
     * {@link #actionPerformed(AnActionEvent)} and inform the user if the action cannot
     * be performed by possibly showing a dialog.
     *
     * @param event Carries information on the invocation place and data available
     */
    public void update(@NonNull AnActionEvent event) {

    }

    /**
     * Implement this method to handle when this action has been clicked or pressed.
     *
     * @param e Carries information on the invocation place and data available.
     */
    public abstract void actionPerformed(@NonNull AnActionEvent e);


    public void beforeActionPerformedUpdate(@NonNull AnActionEvent e) {
        update(e);
    }

    public String getTemplateText() {
        return getTemplatePresentation().getText();
    }
}
