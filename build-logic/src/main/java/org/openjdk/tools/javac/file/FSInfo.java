package org.openjdk.tools.javac.file;

import com.github.marschall.com.sun.nio.zipfs.ZipFileSystemProvider;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;
import org.openjdk.tools.javac.util.Context;

public class FSInfo {
    private FileSystemProvider jarFSProvider;

    public static FSInfo instance(Context var0) {
        FSInfo var1 = (FSInfo)var0.get(FSInfo.class);
        if (var1 == null) {
            var1 = new FSInfo();
        }

        return var1;
    }

    protected FSInfo() {
    }

    protected FSInfo(Context var1) {
        var1.put(FSInfo.class, this);
    }

    public Path getCanonicalFile(Path var1) {
        try {
            return var1.toRealPath();
        } catch (IOException var3) {
            return var1.toAbsolutePath().normalize();
        }
    }

    public boolean exists(Path var1) {
        return Files.exists(var1);
    }

    public boolean isDirectory(Path var1) {
        return Files.isDirectory(var1);
    }

    public boolean isFile(Path var1) {
        return Files.isRegularFile(var1);
    }

    public List<Path> getJarClassPath(Path var1) throws IOException {
        Path var2 = var1.getParent();
        JarFile var3 = new JarFile(var1.toFile());
        Throwable var4 = null;

        List<Path> var8;
        try {
            Manifest var5 = var3.getManifest();
            if (var5 == null) {
                return Collections.emptyList();
            }

            Attributes var6 = var5.getMainAttributes();
            if (var6 == null) {
                return Collections.emptyList();
            }

            String var7 = var6.getValue(Name.CLASS_PATH);
            if (var7 != null) {
                ArrayList<Path> var26 = new ArrayList<>();

                Path var11;
                for(StringTokenizer var9 = new StringTokenizer(var7); var9.hasMoreTokens(); var26.add(var11)) {
                    String var10 = var9.nextToken();
                    var11 = FileSystems.getDefault().getPath(var10);
                    if (!var11.isAbsolute() && var2 != null) {
                        var11 = var2.resolve(var11).toAbsolutePath();
                    }
                }

                return var26;
            }

            var8 = Collections.emptyList();
        } catch (Throwable var22) {
            var4 = var22;
            throw var22;
        } finally {
            if (var3 != null) {
                if (var4 != null) {
                    try {
                        var3.close();
                    } catch (Throwable var21) {
                        var4.addSuppressed(var21);
                    }
                } else {
                    var3.close();
                }
            }

        }

        return var8;
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