package com.tyron.code.ui.main.action.debug;

import androidx.annotation.NonNull;

import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.comp.Modules;
import com.sun.tools.javac.util.Context;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.Presentation;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.completion.java.parse.CompilationInfo;

import java.io.IOException;
import java.net.URI;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

public class InjectClassesAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(true);
        presentation.setText("Inject light classes");
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Project project = ProjectManager.getInstance().getCurrentProject();
        Module mainModule = project.getMainModule();
        CompilationInfo compilationInfo = mainModule.getUserData(CompilationInfo.COMPILATION_INFO_KEY);
        if (compilationInfo == null) {
            return;
        }

        compilationInfo.update(new SimpleJavaFileObject(URI.create("Test.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return "package com.tyron.test; public class R { public static class layout { public static final int main = 0; } }";
            }
        });

        JavacTaskImpl javacTask = compilationInfo.impl.getJavacTask();
        Context context = javacTask.getContext();

        Modules modules = Modules.instance(context);
        Symbol.ModuleSymbol defaultModule = modules.getDefaultModule();
        System.out.println();
    }
}
