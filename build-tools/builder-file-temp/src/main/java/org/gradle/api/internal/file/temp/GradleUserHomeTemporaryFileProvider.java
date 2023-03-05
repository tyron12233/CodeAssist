package org.gradle.api.internal.file.temp;

import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.internal.Factory;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.util.internal.GFileUtils;

import javax.inject.Inject;
import java.io.File;

@ServiceScope(Scopes.UserHome.class)
public class GradleUserHomeTemporaryFileProvider extends DefaultTemporaryFileProvider {
    @Inject
    public GradleUserHomeTemporaryFileProvider(final GradleUserHomeDirProvider gradleUserHomeDirProvider) {
        super(new Factory<File>() {
            @Override
            public File create() {
                return GFileUtils.canonicalize(new File(gradleUserHomeDirProvider.getGradleUserHomeDirectory(), ".tmp"));
            }
        });
    }
}
