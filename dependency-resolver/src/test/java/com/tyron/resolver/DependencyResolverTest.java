package com.tyron.resolver;

import com.google.common.collect.ImmutableList;
import com.tyron.common.TestUtil;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.RepositoryManager;
import com.tyron.resolver.repository.RepositoryManagerImpl;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class DependencyResolverTest {

    private final RepositoryManager repository = new RepositoryManagerImpl();

    @Test
    public void testDependencyResolution() throws IOException {
        File cacheDir = new File(TestUtil.getResourcesDirectory(), "cache");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        repository.addRepository("maven", "https://repo1.maven.org/maven2");
        repository.addRepository("maven-google", "https://maven.google.com");
        repository.setCacheDirectory(cacheDir);
        repository.initialize();

        DependencyResolver resolver = new DependencyResolver(repository);

        Pom materialPom = repository.getPom("com.google.android.material:material:1.4.0");
        Pom appcompatPom = repository.getPom("androidx.appcompat:appcompat:1.3.0");
        assert materialPom != null;
        assert appcompatPom != null;

        List<Pom> dependencies = ImmutableList.of(materialPom, appcompatPom);
        List<Pom> resolvedPoms =  resolver.resolve(dependencies);

        Pom newerFragment = Pom.valueOf("androidx.fragment", "fragment", "300");

        // this does not actually get the newer fragment, the equals implementation of
        // Pom is that if the artifactId and groupId is the same, it is equal
        // so this should return the older fragment
        Pom pom = resolvedPoms.get(resolvedPoms.indexOf(newerFragment));
        assert pom != null;
        assert pom.getVersionName().equals("1.3.4");

        // the version 1.4.0 of the material library depends on fragment 1.3.4
        // to test the dependency resolution, we will inject a higher version of fragment
        // and see if it gets overwritten

        dependencies = ImmutableList.of(materialPom, newerFragment);
        resolvedPoms =  resolver.resolve(dependencies);
        pom = resolvedPoms.get(resolvedPoms.indexOf(newerFragment));
        assert pom != null;
        assert pom.getVersionName().equals("300");

        FileUtils.forceDelete(cacheDir);
    }
}
