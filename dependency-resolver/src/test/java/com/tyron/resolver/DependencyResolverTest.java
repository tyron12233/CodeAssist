package com.tyron.resolver;

import com.tyron.common.TestUtil;
import com.tyron.resolver.repository.PomRepositoryImpl;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class DependencyResolverTest {

    private final PomRepositoryImpl repository = new PomRepositoryImpl();

    @Test
    public void testDependencyResolution() throws IOException {
        repository.addRepositoryUrl("https://repo1.maven.org/maven2");
        repository.addRepositoryUrl("https://maven.google.com");
        repository.setCacheDirectory(new File(TestUtil.getResourcesDirectory(), "cache"));
        repository.initialize();
    }
}
