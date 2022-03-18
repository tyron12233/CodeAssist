//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package jdk.internal.jrtfs;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReader.Node;

abstract class SystemImage {
    static final String RUNTIME_HOME;
    static final Path moduleImageFile;
    static final boolean modulesImageExists;
    static final Path explodedModulesDir;

    SystemImage() {
    }

    abstract Node findNode(String var1) throws IOException;

    abstract byte[] getResource(Node var1) throws IOException;

    abstract void close() throws IOException;

    static SystemImage open() throws IOException {
        if (modulesImageExists) {
            final ImageReader image = ImageReader.open(moduleImageFile);
            image.getRootDirectory();
            return new SystemImage() {
                Node findNode(String path) throws IOException {
                    return image.findNode(path);
                }

                byte[] getResource(Node node) throws IOException {
                    return image.getResource(node);
                }

                void close() throws IOException {
                    image.close();
                }
            };
        } else if (Files.notExists(explodedModulesDir)) {
            throw new FileSystemNotFoundException(explodedModulesDir.toString());
        } else {
            return new ExplodedImage(explodedModulesDir);
        }
    }

    private static String findHome() {
        return "BuildModule.getAndroidJar().getParent();";
//        CodeSource cs = SystemImage.class.getProtectionDomain().getCodeSource();
//        if (cs == null) {
//            return System.getProperty("java.home");
//        } else {
//            URL url = cs.getLocation();
//            if (!url.getProtocol().equalsIgnoreCase("file")) {
//                throw new InternalError(url + " loaded in unexpected way");
//            } else {
//                try {
//                    Path lib = Paths.get(url.toURI()).getParent();
//                    if (!lib.getFileName().toString().equals("lib")) {
//                        throw new InternalError(url + " unexpected path");
//                    } else {
//                        return lib.getParent().toString();
//                    }
//                } catch (URISyntaxException var3) {
//                    throw new InternalError(var3);
//                }
//            }
//        }
    }

    static {
        PrivilegedAction<String> pa = SystemImage::findHome;
        RUNTIME_HOME = (String)AccessController.doPrivileged(pa);
        FileSystem fs = FileSystems.getDefault();
        moduleImageFile = fs.getPath(RUNTIME_HOME, "lib", "modules");
        explodedModulesDir = fs.getPath(RUNTIME_HOME, "modules");
        modulesImageExists = (Boolean)AccessController.doPrivileged((PrivilegedAction<Boolean>) () -> Files.isRegularFile(SystemImage.moduleImageFile));
    }
}