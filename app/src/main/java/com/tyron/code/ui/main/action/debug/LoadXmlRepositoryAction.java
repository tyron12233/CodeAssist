package com.tyron.code.ui.main.action.debug;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableList;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.builder.compiler.manifest.configuration.Configurable;
import com.tyron.builder.compiler.manifest.configuration.FolderConfiguration;
import com.tyron.builder.compiler.manifest.configuration.LocaleQualifier;
import com.tyron.builder.compiler.manifest.configuration.ScreenOrientationQualifier;
import com.tyron.builder.compiler.manifest.configuration.SmallestScreenWidthQualifier;
import com.tyron.builder.compiler.manifest.resources.ScreenOrientation;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.xml.repository.ResourceRepository;

import java.io.IOException;

public class LoadXmlRepositoryAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(true);
        presentation.setText("Load xml repository");
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        Module mainModule = project.getMainModule();
        ResourceRepository repository = new ResourceRepository((AndroidModule) mainModule);
        try {
            repository.initialize();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }
}
