package com.tyron.completion.java.action.api;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.java.action.quickfix.ExceptionsQuickFix;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.completion.java.util.TreeUtil;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.util.TreePath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CodeActionManager {

    private static volatile CodeActionManager sInstance;

    public static synchronized CodeActionManager getInstance() {
        if (sInstance == null) {
            sInstance = new CodeActionManager();
        }
        return sInstance;
    }

    private final List<ActionProvider> mActions;

    public CodeActionManager() {
        mActions = new ArrayList<>();

        registerBuiltinProviders();
    }

    private void registerBuiltinProviders() {
        registerActionProvider(new ExceptionsQuickFix());
    }

    public void registerActionProvider(ActionProvider provider) {
        mActions.add(provider);
    }

    public List<Action> getActions(JavaCompilerService service, Path file, int cursor) {
        try (CompileTask task = service.compile(file)) {
            Diagnostic<? extends JavaFileObject> diagnostic = DiagnosticUtil.getDiagnostic(task,
                    cursor);
            TreePath currentPath = TreeUtil.findCurrentPath(task, cursor);
            ActionContext context = ActionContext.builder()
                    .setCompileTask(task)
                    .setCurrentPath(currentPath)
                    .setDiagnostic(diagnostic)
                    .setCurrentFile(file)
                    .setCursor(cursor)
                    .build();

            List<ActionProvider> applicableActions = getApplicableActions(context);
            List<Action> actions = new ArrayList<>();
            for (ActionProvider actionProvider : applicableActions) {
                List<Action> action = actionProvider.getAction(context);
                if (action != null) {
                    actions.addAll(action);
                }
            }
            return actions;
        }
    }

    private List<ActionProvider> getApplicableActions(ActionContext context) {
        List<ActionProvider> actionProviders = new ArrayList<>();
        for (ActionProvider action : mActions) {
            if (context.getDiagnostic() != null) {
                if (action.isApplicable(context.getDiagnostic().getCode())) {
                    actionProviders.add(action);
                }
            }

            if (context.getCurrentPath() != null) {
                if (action.isApplicable(context.getCurrentPath())) {
                    actionProviders.add(action);
                }
            }
        }
        return actionProviders;
    }
}
