package com.tyron.resolver;

import android.util.Log;

import com.tyron.resolver.model.Dependency;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencyDownloader {

    private static final String TAG = DependencyDownloader.class.getSimpleName();

    private File mOutputDir;
    private Set<Dependency> mDownloadedLibraries;

    public DependencyDownloader(Set <Dependency> downloaded, File file) {
        mOutputDir = file;
        mDownloadedLibraries = downloaded;
    }

    public void download(Set<Dependency> libraries) throws IOException {
        for (Dependency dependency : libraries) {
            download(dependency);
        }
    }

    private void download(Dependency library) throws IOException {

        // first we check if the library exists or there is an older version of it
        for (Dependency downloaded : mDownloadedLibraries) {
            if (!downloaded.getGroupId().equals(library.getGroupId())) {
                continue;
            }

            if (!downloaded.getAtrifactId().equals(library.getAtrifactId())) {
                continue;
            }

            ComparableVersion downloadedVersion = new ComparableVersion(downloaded.getVersion());
            ComparableVersion willDownloadVersion = new ComparableVersion(library.getVersion());

            int result = downloadedVersion.compareTo(willDownloadVersion);
            if (result == 0) {
                // The versions are equal, so don't bother downloading this one
                return;
            } else if (result > 0) {
                // the downloaded version is greater than this one, so keep the downloaded version
                return;
            } else {
                // This version is newer, delete the old one and proceed to download
                File file = new File(mOutputDir, downloaded.toString() + ".jar");
                if (!file.exists()) {
                   // lets try if this is an aar
                   file = new File(mOutputDir, downloaded.toString() + ".aar");
                }

                if (file.exists()) {
                    if (!file.delete()) {
                        throw new IOException("Failed to delete old library: " + file.getName());
                    }
                }
                break;
            }
        }

        // if we got into here then we download the library
        // lets try with aar first
        boolean isAar = true;
        InputStream is = DependencyResolver.getAarStream(library);
        if (is == null) {
            // this library doesnt have an aar file, lets try with a jar file
            isAar = false;
            is = DependencyResolver.getJarStream(library);
        }

        if (is == null) {
            Log.d(TAG, "Failed to find download link for library: " + library);
            return;
        }

        Log.d(TAG, "Downloading library: " + library.getFileName());

            File outputFile = new File(mOutputDir, library.toString() + (isAar ? ".aar" : ".jar"));
            if (!outputFile.exists()) {
                if (!outputFile.createNewFile()) {
                    throw new IOException("Unable to create file: " + outputFile.getName());
                }
            }
            FileUtils.copyInputStreamToFile(is, outputFile);
    }
}
