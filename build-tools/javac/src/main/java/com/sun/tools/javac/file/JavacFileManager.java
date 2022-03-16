//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.sun.tools.javac.file;

import com.sun.tools.javac.file.JRTIndex.Entry;
import com.sun.tools.javac.file.RelativePath.RelativeDirectory;
import com.sun.tools.javac.file.RelativePath.RelativeFile;
import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.resources.CompilerProperties.Errors;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject.Kind;

public class JavacFileManager extends BaseFileManager implements StandardJavaFileManager {
    private FSInfo fsInfo;
    private static final Set<Kind> SOURCE_OR_CLASS;
    protected boolean symbolFileEnabled;
    private PathFactory pathFactory = Paths::get;
    protected JavacFileManager.SortFiles sortFiles;
    private Map<Location, Map<RelativeDirectory, List<JavacFileManager.PathAndContainer>>> pathsAndContainersByLocationAndRelativeDirectory = new HashMap<>();
    private Map<Location, List<JavacFileManager.PathAndContainer>> nonIndexingContainersByLocation = new HashMap();
    private final Map<Path, JavacFileManager.Container> containers = new HashMap<>();
    private static final JavacFileManager.Container MISSING_CONTAINER;
    private JRTIndex jrtIndex;
    private static final Set<FileVisitOption> NO_FILE_VISIT_OPTIONS;
    private static final Set<FileVisitOption> FOLLOW_LINKS_OPTIONS;
    private static final boolean fileSystemIsCaseSensitive;

    public static char[] toArray(CharBuffer buffer) {
        return buffer.hasArray() ? ((CharBuffer)buffer.compact().flip()).array() : buffer.toString().toCharArray();
    }

    public static void preRegister(Context context) {
        context.put(JavaFileManager.class, (Context.Factory<JavaFileManager>) context1 -> new JavacFileManager(context1, true, null));
    }

    public JavacFileManager(Context context, boolean register, Charset charset) {
        super(charset);
        if (register) {
            context.put(JavaFileManager.class, this);
        }

        this.setContext(context);
    }

    public void setContext(Context context) {
        super.setContext(context);
        this.fsInfo = FSInfo.instance(context);
        this.symbolFileEnabled = !this.options.isSet("ignore.symbol.file");
        String sf = this.options.get("sortFiles");
        if (sf != null) {
            this.sortFiles = sf.equals("reverse") ? JavacFileManager.SortFiles.REVERSE : JavacFileManager.SortFiles.FORWARD;
        }

    }

    public void setPathFactory(PathFactory f) {
        this.pathFactory = (PathFactory)Objects.requireNonNull(f);
        this.locations.setPathFactory(f);
    }

    private Path getPath(String first, String... more) {
        return this.pathFactory.getPath(first, more);
    }

    public void setSymbolFileEnabled(boolean b) {
        this.symbolFileEnabled = b;
    }

    public boolean isSymbolFileEnabled() {
        return this.symbolFileEnabled;
    }

    public JavaFileObject getJavaFileObject(String name) {
        return (JavaFileObject)this.getJavaFileObjects(name).iterator().next();
    }

    public JavaFileObject getJavaFileObject(Path file) {
        return (JavaFileObject)this.getJavaFileObjects(file).iterator().next();
    }

    public JavaFileObject getFileForOutput(String classname, Kind kind, JavaFileObject sibling) throws IOException {
        return this.getJavaFileForOutput(StandardLocation.CLASS_OUTPUT, classname, kind, sibling);
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
        ListBuffer<Path> paths = new ListBuffer<>();

        for (String name : names) {
            paths.append(this.getPath((String) nullCheck(name)));
        }

        return this.getJavaFileObjectsFromPaths(paths.toList());
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
        return this.getJavaFileObjectsFromStrings(Arrays.asList(nullCheck(names)));
    }

    private static boolean isValidName(String name) {
        String[] var1 = name.split("\\.", -1);
        int var2 = var1.length;

        for(int var3 = 0; var3 < var2; ++var3) {
            String s = var1[var3];
            if (!SourceVersion.isIdentifier(s)) {
                return false;
            }
        }

        return true;
    }

    private static void validateClassName(String className) {
        if (!isValidName(className)) {
            throw new IllegalArgumentException("Invalid class name: " + className);
        }
    }

    private static void validatePackageName(String packageName) {
        if (packageName.length() > 0 && !isValidName(packageName)) {
            throw new IllegalArgumentException("Invalid packageName name: " + packageName);
        }
    }

