package com.tyron.code.ui.editor.action;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.Presentation;
import com.tyron.code.R;
import com.tyron.code.ui.editor.api.FileEditor;
import com.tyron.code.ui.editor.impl.xml.LayoutEditor;
import com.tyron.code.ui.editor.impl.xml.LayoutTextEditorFragment;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.util.AndroidUtilities;

public class PreviewLayoutAction extends AnAction {

    public static final String ID = "previewLayoutAction";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            return;
        }

        FileEditor fileEditor = event.getData(MainFragment.FILE_EDITOR_KEY);
        if (!(fileEditor instanceof LayoutEditor)) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(R.string.menu_preview_layout));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        FileEditor fileEditor = e.getData(MainFragment.FILE_EDITOR_KEY);
        Fragment fragment = fileEditor.getFragment();
        if (fragment == null || fragment.isDetached()) {
            return;
        }
        FragmentActivity activity = fragment.requireActivity();
        View currentFocus = activity.getCurrentFocus();
        if (currentFocus == null) {
            currentFocus = new View(activity);
        }
        AndroidUtilities.hideKeyboard(currentFocus);

        if (fragment instanceof LayoutTextEditorFragment) {
            ((LayoutTextEditorFragment) fragment).preview();
        }
    }
}
