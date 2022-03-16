package com.tyron.code.ui.editor.action;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.google.common.collect.Range;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.Presentation;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.compiler.Parser;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;
import com.tyron.editor.selection.ExpandSelectionProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.io.File;
import java.time.Instant;

public class ExpandSelectionAction extends AnAction {

    public static final String ID = "expandSelection";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        File file = event.getData(CommonDataKeys.FILE);
        if (file == null) {
            return;
        }

        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        ExpandSelectionProvider provider = ExpandSelectionProvider.forEditor(editor);
        if (provider == null) {
            return;
        }

        if (editor.getProject() == null) {
            return;
        }

        DataContext context = event.getDataContext();
        presentation.setVisible(true);
        presentation.setText(context.getString(R.string.expand_selection));
        presentation.setIcon(ResourcesCompat.getDrawable(context.getResources(),
                R.drawable.ic_baseline_code_24, null));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        ExpandSelectionProvider provider = ExpandSelectionProvider.forEditor(editor);
        if (provider == null) {
            AndroidUtilities.showSimpleAlert(e.getDataContext(), "No provider",
                                             "No expand selection provider found.");
            return;
        }
        Range<Integer> range = provider.expandSelection(editor);
        if (range == null) {
            AndroidUtilities.showSimpleAlert(e.getDataContext(),
                                             "Error",
                                             "Cannot expand selection");
            return;
        }
        editor.setSelectionRegion(range.lowerEndpoint(), range.upperEndpoint());
    }
}
