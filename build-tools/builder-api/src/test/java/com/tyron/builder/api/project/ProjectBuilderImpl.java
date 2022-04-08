package com.tyron.builder.api.project;

import com.tyron.builder.api.StartParameter;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistryBuilder;
import com.tyron.builder.api.internal.reflect.service.scopes.BasicGlobalScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GlobalServices;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.util.GFileUtils;
import com.tyron.builder.initialization.BuildRequestMetaData;
import com.tyron.builder.initialization.DefaultBuildRequestMetaData;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.session.state.CrossBuildSessionState;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public class ProjectBuilderImpl {

    private static ServiceRegistry globalServices;

    public ProjectInternal createProject(String name, File inputProjectDir, File gradleUserHomeDir) {
        final File projectDir = prepareProjectDir(inputProjectDir);
        File userHomeDir = gradleUserHomeDir == null ? new File(projectDir, "userHome") : GFileUtils.canonicalize(gradleUserHomeDir);
        StartParameterInternal startParameter = new StartParameterInternal();
        startParameter.setGradleUserHomeDir(userHomeDir);

        final ServiceRegistry globalServices = getGlobalServices();

        BuildRequestMetaData buildRequestMetaData = new DefaultBuildRequestMetaData(Time.currentTimeMillis());
        CrossBuildSessionState crossBuildSessionState = new CrossBuildSessionState(globalServices, startParameter);
        GradleUserHomeScopeServiceRegistry userHomeServices = userHomeServicesOf(globalServices);

        return null;
    }

    private GradleUserHomeScopeServiceRegistry userHomeServicesOf(ServiceRegistry globalServices) {
        return globalServices.get(GradleUserHomeScopeServiceRegistry.class);
    }

    public synchronized static ServiceRegistry getGlobalServices() {
        if (globalServices == null) {
            globalServices = createGlobalServices();
        }
        return globalServices;
    }

    private static ServiceRegistry createGlobalServices() {
        return ServiceRegistryBuilder
                .builder()
                .displayName("global services")
                .parent(new GlobalServices())
                .build();
    }

    public File prepareProjectDir(@Nullable final File projectDir) {
        if (projectDir == null) {
            throw new IllegalArgumentException();
        }
        return GFileUtils.canonicalize(projectDir);
    }
}
