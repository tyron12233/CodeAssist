package com.tyron.resolver;

import com.tyron.resolver.repository.PomRepositoryImpl;

import org.junit.Test;

import java.io.File;

public class DependencyResolverTest {

    private final PomRepositoryImpl repository = new PomRepositoryImpl();

    @Test
    public void testDependencyResolution() {
        repository.addRepositoryUrl("https://repo1.maven.org/maven2");
        repository.addRepositoryUrl("https://maven.google.com");
        repository.setCacheDirectory(new File("/home/tyron/AndroidStudioProjects/CodeAssist/dependency-resolver/src/test/resources/cache"));
        repository.initialize();
    }
}
