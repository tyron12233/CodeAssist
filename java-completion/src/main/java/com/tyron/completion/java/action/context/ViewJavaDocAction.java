package com.tyron.completion.java.action.context;

import android.app.AlertDialog;

import androidx.annotation.NonNull;

import com.tyron.completion.java.R;
import com.tyron.completion.java.action.api.ActionContext;
import com.tyron.completion.java.action.api.ActionProvider;
import com.tyron.completion.java.hover.HoverProvider;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.List;

public class ViewJavaDocAction extends ActionProvider {

    @Override
    public boolean isApplicable(ActionContext context, @NonNull TreePath currentPath) {
        return true;
    }

    @Override
    public void addMenus(@NonNull ActionContext context) {
        String title = context.getContext().getString(R.string.menu_action_view_javadoc_title);
        context.addMenu("context", title).setOnMenuItemClickListener(item -> {
            HoverProvider provider = new HoverProvider(context.getCompiler());
            List<String> hover = provider.hover(context.getCurrentFile(), context.getCursor());

            if (!hover.isEmpty()) {
                new AlertDialog.Builder(context.getContext())
                        .setTitle(title)
                        .setMessage(hover.get(0))
                        .setPositiveButton(R.string.menu_close, null)
                        .show();
            } else {
                new AlertDialog.Builder(context.getContext())
                        .setTitle(title)
                        .setMessage(R.string.menu_action_no_javadoc_message)
                        .setPositiveButton(R.string.menu_close, null)
                        .show();
            }
            return true;
        });
    }
}
