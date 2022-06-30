package com.tyron.builder.api.internal.file.temp;

import com.tyron.builder.initialization.GradleUserHomeDirProvider;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.util.internal.GFileUtils;

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
