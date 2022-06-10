package org.gradle.android;

import com.google.common.collect.ImmutableList;
import org.gradle.android.aapt2.Aapt2Task;
import org.gradle.api.Action;
import org.gradle.api.BuildProject;

import java.io.File;
import java.util.List;

@SuppressWarnings("Convert2Lambda")
public class TestResourceTask extends BaseProjectTestCase {

    @Override
    public void configure(BuildProject project) {
        project.getTasks().register("Aapt2Task", Aapt2Task.class, new Action<Aapt2Task>() {
            @Override
            public void execute(Aapt2Task aapt2Task) {
                File resources = project.mkdir(project.file("src/main/res"));
                aapt2Task.setSource(resources);

                aapt2Task.getManifestFile().fileValue(project.file("src/main/AndroidManifest.xml"));
            }
        });
    }

    @Override
    public List<String> getTasks() {
        return ImmutableList.of("Aapt2Task");
    }
}
