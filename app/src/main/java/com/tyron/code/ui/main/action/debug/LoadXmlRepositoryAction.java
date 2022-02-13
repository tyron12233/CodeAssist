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

        FolderConfiguration defaultConfig = FolderConfiguration.createDefault();
        FolderConfiguration specificConfig = FolderConfiguration.createDefault();
        specificConfig.setSmallestScreenWidthQualifier(SmallestScreenWidthQualifier.getQualifier("600"));
        specificConfig.setScreenOrientationQualifier(new ScreenOrientationQualifier(
                ScreenOrientation.LANDSCAPE));


        ImmutableList<Configurable> configurables = ImmutableList.<Configurable>builder()
                .add(new MockConfigurable("layout"))
                .add(new MockConfigurable("layout", "land"))
                .add(new MockConfigurable("layout", "sw600dp", "land"))
                .build();

        Configurable defaultMatch = defaultConfig.findMatchingConfigurable(configurables);
        Configurable specificMatch = specificConfig.findMatchingConfigurable(configurables);

        System.out.println(defaultMatch);
    }

    private static class MockConfigurable implements Configurable {
        private final FolderConfiguration mConfig;

        public MockConfigurable(FolderConfiguration configuration) {
            mConfig = configuration;
        }

        public MockConfigurable(String... qualifiers) {
            mConfig = FolderConfiguration.getConfig(qualifiers);
        }

        @NonNull
        @Override
        public FolderConfiguration getConfiguration() {
            return mConfig;
        }

        @NonNull
        @Override
        public String toString() {
            return mConfig.toString();
        }
    }
}
