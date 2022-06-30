package com.tyron.builder.api.internal.classpath;

import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.util.internal.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

public class ManifestUtil {
    private static final String[] EMPTY = new String[0];

    public static String createManifestClasspath(File jarFile, Collection<File> classpath) {
        List<String> paths = new ArrayList<String>(classpath.size());
        for (File file : classpath) {
            String path = constructRelativeClasspathUri(jarFile, file);
            paths.add(path);
        }

        return CollectionUtils.join(" ", paths);
    }

    private static String constructRelativeClasspathUri(File jarFile, File file) {
        URI jarFileUri = jarFile.getParentFile().toURI();
        URI fileUri = file.toURI();
        URI relativeUri = jarFileUri.relativize(fileUri);
        return relativeUri.getRawPath();
    }

    public static List<URI> parseManifestClasspath(File jarFile) {
        List<URI> manifestClasspath = new ArrayList<URI>();
        for (String value : readManifestClasspathString(jarFile)) {
            try {
                URI uri = new URI(value);
                uri = jarFile.toURI().resolve(uri);
                manifestClasspath.add(uri);
            } catch (URISyntaxException e) {
                throw new UncheckedIOException(e);
            }
        }
        return manifestClasspath;
    }

    private static String[] readManifestClasspathString(File classpathFile) {
        try {
            Manifest manifest = findManifest(classpathFile);
            if (manifest == null) {
                return EMPTY;
            }
            String classpathEntry = manifest.getMainAttributes().getValue("Class-Path");
            if (classpathEntry == null || classpathEntry.trim().length() == 0) {
                return EMPTY;
            }
            return classpathEntry.split(" ");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /*
     * The manifest if this is a jar file and has a manifest, null otherwise.
     */
    private static Manifest findManifest(File possibleJarFile) throws IOException {
        if (!possibleJarFile.exists() || !possibleJarFile.isFile()) {
            return null;
        }
        JarFile jarFile;
        try {
            jarFile = new JarFile(possibleJarFile);
        } catch (ZipException e) {
            // Not a zip file
            return null;
        }
        try {
            return jarFile.getManifest();
        } finally {
            jarFile.close();
        }
    }
}