    public static void testName(String name, boolean isValidPackageName, boolean isValidClassName) {
        try {
            validatePackageName(name);
            if (!isValidPackageName) {
                throw new AssertionError("Invalid package name accepted: " + name);
            }

            printAscii("Valid package name: \"%s\"", name);
        } catch (IllegalArgumentException var5) {
            if (isValidPackageName) {
                throw new AssertionError("Valid package name rejected: " + name);
            }

            printAscii("Invalid package name: \"%s\"", name);
        }

        try {
            validateClassName(name);
            if (!isValidClassName) {
                throw new AssertionError("Invalid class name accepted: " + name);
            }

            printAscii("Valid class name: \"%s\"", name);
        } catch (IllegalArgumentException var4) {
            if (isValidClassName) {
                throw new AssertionError("Valid class name rejected: " + name);
            }

            printAscii("Invalid class name: \"%s\"", name);
        }

    }

    private static void printAscii(String format, Object... args) {
        String message;
        String ascii = "US-ASCII";
        message = new String(String.format((Locale)null, format, args).getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);

        System.out.println(message);
    }

    synchronized JavacFileManager.Container getContainer(Path path) throws IOException {
        JavacFileManager.Container fs = (JavacFileManager.Container)this.containers.get(path);
        if (fs != null) {
            return fs;
        } else if (this.fsInfo.isFile(path) && path.equals(Locations.thisSystemModules)) {
            this.containers.put(path, fs = new JavacFileManager.JRTImageContainer());
            return fs;
        } else {
            Path realPath = this.fsInfo.getCanonicalFile(path);
            fs = (JavacFileManager.Container)this.containers.get(realPath);
            if (fs != null) {
                this.containers.put(path, fs);
                return (JavacFileManager.Container)fs;
            } else {
                BasicFileAttributes attr = null;

                try {
                    attr = Files.readAttributes(realPath, BasicFileAttributes.class);
                } catch (IOException var7) {
                    fs = MISSING_CONTAINER;
                }

                if (attr != null) {
                    if (attr.isDirectory()) {
                        fs = new JavacFileManager.DirectoryContainer(realPath);
                    } else {
                        try {
                            fs = new JavacFileManager.ArchiveContainer(path);
                        } catch (SecurityException | ProviderNotFoundException var6) {
                            throw new IOException(var6);
                        }
                    }
                }

                this.containers.put(realPath, fs);
                this.containers.put(path, fs);
                return (JavacFileManager.Container)fs;
            }
        }
    }

    private synchronized JRTIndex getJRTIndex() {
        if (this.jrtIndex == null) {
            this.jrtIndex = JRTIndex.getSharedInstance();
        }

        return this.jrtIndex;
    }

    private boolean isValidFile(String s, Set<Kind> fileKinds) {
        Kind kind = getKind(s);
        return fileKinds.contains(kind);
    }

    private boolean caseMapCheck(Path f, RelativePath name) {
        if (fileSystemIsCaseSensitive) {
            return true;
        } else {
            String path;
            char sep;
            try {
                path = f.toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
                sep = f.getFileSystem().getSeparator().charAt(0);
            } catch (IOException var9) {
                return false;
            }

            char[] pcs = path.toCharArray();
            char[] ncs = name.path.toCharArray();
            int i = pcs.length - 1;
            int j = ncs.length - 1;

            while(i >= 0 && j >= 0) {
                while(i >= 0 && pcs[i] == sep) {
                    --i;
                }

                while(j >= 0 && ncs[j] == '/') {
                    --j;
                }

                if (i >= 0 && j >= 0) {
                    if (pcs[i] != ncs[j]) {
                        return false;
                    }

                    --i;
                    --j;
                }
            }

            return j < 0;
        }
    }

    public void flush() {
        this.contentCache.clear();
        this.pathsAndContainersByLocationAndRelativeDirectory.clear();
        this.nonIndexingContainersByLocation.clear();
    }

    public void close() throws IOException {
        if (this.deferredCloseTimeout > 0L) {
            this.deferredClose();
        } else {
            this.locations.close();
            Iterator var1 = this.containers.values().iterator();

            while(var1.hasNext()) {
                JavacFileManager.Container container = (JavacFileManager.Container)var1.next();
                container.close();
            }

            this.containers.clear();
            this.pathsAndContainersByLocationAndRelativeDirectory.clear();
            this.nonIndexingContainersByLocation.clear();
            this.contentCache.clear();
        }
    }

