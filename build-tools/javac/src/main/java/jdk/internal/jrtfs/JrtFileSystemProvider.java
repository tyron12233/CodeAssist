//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package jdk.internal.jrtfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public final class JrtFileSystemProvider extends FileSystemProvider {
    private volatile FileSystem theFileSystem;
    private static final String JRT_FS_JAR = "jrt-fs.jar";

    public JrtFileSystemProvider() {
    }

    public String getScheme() {
        return "jrt";
    }

    private void checkPermission() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            RuntimePermission perm = new RuntimePermission("accessSystemModules");
            sm.checkPermission(perm);
        }

    }

    private void checkUri(URI uri) {
        if (!uri.getScheme().equalsIgnoreCase(this.getScheme())) {
            throw new IllegalArgumentException("URI does not match this provider");
        } else if (uri.getAuthority() != null) {
            throw new IllegalArgumentException("Authority component present");
        } else if (uri.getPath() == null) {
            throw new IllegalArgumentException("Path component is undefined");
        } else if (!uri.getPath().equals("/")) {
            throw new IllegalArgumentException("Path component should be '/'");
        } else if (uri.getQuery() != null) {
            throw new IllegalArgumentException("Query component present");
        } else if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Fragment component present");
        }
    }

    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        Objects.requireNonNull(env);
        this.checkPermission();
        this.checkUri(uri);
        return (FileSystem)(env.containsKey("java.home") ? this.newFileSystem((String)env.get("java.home"), uri, env) : new JrtFileSystemWrapper(new JrtFileSystem(this, env)) {

        });
    }

    private FileSystem newFileSystem(String targetHome, URI uri, Map<String, ?> env) throws IOException {
        Objects.requireNonNull(targetHome);
        Path jrtfs = FileSystems.getDefault().getPath(targetHome, "lib", "jrt-fs.jar");
        if (Files.notExists(jrtfs)) {
            throw new IOException(jrtfs.toString() + " not exist");
        } else {
            Map<String, ?> newEnv = new HashMap<>(env);
            newEnv.remove("java.home");

            return newFileSystem(uri, newEnv);
        }
    }

    public Path getPath(URI uri) {
        this.checkPermission();
        if (!uri.getScheme().equalsIgnoreCase(this.getScheme())) {
            throw new IllegalArgumentException("URI does not match this provider");
        } else if (uri.getAuthority() != null) {
            throw new IllegalArgumentException("Authority component present");
        } else if (uri.getQuery() != null) {
            throw new IllegalArgumentException("Query component present");
        } else if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Fragment component present");
        } else {
            String path = uri.getPath();
            if (path != null && path.charAt(0) == '/') {
                return this.getTheFileSystem().getPath(path);
            } else {
                throw new IllegalArgumentException("Invalid path component");
            }
        }
    }

    private FileSystem getTheFileSystem() {
        this.checkPermission();
        FileSystem fs = this.theFileSystem;
        if (fs == null) {
            synchronized(this) {
                fs = this.theFileSystem;
                if (fs == null) {
                    try {
                        this.theFileSystem = (FileSystem)(fs = new JrtFileSystem(this, null));
                    } catch (IOException var5) {
                        throw new InternalError(var5);
                    }
                }
            }
        }

        return (FileSystem)fs;
    }

    public FileSystem getFileSystem(URI uri) {
        this.checkPermission();
        this.checkUri(uri);
        return this.getTheFileSystem();
    }

    static final JrtPath toJrtPath(Path path) {
        Objects.requireNonNull(path, "path");
        if (!(path instanceof JrtPath)) {
            throw new ProviderMismatchException();
        } else {
            return (JrtPath)path;
        }
    }

    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        toJrtPath(path).checkAccess(modes);
    }

    public Path readSymbolicLink(Path link) throws IOException {
        return toJrtPath(link).readSymbolicLink();
    }

    public void copy(Path src, Path target, CopyOption... options) throws IOException {
        toJrtPath(src).copy(toJrtPath(target), options);
    }

    public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException {
        toJrtPath(path).createDirectory(attrs);
    }

    public final void delete(Path path) throws IOException {
        toJrtPath(path).delete();
    }

    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return JrtFileAttributeView.get(toJrtPath(path), type, options);
    }

    public FileStore getFileStore(Path path) throws IOException {
        return toJrtPath(path).getFileStore();
    }

    public boolean isHidden(Path path) {
        return toJrtPath(path).isHidden();
    }

    public boolean isSameFile(Path path, Path other) throws IOException {
        return toJrtPath(path).isSameFile(other);
    }

    public void move(Path src, Path target, CopyOption... options) throws IOException {
        toJrtPath(src).move(toJrtPath(target), options);
    }

    public AsynchronousFileChannel newAsynchronousFileChannel(Path path, Set<? extends OpenOption> options, ExecutorService exec, FileAttribute<?>... attrs) throws IOException {
        throw new UnsupportedOperationException();
    }

    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return toJrtPath(path).newByteChannel(options, attrs);
    }

    public DirectoryStream<Path> newDirectoryStream(Path path, Filter<? super Path> filter) throws IOException {
        return toJrtPath(path).newDirectoryStream(filter);
    }

    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return toJrtPath(path).newFileChannel(options, attrs);
    }

    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return toJrtPath(path).newInputStream(options);
    }

    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return toJrtPath(path).newOutputStream(options);
    }

    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        return type != BasicFileAttributes.class && type != JrtFileAttributes.class ? null : (A) toJrtPath(path).getAttributes(options);
    }

    public Map<String, Object> readAttributes(Path path, String attribute, LinkOption... options) throws IOException {
        return toJrtPath(path).readAttributes(attribute, options);
    }

    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        toJrtPath(path).setAttribute(attribute, value, options);
    }

    private static class JrtFsLoader extends URLClassLoader {
        JrtFsLoader(URL[] urls) {
            super(urls);
        }

        protected Class<?> loadClass(String cn, boolean resolve) throws ClassNotFoundException {
            Class<?> c = this.findLoadedClass(cn);
            if (c == null) {
                URL u = this.findResource(cn.replace('.', '/') + ".class");
                if (u == null) {
                    return super.loadClass(cn, resolve);
                }

                c = this.findClass(cn);
            }

            if (resolve) {
                this.resolveClass(c);
            }

            return c;
        }
    }
}