package com.tyron.resolver;

import com.tyron.common.TestUtil;
import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.RepositoryManagerImpl;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class RepositoryManagerTest {

    private final RepositoryManagerImpl repository = new RepositoryManagerImpl();

    @Test
    public void test() throws IOException {
        File cacheDir = new File(TestUtil.getResourcesDirectory(), "cache");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        repository.addRepository("maven", "https://repo1.maven.org/maven2");
        repository.addRepository("maven-google", "https://maven.google.com");

        repository.setCacheDirectory(cacheDir);
        repository.initialize();

        Pom pom = repository.getPom("com.google.android.material:material:1.4.0");
        assert pom != null;
        // get all the dependencies of this pom
        recurse(pom);

        // check if direct dependencies exists
        Pom androidxCore = repository.getPom("androidx.core:core:1.5.0");
        assert androidxCore != null;

        // check if indirect dependencies of material library exists
        Pom annotations = repository.getPom("androidx.annotation:annotation:1.1.0");
        assert annotations != null;

        File library = repository.getLibrary(androidxCore);
        System.out.println(library);
        assert library != null;

        File annotationsFile = repository.getLibrary(annotations);
        assert annotationsFile != null;

        try {
            FileUtils.forceDelete(cacheDir);
        } catch (IOException e) {
            if (TestUtil.isWindows()) {
                // cannot delete file on windows cause intellij is still using it, ignore
                return;
            }
            // should not happen on other OSes
            throw e;
        }
    }

    private void recurse(Pom pom) {
        for (Dependency dependency : pom.getDependencies()) {
            if ("test".equals(dependency.getScope())) {
                continue;
            }
            Pom pomFile = repository.getPom(dependency.toString());
            if (pomFile == null) {
                continue;
            }

            recurse(pomFile);
        }
    }
}