    public ClassLoader getClassLoader(Location location) {
        this.checkNotModuleOrientedLocation(location);
        Iterable<? extends File> path = this.getLocation(location);
        if (path == null) {
            return null;
        } else {
            ListBuffer<URL> lb = new ListBuffer();
            Iterator var4 = path.iterator();

            while(var4.hasNext()) {
                File f = (File)var4.next();

                try {
                    lb.append(f.toURI().toURL());
                } catch (MalformedURLException var7) {
                    throw new AssertionError(var7);
                }
            }

            return this.getClassLoader((URL[])lb.toArray(new URL[lb.size()]));
        }
    }

    public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
        this.checkNotModuleOrientedLocation(location);
        nullCheck(packageName);
        nullCheck(kinds);
        RelativeDirectory subdirectory = RelativeDirectory.forPackage(packageName);
        ListBuffer<JavaFileObject> results = new ListBuffer();
        Iterator var7 = this.pathsAndContainers(location, subdirectory).iterator();

        while(var7.hasNext()) {
            JavacFileManager.PathAndContainer pathAndContainer = (JavacFileManager.PathAndContainer)var7.next();
            Path directory = pathAndContainer.path;
            JavacFileManager.Container container = pathAndContainer.container;
            container.list(directory, subdirectory, kinds, recurse, results);
        }

