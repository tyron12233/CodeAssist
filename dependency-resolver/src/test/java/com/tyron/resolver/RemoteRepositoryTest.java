package com.tyron.resolver;

import com.tyron.common.TestUtil;
import com.tyron.resolver.repository.FileType;
import com.tyron.resolver.repository.RemoteRepository;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class RemoteRepositoryTest {

    @Test
    public void test() throws IOException {
        File cacheDir = new File(TestUtil.getResourcesDirectory(), "cache");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }

        RemoteRepository remoteRepository = new RemoteRepository("google-maven", "https://maven.google.com");
        remoteRepository.setCacheDirectory(cacheDir);

        InputStream inputStream = remoteRepository.getInputStream("com/google/android/material" +
                "/material/1.4.0/material-1.4.0.pom");
        assert inputStream != null;
    }
}
