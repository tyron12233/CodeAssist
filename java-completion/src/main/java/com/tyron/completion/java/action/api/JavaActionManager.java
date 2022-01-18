package com.tyron.completion.java.action.api;

import android.content.Context;
import android.view.Menu;
import android.widget.Toast;

import com.tyron.completion.java.CompilerContainer;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.java.action.context.DiagnosticInfoAction;
import com.tyron.completion.java.action.context.IntroduceLocalVariableAction;
import com.tyron.completion.java.action.context.OverrideInheritedMethodsAction;
import com.tyron.completion.java.action.context.ViewJavaDocAction;
import com.tyron.completion.java.action.quickfix.ExceptionsQuickFix;
import com.tyron.completion.java.action.quickfix.ImplementAbstractMethodsFix;
import com.tyron.completion.java.action.quickfix.ImportClassFieldFix;
import com.tyron.completion.java.action.quickfix.ImportClassFix;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.completion.java.util.TreeUtil;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.util.TreePath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class JavaActionManager {

    private static volatile JavaActionManager sInstance;

    public static synchronized JavaActionManager getInstance() {
        if (sInstance == null) {
            sInstance = new JavaActionManager();
        }
        return sInstance;
    }

    private final List<ActionProvider> mActions;

    public JavaActionManager() {
        mActions = new ArrayList<>();

        registerBuiltinProviders();
    }

    private void registerBuiltinProviders() {
        registerActionProvider(new ViewJavaDocAction());
        registerActionProvider(new DiagnosticInfoAction());
        registerActionProvider(new ExceptionsQuickFix());
        registerActionProvider(new ImplementAbstractMethodsFix());
        registerActionProvider(new OverrideInheritedMethodsAction());
        registerActionProvider(new IntroduceLocalVariableAction());
        registerActionProvider(new ImportClassFix());
        registerActionProvider(new ImportClassFieldFix());
    }

    public void registerActionProvider(ActionProvider provider) {
        mActions.add(provider);
    }

    public synchronized void addActions(Context thisContext, Menu menu, JavaCompilerService service, Path file, int cursor, EditorInterface editor){
        if (!service.isReady()) {
            Toast.makeText(thisContext, "Compiler is busy", Toast.LENGTH_SHORT).show();
            return;
        }
        CompilerContainer container = service.compile(file);
        container.run(task -> {
            Diagnostic<? extends JavaFileObject> diagnostic = DiagnosticUtil.getDiagnostic(task,
                    cursor);
            TreePath currentPath = TreeUtil.findCurrentPath(task, cursor);
            ActionContext context = ActionContext.builder()
                    .setContext(thisContext)
                    .setMenu(menu)
                    .setCompileTask(task)
                    .setEditorInterface(editor)
                    .setCurrentPath(currentPath)
                    .setDiagnostic(diagnostic)
                    .setCurrentFile(file)
                    .setCompiler(service)
                    .setCursor(cursor)
                    .build();

            List<ActionProvider> applicableActions = getApplicableActions(context);
            for (ActionProvider actionProvider : applicableActions) {
                actionProvider.addMenus(context);
            }
        });
    }

    private List<ActionProvider> getApplicableActions(ActionContext context) {
        List<ActionProvider> actionProviders = new ArrayList<>();
        for (ActionProvider action : mActions) {
            if (context.getDiagnostic() != null) {
                if (action.isApplicable(context, context.getDiagnostic().getCode())) {
                    actionProviders.add(action);
                }
            }

            if (context.getCurrentPath() != null) {
                if (action.isApplicable(context, context.getCurrentPath())) {
                    actionProviders.add(action);
                }
            }
        }
        return actionProviders;
    }
}