        return results.toList();
    }

    public String inferBinaryName(Location location, JavaFileObject file) {
        this.checkNotModuleOrientedLocation(location);
        Objects.requireNonNull(file);
        Iterable<? extends Path> path = this.getLocationAsPaths(location);
        if (path == null) {
            return null;
        } else if (file instanceof PathFileObject) {
            return ((PathFileObject)file).inferBinaryName(path);
        } else {
            throw new IllegalArgumentException(file.getClass().getName());
        }
    }

    public boolean isSameFile(FileObject a, FileObject b) {
        nullCheck(a);
        nullCheck(b);
        return a instanceof PathFileObject && b instanceof PathFileObject ? ((PathFileObject)a).isSameFile((PathFileObject)b) : a.equals(b);
    }

    public boolean hasLocation(Location location) {
        nullCheck(location);
        return this.locations.hasLocation(location);
    }

    protected boolean hasExplicitLocation(Location location) {
        nullCheck(location);
        return this.locations.hasExplicitLocation(location);
    }

    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        this.checkNotModuleOrientedLocation(location);
        nullCheck(className);
        nullCheck(kind);
        if (!SOURCE_OR_CLASS.contains(kind)) {
            throw new IllegalArgumentException("Invalid kind: " + kind);
        } else {
            return this.getFileForInput(location, RelativeFile.forClass(className, kind));
        }
    }

    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        this.checkNotModuleOrientedLocation(location);
        nullCheck(packageName);
        if (!isRelativeUri(relativeName)) {
            throw new IllegalArgumentException("Invalid relative name: " + relativeName);
        } else {
            RelativeFile name = packageName.length() == 0 ? new RelativeFile(relativeName) : new RelativeFile(RelativeDirectory.forPackage(packageName), relativeName);
            return this.getFileForInput(location, name);
        }
    }

    private JavaFileObject getFileForInput(Location location, RelativeFile name) throws IOException {
        Iterable<? extends Path> path = this.getLocationAsPaths(location);
        if (path == null) {
            return null;
        } else {
            Iterator var4 = path.iterator();

            JavaFileObject fo;
            do {
                if (!var4.hasNext()) {
                    return null;
                }

                Path file = (Path)var4.next();
                fo = this.getContainer(file).getFileObject(file, name);
            } while(fo == null);

            return fo;
        }
    }

    public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling) throws IOException {
        this.checkOutputLocation(location);
        nullCheck(className);
        nullCheck(kind);
        if (!SOURCE_OR_CLASS.contains(kind)) {
            throw new IllegalArgumentException("Invalid kind: " + kind);
        } else {
            return this.getFileForOutput(location, RelativeFile.forClass(className, kind), sibling);
        }
    }

    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException {
        this.checkOutputLocation(location);
        nullCheck(packageName);
        if (!isRelativeUri(relativeName)) {
            throw new IllegalArgumentException("Invalid relative name: " + relativeName);
        } else {
            RelativeFile name = packageName.length() == 0 ? new RelativeFile(relativeName) : new RelativeFile(RelativeDirectory.forPackage(packageName), relativeName);
            return this.getFileForOutput(location, name, sibling);
        }
    }

    private JavaFileObject getFileForOutput(Location location, RelativeFile fileName, FileObject sibling) throws IOException {
        Path dir;
        Path real;
        if (location == StandardLocation.CLASS_OUTPUT) {
            if (this.getClassOutDir() == null) {
                String baseName = fileName.basename();
                if (sibling != null && sibling instanceof PathFileObject) {
                    return ((PathFileObject)sibling).getSibling(baseName);
                }

                Path p = this.getPath(baseName);
                real = this.fsInfo.getCanonicalFile(p);
                return PathFileObject.forSimplePath(this, real, p);
            }

            dir = this.getClassOutDir();
        } else if (location == StandardLocation.SOURCE_OUTPUT) {
            dir = this.getSourceOutDir() != null ? this.getSourceOutDir() : this.getClassOutDir();
        } else {
            Iterable<? extends Path> path = this.locations.getLocation(location);
            dir = null;
            Iterator var11 = path.iterator();
            if (var11.hasNext()) {
                real = (Path)var11.next();
                dir = real;
            }
        }

        try {
            if (dir == null) {
                dir = this.getPath(System.getProperty("user.dir"));
            }
            Path path = fileName.resolveAgainst(this.fsInfo.getCanonicalFile(dir));
            return PathFileObject.forDirectoryPath(this, path, dir, fileName);
        } catch (InvalidPathException var8) {
            throw new IOException("bad filename " + fileName, var8);
        }
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
        ArrayList result;
        if (files instanceof Collection) {
            result = new ArrayList(((Collection)files).size());
        } else {
            result = new ArrayList();
        }

        Iterator var3 = files.iterator();

        while(var3.hasNext()) {
            File f = (File)var3.next();
            Objects.requireNonNull(f);
            Path p = f.toPath();
            result.add(PathFileObject.forSimplePath(this, this.fsInfo.getCanonicalFile(p), p));
        }

        return result;
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromPaths(Collection<? extends Path> paths) {
        ArrayList result;
        if (paths != null) {
            result = new ArrayList(paths.size());
            Iterator var3 = paths.iterator();

            while(var3.hasNext()) {
                Path p = (Path)var3.next();
                result.add(PathFileObject.forSimplePath(this, this.fsInfo.getCanonicalFile(p), p));
            }
        } else {
            result = new ArrayList();
        }

        return result;
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
        return this.getJavaFileObjectsFromFiles(Arrays.asList(nullCheck(files)));
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjects(Path... paths) {
        return this.getJavaFileObjectsFromPaths(Arrays.asList(nullCheck(paths)));
    }

    public void setLocation(Location location, Iterable<? extends File> searchpath) throws IOException {
        nullCheck(location);
        this.locations.setLocation(location, asPaths(searchpath));
        this.clearCachesForLocation(location);
    }

    public void setLocationFromPaths(Location location, Collection<? extends Path> searchpath) throws IOException {
        nullCheck(location);
        this.locations.setLocation(location, nullCheck(searchpath));
        this.clearCachesForLocation(location);
    }

    public Iterable<? extends File> getLocation(Location location) {
        nullCheck(location);
        return asFiles(this.locations.getLocation(location));
    }

    public Collection<? extends Path> getLocationAsPaths(Location location) {
        nullCheck(location);
        return this.locations.getLocation(location);
    }

    private List<JavacFileManager.PathAndContainer> pathsAndContainers(Location location, RelativeDirectory relativeDirectory) throws IOException {
        try {
            return (List)((Map)this.pathsAndContainersByLocationAndRelativeDirectory.computeIfAbsent(location, this::indexPathsAndContainersByRelativeDirectory)).computeIfAbsent(relativeDirectory, (d) -> {
                return (List)this.nonIndexingContainersByLocation.get(location);
            });
        } catch (UncheckedIOException var4) {
            throw var4.getCause();
        }
    }

    private Map<RelativeDirectory, List<JavacFileManager.PathAndContainer>> indexPathsAndContainersByRelativeDirectory(Location location) {
        Map<RelativeDirectory, List<JavacFileManager.PathAndContainer>> result = new HashMap();
        List<JavacFileManager.PathAndContainer> allPathsAndContainers = this.pathsAndContainers(location);
        List<JavacFileManager.PathAndContainer> nonIndexingContainers = new ArrayList();
        Iterator var5 = allPathsAndContainers.iterator();

        JavacFileManager.PathAndContainer pathAndContainer;
        while(var5.hasNext()) {
            pathAndContainer = (JavacFileManager.PathAndContainer)var5.next();
            if (!pathAndContainer.container.maintainsDirectoryIndex()) {
                nonIndexingContainers.add(pathAndContainer);
            }
        }

        var5 = allPathsAndContainers.iterator();

        while(true) {
            JavacFileManager.Container container;
            do {
                if (!var5.hasNext()) {
                    this.nonIndexingContainersByLocation.put(location, nonIndexingContainers);
                    result.values().forEach((pathAndContainerList) -> {
                        Collections.sort(pathAndContainerList);
                    });
                    return result;
                }

                pathAndContainer = (JavacFileManager.PathAndContainer)var5.next();
                container = pathAndContainer.container;
            } while(!container.maintainsDirectoryIndex());

            Iterator var8 = container.indexedDirectories().iterator();

            while(var8.hasNext()) {
                RelativeDirectory directory = (RelativeDirectory)var8.next();
                ((List)result.computeIfAbsent(directory, (d) -> {
                    return new ArrayList(nonIndexingContainers);
                })).add(pathAndContainer);
            }
        }
    }

    private List<JavacFileManager.PathAndContainer> pathsAndContainers(Location location) {
        Collection<? extends Path> paths = this.getLocationAsPaths(location);
        if (paths == null) {
            return com.sun.tools.javac.util.List.nil();
        } else {
            List<JavacFileManager.PathAndContainer> pathsAndContainers = new ArrayList(paths.size());

            Path path;
            JavacFileManager.Container container;
            for(Iterator var4 = paths.iterator(); var4.hasNext(); pathsAndContainers.add(new JavacFileManager.PathAndContainer(path, container, pathsAndContainers.size()))) {
                path = (Path)var4.next();

                try {
                    container = this.getContainer(path);
                } catch (IOException var8) {
                    throw new UncheckedIOException(var8);
                }
            }

            return pathsAndContainers;
        }
    }

    public boolean contains(Location location, FileObject fo) throws IOException {
        nullCheck(location);
        nullCheck(fo);
        Path p = this.asPath(fo);
        return this.locations.contains(location, p);
    }

    private Path getClassOutDir() {
        return this.locations.getOutputLocation(StandardLocation.CLASS_OUTPUT);
    }

    private Path getSourceOutDir() {
        return this.locations.getOutputLocation(StandardLocation.SOURCE_OUTPUT);
    }

    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        this.checkModuleOrientedOrOutputLocation((Location)location);
        nullCheck(moduleName);
        if (location == StandardLocation.SOURCE_OUTPUT && this.getSourceOutDir() == null) {
            location = StandardLocation.CLASS_OUTPUT;
        }

        return this.locations.getLocationForModule((Location)location, moduleName);
    }

    public <S> ServiceLoader<S> getServiceLoader(Location location, Class<S> service) throws IOException {
        throw new UnsupportedOperationException();
    }

    public Location getLocationForModule(Location location, JavaFileObject fo) throws IOException {
        this.checkModuleOrientedOrOutputLocation(location);
        if (!(fo instanceof PathFileObject)) {
            return null;
        } else {
            Path p = Locations.normalize(((PathFileObject)fo).path);
            return this.locations.getLocationForModule(location, p);
        }
    }

    public void setLocationForModule(Location location, String moduleName, Collection<? extends Path> paths) throws IOException {
        nullCheck(location);
        this.checkModuleOrientedOrOutputLocation(location);
        this.locations.setLocationForModule(location, (String)nullCheck(moduleName), nullCheck(paths));
        this.clearCachesForLocation(location);
    }

    public String inferModuleName(Location location) {
        this.checkNotModuleOrientedLocation(location);
        return this.locations.inferModuleName(location);
    }

    public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        this.checkModuleOrientedOrOutputLocation(location);
        return this.locations.listLocationsForModules(location);
    }

    public Path asPath(FileObject file) {
        if (file instanceof PathFileObject) {
            return ((PathFileObject)file).path;
        } else {
            throw new IllegalArgumentException(file.getName());
        }
    }

    protected static boolean isRelativeUri(URI uri) {
        if (uri.isAbsolute()) {
            return false;
        } else {
            String path = uri.normalize().getPath();
            if (path.length() == 0) {
                return false;
            } else if (!path.equals(uri.getPath())) {
                return false;
            } else {
                return !path.startsWith("/") && !path.startsWith("./") && !path.startsWith("../");
            }
        }
    }

    protected static boolean isRelativeUri(String u) {
        try {
            return isRelativeUri(new URI(u));
        } catch (URISyntaxException var2) {
            return false;
        }
    }

    public static String getRelativeName(File file) {
        if (!file.isAbsolute()) {
            String result = file.getPath().replace(File.separatorChar, '/');
            if (isRelativeUri(result)) {
                return result;
            }
        }

        throw new IllegalArgumentException("Invalid relative path: " + file);
    }

    public static String getMessage(IOException e) {
        String s = e.getLocalizedMessage();
        if (s != null) {
            return s;
        } else {
            s = e.getMessage();
            return s != null ? s : e.toString();
        }
    }

    private void checkOutputLocation(Location location) {
        Objects.requireNonNull(location);
        if (!location.isOutputLocation()) {
            throw new IllegalArgumentException("location is not an output location: " + location.getName());
        }
    }

    private void checkModuleOrientedOrOutputLocation(Location location) {
        Objects.requireNonNull(location);
        if (!location.isModuleOrientedLocation() && !location.isOutputLocation()) {
            throw new IllegalArgumentException("location is not an output location or a module-oriented location: " + location.getName());
        }
    }

    private void checkNotModuleOrientedLocation(Location location) {
        Objects.requireNonNull(location);
        if (location.isModuleOrientedLocation()) {
            throw new IllegalArgumentException("location is module-oriented: " + location.getName());
        }
    }

    private static Iterable<Path> asPaths(Iterable<? extends File> files) {
        return files == null ? null : () -> {
            return new Iterator<Path>() {
                Iterator iter = files.iterator();

                public boolean hasNext() {
                    return this.iter.hasNext();
                }

                public Path next() {
                    return ((File)this.iter.next()).toPath();
                }
            };
        };
    }

    private static Iterable<File> asFiles(Iterable<? extends Path> paths) {
        return paths == null ? null : () -> new Iterator<File>() {
            final Iterator<? extends Path> iter = paths.iterator();

            public boolean hasNext() {
                return this.iter.hasNext();
            }

            public File next() {
                try {
                    return ((Path)this.iter.next()).toFile();
                } catch (UnsupportedOperationException var2) {
                    throw new IllegalStateException(var2);
                }
            }
        };
    }

    public boolean handleOption(Option option, String value) {
        if (Option.getJavacFileManagerOptions().contains(option)) {
            this.pathsAndContainersByLocationAndRelativeDirectory.clear();
            this.nonIndexingContainersByLocation.clear();
        }

        return super.handleOption(option, value);
    }

    private void clearCachesForLocation(Location location) {
        nullCheck(location);
        this.pathsAndContainersByLocationAndRelativeDirectory.remove(location);
        this.nonIndexingContainersByLocation.remove(location);
    }

    static {
        SOURCE_OR_CLASS = EnumSet.of(Kind.SOURCE, Kind.CLASS);
        MISSING_CONTAINER = new JavacFileManager.Container() {
            public void list(Path userPath, RelativeDirectory subdirectory, Set<Kind> fileKinds, boolean recurse, ListBuffer<JavaFileObject> resultList) throws IOException {
            }

            public JavaFileObject getFileObject(Path userPath, RelativeFile name) throws IOException {
                return null;
            }

            public void close() throws IOException {
            }

            public boolean maintainsDirectoryIndex() {
                return false;
            }

            public Iterable<RelativeDirectory> indexedDirectories() {
                return com.sun.tools.javac.util.List.nil();
            }
        };
        NO_FILE_VISIT_OPTIONS = Collections.emptySet();
        FOLLOW_LINKS_OPTIONS = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
        fileSystemIsCaseSensitive = File.separatorChar == '/';
    }

    private static class PathAndContainer implements Comparable<JavacFileManager.PathAndContainer> {
        private final Path path;
        private final JavacFileManager.Container container;
        private final int index;

        PathAndContainer(Path path, JavacFileManager.Container container, int index) {
            this.path = path;
            this.container = container;
            this.index = index;
        }

        public int compareTo(JavacFileManager.PathAndContainer other) {
            return this.index - other.index;
        }

        public boolean equals(Object o) {
            if (o instanceof PathAndContainer) {
                JavacFileManager.PathAndContainer that = (JavacFileManager.PathAndContainer)o;
                return this.path.equals(that.path) && this.container.equals(that.container) && this.index == this.index;
            } else {
                return false;
            }
        }

        public int hashCode() {
            return Objects.hash(this.path, this.container, this.index);
        }
    }

    public boolean isDefaultSystemModulesPath() {
        return true;
    }

    private final class ArchiveContainer implements JavacFileManager.Container {
        private final Path archivePath;
        private final FileSystem fileSystem;
        private final Map<RelativeDirectory, Path> packages;

        public ArchiveContainer(Path archivePath) throws IOException, ProviderNotFoundException, SecurityException {
            this.archivePath = archivePath;
            //  if (JavacFileManager.this.multiReleaseValue != null && archivePath.toString().endsWith(".jar")) {
            Map<String, String> env = Collections.singletonMap("multi-release", JavacFileManager.this.multiReleaseValue);
            FileSystemProvider jarFSProvider = JavacFileManager.this.fsInfo.getJarFSProvider();
            Assert.checkNonNull(jarFSProvider, "should have been caught before!");
            this.fileSystem = jarFSProvider.newFileSystem(archivePath, env);
//            } else {
//                this.fileSystem = FileSystems.newFileSystem(archivePath, (ClassLoader)null);
//            }

            this.packages = new HashMap<>();

            for (Path root : this.fileSystem.getRootDirectories()) {
                Files.walkFileTree(root, JavacFileManager.NO_FILE_VISIT_OPTIONS, 2147483647,
                                   new SimpleFileVisitor<Path>() {
                                       public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                           if (ArchiveContainer.this.isValid(dir.getFileName())) {
                                               ArchiveContainer.this.packages.put(new RelativeDirectory(root.relativize(dir).toString()), dir);
                                               return FileVisitResult.CONTINUE;
                                           } else {
                                               return FileVisitResult.SKIP_SUBTREE;
                                           }
                                       }
                                   });
            }

        }

        public void list(Path userPath, RelativeDirectory subdirectory, final Set<Kind> fileKinds, boolean recurse, final ListBuffer<JavaFileObject> resultList) throws IOException {
            Path resolvedSubdirectory = (Path)this.packages.get(subdirectory);
            if (resolvedSubdirectory != null) {
                int maxDepth = recurse ? 2147483647 : 1;
                Files.walkFileTree(resolvedSubdirectory, JavacFileManager.FOLLOW_LINKS_OPTIONS, maxDepth, new SimpleFileVisitor<Path>() {
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return ArchiveContainer.this.isValid(dir.getFileName()) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                    }

                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && fileKinds.contains(BaseFileManager.getKind(file.getFileName().toString()))) {
                            JavaFileObject fe = PathFileObject.forJarPath(JavacFileManager.this, file, ArchiveContainer.this.archivePath);
                            resultList.append(fe);
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }

        private boolean isValid(Path fileName) {
            if (fileName == null) {
                return true;
            } else {
                String name = fileName.toString();
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }

                return SourceVersion.isIdentifier(name);
            }
        }

        public JavaFileObject getFileObject(Path userPath, RelativeFile name) throws IOException {
            RelativeDirectory root = name.dirname();
            Path packagepath = (Path)this.packages.get(root);
            if (packagepath != null) {
                Path relpath = packagepath.resolve(name.basename());
                if (Files.exists(relpath)) {
                    return PathFileObject.forJarPath(JavacFileManager.this, relpath, userPath);
                }
            }

            return null;
        }

        public void close() throws IOException {
            this.fileSystem.close();
        }

        public boolean maintainsDirectoryIndex() {
            return true;
        }

        public Iterable<RelativeDirectory> indexedDirectories() {
            return this.packages.keySet();
        }
    }

    private final class DirectoryContainer implements JavacFileManager.Container {
        private final Path directory;

        public DirectoryContainer(Path directory) {
            this.directory = directory;
        }

        public void list(Path userPath, RelativeDirectory subdirectory, Set<Kind> fileKinds, boolean recurse, ListBuffer<JavaFileObject> resultList) throws IOException {
            Path d;
            try {
                d = subdirectory.resolveAgainst(userPath);
            } catch (InvalidPathException var22) {
                return;
            }

            if (Files.exists(d)) {
                if (JavacFileManager.this.caseMapCheck(d, subdirectory)) {
                    List<Path> files;
                    try {
                        Stream<Path> s = Files.list(d);
                        Throwable var9 = null;

                        try {
                            files = (JavacFileManager.this.sortFiles == null ? s : s.sorted(JavacFileManager.this.sortFiles)).collect(Collectors.toList());
                        } catch (Throwable var21) {
                            var9 = var21;
                            throw var21;
                        } finally {
                            if (s != null) {
                                if (var9 != null) {
                                    try {
                                        s.close();
                                    } catch (Throwable var20) {
                                        var9.addSuppressed(var20);
                                    }
                                } else {
                                    s.close();
                                }
                            }

                        }
                    } catch (IOException var25) {
                        return;
                    }

                    for (Path f : files) {
                        String fname = f.getFileName().toString();
                        if (fname.endsWith("/")) {
                            fname = fname.substring(0, fname.length() - 1);
                        }

                        if (Files.isDirectory(f)) {
                            if (recurse && SourceVersion.isIdentifier(fname)) {
                                this.list(userPath, new RelativeDirectory(subdirectory, fname),
                                          fileKinds, recurse, resultList);
                            }
                        } else if (JavacFileManager.this.isValidFile(fname, fileKinds)) {
                            try {
                                RelativeFile file = new RelativeFile(subdirectory, fname);
                                JavaFileObject fe =
                                        PathFileObject.forDirectoryPath(JavacFileManager.this,
                                                                        file.resolveAgainst(this.directory), userPath,
                                                                        file);
                                resultList.append(fe);
                            } catch (InvalidPathException var23) {
                                throw new IOException("error accessing directory " + this.directory + var23);
                            }
                        }
                    }

                }
            }
        }

        public JavaFileObject getFileObject(Path userPath, RelativeFile name) throws IOException {
            try {
                Path f = name.resolveAgainst(userPath);
                if (Files.exists(f, new LinkOption[0])) {
                    return PathFileObject.forSimplePath(JavacFileManager.this, JavacFileManager.this.fsInfo.getCanonicalFile(f), f);
                }
            } catch (InvalidPathException var4) {
            }

            return null;
        }

        public void close() throws IOException {
        }

        public boolean maintainsDirectoryIndex() {
            return false;
        }

        public Iterable<RelativeDirectory> indexedDirectories() {
            return com.sun.tools.javac.util.List.nil();
        }
    }

    private final class JRTImageContainer implements JavacFileManager.Container {
        private JRTImageContainer() {
        }

        public void list(Path userPath, RelativeDirectory subdirectory, Set<Kind> fileKinds, boolean recurse, ListBuffer<JavaFileObject> resultList) throws IOException {
            try {
                Entry e = JavacFileManager.this.getJRTIndex().getEntry(subdirectory);
                if (JavacFileManager.this.symbolFileEnabled && e.ctSym.hidden) {
                    return;
                }

                for (Path file : e.files.values()) {
                    if (fileKinds.contains(BaseFileManager.getKind(file))) {
                        JavaFileObject fe = PathFileObject.forJRTPath(JavacFileManager.this, file);
                        resultList.append(fe);
                    }
                }

                if (recurse) {
                    for (RelativeDirectory rd : e.subdirs) {
                        this.list(userPath, rd, fileKinds, recurse, resultList);
                    }
                }
            } catch (IOException var10) {
                var10.printStackTrace(System.err);
                JavacFileManager.this.log.error(Errors.ErrorReadingFile(userPath, JavacFileManager.getMessage(var10)));
            }

        }

        public JavaFileObject getFileObject(Path userPath, RelativeFile name) throws IOException {
            Entry e = JavacFileManager.this.getJRTIndex().getEntry(name.dirname());
            if (JavacFileManager.this.symbolFileEnabled && e.ctSym.hidden) {
                return null;
            } else {
                Path p = (Path)e.files.get(name.basename());
                return p != null ? PathFileObject.forJRTPath(JavacFileManager.this, p) : null;
            }
        }

        public void close() throws IOException {
        }

        public boolean maintainsDirectoryIndex() {
            return false;
        }

        public Iterable<RelativeDirectory> indexedDirectories() {
            return com.sun.tools.javac.util.List.nil();
        }
    }

    private interface Container {
        void list(Path var1, RelativeDirectory var2, Set<Kind> var3, boolean var4, ListBuffer<JavaFileObject> var5) throws IOException;

        JavaFileObject getFileObject(Path var1, RelativeFile var2) throws IOException;

        void close() throws IOException;

        boolean maintainsDirectoryIndex();

        Iterable<RelativeDirectory> indexedDirectories();
    }

    protected static enum SortFiles implements Comparator<Path> {
        FORWARD {
            public int compare(Path f1, Path f2) {
                return f1.getFileName().compareTo(f2.getFileName());
            }
        },
        REVERSE {
            public int compare(Path f1, Path f2) {
                return f2.getFileName().compareTo(f1.getFileName());
            }
        };

        private SortFiles() {
        }
    }
}