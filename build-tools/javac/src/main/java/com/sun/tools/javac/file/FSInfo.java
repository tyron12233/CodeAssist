//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.sun.tools.javac.file;

import com.github.marschall.com.sun.nio.zipfs.ZipFileSystemProvider;
import com.sun.tools.javac.util.Context;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;

/**
 * Modified version of JDK 17's FSInfo to return a custom FileSystemProvider
 */
public class FSInfo {
    private FileSystemProvider jarFSProvider;

    public static FSInfo instance(Context context) {
        FSInfo instance = (FSInfo)context.get(FSInfo.class);
        if (instance == null) {
            instance = new FSInfo();
        }

        return instance;
    }

    protected FSInfo() {
    }

    protected FSInfo(Context context) {
        context.put(FSInfo.class, this);
    }

    public Path getCanonicalFile(Path file) {
        try {
            return file.toRealPath();
        } catch (IOException var3) {
            return file.toAbsolutePath().normalize();
        }
    }

    public boolean exists(Path file) {
        return Files.exists(file);
    }

    public boolean isDirectory(Path file) {
        return Files.isDirectory(file);
    }

    public boolean isFile(Path file) {
        return Files.isRegularFile(file);
    }

    public List<Path> getJarClassPath(Path file) throws IOException {
        JarFile jarFile = new JarFile(file.toFile());
        Throwable var3 = null;

        List<Path> var7;
        try {
            Manifest man = jarFile.getManifest();
            if (man == null) {
                return Collections.emptyList();
            }

            Attributes attr = man.getMainAttributes();
            if (attr == null) {
                return Collections.emptyList();
            }

            String path = attr.getValue(Name.CLASS_PATH);
            if (path != null) {
                List<Path> list = new ArrayList<>();
                URL base = file.toUri().toURL();
                StringTokenizer st = new StringTokenizer(path);

                while(st.hasMoreTokens()) {
                    String elt = st.nextToken();

                    try {
                        URL url = tryResolveFile(base, elt);
                        if (url != null) {
                            list.add(Paths.get(url.toURI()));
                        }
                    } catch (URISyntaxException var23) {
                        throw new IOException(var23);
                    }
                }

                return list;
            }

            var7 = Collections.emptyList();
        } catch (Throwable var24) {
            var3 = var24;
            throw var24;
        } finally {
            if (var3 != null) {
                try {
                    jarFile.close();
                } catch (Throwable var22) {
                    var3.addSuppressed(var22);
                }
            } else {
                jarFile.close();
            }
        }

        return var7;
    }

    static URL tryResolveFile(URL base, String input) throws MalformedURLException {
        URL retVal = new URL(base, input);
        return input.indexOf(58) >= 0 && !"file".equalsIgnoreCase(retVal.getProtocol()) ? null : retVal;
    }

    public synchronized FileSystemProvider getJarFSProvider() {
        if (this.jarFSProvider != null) {
            return this.jarFSProvider;
        } else {
            this.jarFSProvider = new ZipFileSystemProvider();
            return jarFSProvider;
        }
    }
}