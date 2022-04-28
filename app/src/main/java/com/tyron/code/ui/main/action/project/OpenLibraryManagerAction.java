package com.tyron.code.ui.main.action.project;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.code.R;
import com.tyron.code.ui.library.LibraryManagerFragment;

public class OpenLibraryManagerAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        Context context = event.getDataContext();

        presentation.setVisible(false);
        if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
            return;
        }
        Project project = event.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            return;
        }

        presentation.setText(context.getString(R.string.menu_library_manager));
        presentation.setVisible(true);
        presentation.setEnabled(true);
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Context context = e.getRequiredData(CommonDataKeys.CONTEXT);
        context = getActivityContext(context);

        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        Module mainModule = project.getMainModule();
        if (context instanceof AppCompatActivity) {
            FragmentManager fragmentManager =
                    ((AppCompatActivity) context).getSupportFragmentManager();
            Fragment fragment = LibraryManagerFragment.newInstance(mainModule.getRootFile().getAbsolutePath());
            fragmentManager.beginTransaction()
                    .add(R.id.fragment_container, fragment, LibraryManagerFragment.TAG)
                    .addToBackStack(LibraryManagerFragment.TAG)
                    .commit();
        }
    }

    private Context getActivityContext(Context context) {
        Context current = context;
        while (current != null) {
            if (current instanceof Activity) {
                return current;
            }
            if (current instanceof ContextWrapper) {
                current = ((ContextWrapper) current).getBaseContext();
            } else {
                current = null;
            }
        }
        return null;
    }
}
