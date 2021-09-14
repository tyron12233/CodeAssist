//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.openjdk.tools.javac.file;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openjdk.javax.lang.model.SourceVersion;
import org.openjdk.javax.tools.FileObject;
import org.openjdk.javax.tools.JavaFileManager;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.javax.tools.StandardJavaFileManager;
import org.openjdk.javax.tools.StandardLocation;
import org.openjdk.javax.tools.JavaFileManager.Location;
import org.openjdk.javax.tools.JavaFileObject.Kind;
import org.openjdk.javax.tools.StandardJavaFileManager.PathFactory;
import org.openjdk.tools.javac.file.JRTIndex.Entry;
import org.openjdk.tools.javac.file.RelativePath.RelativeDirectory;
import org.openjdk.tools.javac.file.RelativePath.RelativeFile;
import org.openjdk.tools.javac.util.Assert;
import org.openjdk.tools.javac.util.Context;
import org.openjdk.tools.javac.util.List;
import org.openjdk.tools.javac.util.ListBuffer;
import org.openjdk.tools.javac.util.JDK9Wrappers.Configuration;
import org.openjdk.tools.javac.util.JDK9Wrappers.Layer;
import org.openjdk.tools.javac.util.JDK9Wrappers.Module;
import org.openjdk.tools.javac.util.JDK9Wrappers.ModuleFinder;
import org.openjdk.tools.javac.util.JDK9Wrappers.ServiceLoaderHelper;

/**
 * I have modified this a bit to use the custom file system manager because
 * android doesn't provide a ZipFileSystemProvider
 */
@SuppressWarnings("ALL")
public class JavacFileManager extends BaseFileManager implements StandardJavaFileManager {
    private FSInfo fsInfo;
    private final Set<Kind> sourceOrClass;
    protected boolean symbolFileEnabled;
    private PathFactory pathFactory;
    protected JavacFileManager.SortFiles sortFiles;
    private final Map<Path, JavacFileManager.Container> containers;
    private static final JavacFileManager.Container MISSING_CONTAINER = new JavacFileManager.Container() {
        public void list(Path var1, RelativeDirectory var2, Set<Kind> var3, boolean var4, ListBuffer<JavaFileObject> var5) throws IOException {
        }

        public JavaFileObject getFileObject(Path var1, RelativeFile var2) throws IOException {
            return null;
        }

        public void close() throws IOException {
        }
    };
    private JRTIndex jrtIndex;
    private static final boolean fileSystemIsCaseSensitive;

    public static char[] toArray(CharBuffer var0) {
        return var0.hasArray() ? ((CharBuffer)var0.compact().flip()).array() : var0.toString().toCharArray();
    }

    public static void preRegister(Context var0) {
        var0.put(JavaFileManager.class, (Context.Factory<JavaFileManager>) context -> new JavacFileManager(context, true, null));
    }

    public JavacFileManager(Context var1, boolean var2, Charset var3) {
        super(var3);
        this.sourceOrClass = EnumSet.of(Kind.SOURCE, Kind.CLASS);
        this.pathFactory = Paths::get;
        this.containers = new HashMap<>();
        if (var2) {
            var1.put(JavaFileManager.class, this);
        }

        this.setContext(var1);
    }

    public void setContext(Context var1) {
        super.setContext(var1);
        this.fsInfo = FSInfo.instance(var1);
        this.symbolFileEnabled = !this.options.isSet("ignore.symbol.file");
        String var2 = this.options.get("sortFiles");
        if (var2 != null) {
            this.sortFiles = var2.equals("reverse") ? JavacFileManager.SortFiles.REVERSE : JavacFileManager.SortFiles.FORWARD;
        }

    }

    public void setPathFactory(PathFactory var1) {
        this.pathFactory = Objects.requireNonNull(var1);
        this.locations.setPathFactory(var1);
    }

    private Path getPath(String var1, String... var2) {
        return this.pathFactory.getPath(var1, var2);
    }

    public void setSymbolFileEnabled(boolean var1) {
        this.symbolFileEnabled = var1;
    }

    public boolean isSymbolFileEnabled() {
        return this.symbolFileEnabled;
    }

    public JavaFileObject getJavaFileObject(String var1) {
        return this.getJavaFileObjects(var1).iterator().next();
    }

    public JavaFileObject getJavaFileObject(Path var1) {
        return this.getJavaFileObjects(var1).iterator().next();
    }

    public JavaFileObject getFileForOutput(String var1, Kind var2, JavaFileObject var3) throws IOException {
        return this.getJavaFileForOutput(StandardLocation.CLASS_OUTPUT, var1, var2, var3);
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> var1) {
        ListBuffer<Path> var2 = new ListBuffer<>();

        for (String var4 : var1) {
            var2.append(this.getPath(BaseFileManager.nullCheck(var4)));
        }

        return this.getJavaFileObjectsFromPaths(var2.toList());
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... var1) {
        return this.getJavaFileObjectsFromStrings(Arrays.asList((BaseFileManager.nullCheck(var1))));
    }

    private static boolean isValidName(String var0) {
        String[] var1 = var0.split("\\.", -1);
        int var2 = var1.length;

        for (String var4 : var1) {
            if (!SourceVersion.isIdentifier(var4)) {
                return false;
            }
        }

        return true;
    }

    private static void validateClassName(String var0) {
        if (!isValidName(var0)) {
            throw new IllegalArgumentException("Invalid class name: " + var0);
        }
    }

    private static void validatePackageName(String var0) {
        if (var0.length() > 0 && !isValidName(var0)) {
            throw new IllegalArgumentException("Invalid packageName name: " + var0);
        }
    }

    public static void testName(String var0, boolean var1, boolean var2) {
        try {
            validatePackageName(var0);
            if (!var1) {
                throw new AssertionError("Invalid package name accepted: " + var0);
            }

            printAscii("Valid package name: \"%s\"", var0);
        } catch (IllegalArgumentException var5) {
            if (var1) {
                throw new AssertionError("Valid package name rejected: " + var0);
            }

            printAscii("Invalid package name: \"%s\"", var0);
        }

        try {
            validateClassName(var0);
            if (!var2) {
                throw new AssertionError("Invalid class name accepted: " + var0);
            }

            printAscii("Valid class name: \"%s\"", var0);
        } catch (IllegalArgumentException var4) {
            if (var2) {
                throw new AssertionError("Valid class name rejected: " + var0);
            }

            printAscii("Invalid class name: \"%s\"", var0);
        }

    }

    private static void printAscii(String var0, Object... var1) {
        String var2;
        try {
            var2 = new String(String.format((Locale)null, var0, var1).getBytes("US-ASCII"), "US-ASCII");
        } catch (UnsupportedEncodingException var4) {
            throw new AssertionError(var4);
        }

        System.out.println(var2);
    }

    synchronized JavacFileManager.Container getContainer(Path var1) throws IOException {
        JavacFileManager.Container var2 = (JavacFileManager.Container)this.containers.get(var1);
        if (var2 != null) {
            return var2;
        } else if (this.fsInfo.isFile(var1) && var1.equals(Locations.thisSystemModules)) {
            JavacFileManager.JRTImageContainer var9;
            this.containers.put(var1, var9 = new JavacFileManager.JRTImageContainer());
            return var9;
        } else {
            Path var3 = this.fsInfo.getCanonicalFile(var1);
            JavacFileManager.Container var8 = this.containers.get(var3);
            if (var8 != null) {
                this.containers.put(var1, var8);
                return var8;
            } else {
                BasicFileAttributes var4 = null;

                try {
                    var4 = Files.readAttributes(var3, BasicFileAttributes.class);
                } catch (IOException var7) {
                    var8 = MISSING_CONTAINER;
                }

                if (var4 != null) {
                    if (var4.isDirectory()) {
                        var8 = new JavacFileManager.DirectoryContainer(var1);
                    } else {
                        try {
                            var8 = new JavacFileManager.ArchiveContainer(var1);
                        } catch (SecurityException | ProviderNotFoundException var6) {
                            throw new IOException(var6);
                        }
                    }
                }

                this.containers.put(var3, var8);
                this.containers.put(var1, var8);
                return (JavacFileManager.Container)var8;
            }
        }
    }

    private synchronized JRTIndex getJRTIndex() {
        if (this.jrtIndex == null) {
            this.jrtIndex = JRTIndex.getSharedInstance();
        }

        return this.jrtIndex;
    }

    private boolean isValidFile(String var1, Set<Kind> var2) {
        Kind var3 = BaseFileManager.getKind(var1);
        return var2.contains(var3);
    }

    private boolean caseMapCheck(Path var1, RelativePath var2) {
        if (fileSystemIsCaseSensitive) {
            return true;
        } else {
            String var3;
            char var4;
            try {
                var3 = var1.toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
                var4 = var1.getFileSystem().getSeparator().charAt(0);
            } catch (IOException var9) {
                return false;
            }

            char[] var5 = var3.toCharArray();
            char[] var6 = var2.path.toCharArray();
            int var7 = var5.length - 1;
            int var8 = var6.length - 1;

            while(var7 >= 0 && var8 >= 0) {
                while(var7 >= 0 && var5[var7] == var4) {
                    --var7;
                }

                while(var8 >= 0 && var6[var8] == '/') {
                    --var8;
                }

                if (var7 >= 0 && var8 >= 0) {
                    if (var5[var7] != var6[var8]) {
                        return false;
                    }

                    --var7;
                    --var8;
                }
            }

            return var8 < 0;
        }
    }

    public void flush() {
        this.contentCache.clear();
    }

    public void close() throws IOException {
        if (this.deferredCloseTimeout > 0L) {
            this.deferredClose();
        } else {
            this.locations.close();
            Iterator var1 = this.containers.values().iterator();

            while(var1.hasNext()) {
                JavacFileManager.Container var2 = (JavacFileManager.Container)var1.next();
                var2.close();
            }

            this.containers.clear();
            this.contentCache.clear();
        }
    }

    public ClassLoader getClassLoader(Location var1) {
        this.checkNotModuleOrientedLocation(var1);
        Iterable<? extends File> var2 = this.getLocation(var1);
        if (var2 == null) {
            return null;
        } else {
            ListBuffer<URL> var3 = new ListBuffer<>();

            for (File var5 : var2) {
                try {
                    var3.append(var5.toURI().toURL());
                } catch (MalformedURLException var7) {
                    throw new AssertionError(var7);
                }
            }

            return this.getClassLoader(var3.toArray(new URL[0]));
        }
    }

    public Iterable<JavaFileObject> list(Location var1, String var2, Set<Kind> var3, boolean var4) throws IOException {
        this.checkNotModuleOrientedLocation(var1);
        BaseFileManager.nullCheck(var2);
        BaseFileManager.nullCheck(var3);
        Iterable var5 = this.getLocationAsPaths(var1);
        if (var5 == null) {
            return List.nil();
        } else {
            RelativeDirectory var6 = RelativeDirectory.forPackage(var2);
            ListBuffer var7 = new ListBuffer();
            Iterator var8 = var5.iterator();

            while(var8.hasNext()) {
                Path var9 = (Path)var8.next();
                JavacFileManager.Container var10 = this.getContainer(var9);
                var10.list(var9, var6, var3, var4, var7);
            }

            return var7.toList();
        }
    }

    public String inferBinaryName(Location var1, JavaFileObject var2) {
        this.checkNotModuleOrientedLocation(var1);
        Objects.requireNonNull(var2);
        Iterable<? extends Path> var3 = this.getLocationAsPaths(var1);
        if (var3 == null) {
            return null;
        } else if (var2 instanceof PathFileObject) {
            return ((PathFileObject)var2).inferBinaryName(var3);
        } else {
            throw new IllegalArgumentException(var2.getClass().getName());
        }
    }

    public boolean isSameFile(FileObject var1, FileObject var2) {
        BaseFileManager.nullCheck(var1);
        BaseFileManager.nullCheck(var2);
        return var1 instanceof PathFileObject && var2 instanceof PathFileObject ? ((PathFileObject)var1).isSameFile((PathFileObject)var2) : var1.equals(var2);
    }

    public boolean hasLocation(Location var1) {
        BaseFileManager.nullCheck(var1);
        return this.locations.hasLocation(var1);
    }

    public JavaFileObject getJavaFileForInput(Location var1, String var2, Kind var3) throws IOException {
        this.checkNotModuleOrientedLocation(var1);
        BaseFileManager.nullCheck(var2);
        BaseFileManager.nullCheck(var3);
        if (!this.sourceOrClass.contains(var3)) {
            throw new IllegalArgumentException("Invalid kind: " + var3);
        } else {
            return this.getFileForInput(var1, RelativeFile.forClass(var2, var3));
        }
    }

    public FileObject getFileForInput(Location var1, String var2, String var3) throws IOException {
        this.checkNotModuleOrientedLocation(var1);
        BaseFileManager.nullCheck(var2);
        if (!isRelativeUri(var3)) {
            throw new IllegalArgumentException("Invalid relative name: " + var3);
        } else {
            RelativeFile var4 = var2.length() == 0 ? new RelativeFile(var3) : new RelativeFile(RelativeDirectory.forPackage(var2), var3);
            return this.getFileForInput(var1, var4);
        }
    }

    private JavaFileObject getFileForInput(Location var1, RelativeFile var2) throws IOException {
        Iterable<? extends Path> var3 = this.getLocationAsPaths(var1);
        if (var3 == null) {
            return null;
        } else {
            Iterator<? extends Path> var4 = var3.iterator();

            JavaFileObject var6;
            do {
                if (!var4.hasNext()) {
                    return null;
                }

                Path var5 = (Path)var4.next();
                var6 = this.getContainer(var5).getFileObject(var5, var2);
            } while(var6 == null);

            return var6;
        }
    }

    public JavaFileObject getJavaFileForOutput(Location var1, String var2, Kind var3, FileObject var4) throws IOException {
        this.checkOutputLocation(var1);
        BaseFileManager.nullCheck(var2);
        BaseFileManager.nullCheck(var3);
        if (!this.sourceOrClass.contains(var3)) {
            throw new IllegalArgumentException("Invalid kind: " + var3);
        } else {
            return this.getFileForOutput(var1, RelativeFile.forClass(var2, var3), var4);
        }
    }

    public FileObject getFileForOutput(Location var1, String var2, String var3, FileObject var4) throws IOException {
        this.checkOutputLocation(var1);
        BaseFileManager.nullCheck(var2);
        if (!isRelativeUri(var3)) {
            throw new IllegalArgumentException("Invalid relative name: " + var3);
        } else {
            RelativeFile var5 = var2.length() == 0 ? new RelativeFile(var3) : new RelativeFile(RelativeDirectory.forPackage(var2), var3);
            return this.getFileForOutput(var1, var5, var4);
        }
    }

    private JavaFileObject getFileForOutput(Location var1, RelativeFile var2, FileObject var3) throws IOException {
        Path var4;
        Path var7;
        if (var1 == StandardLocation.CLASS_OUTPUT) {
            if (this.getClassOutDir() == null) {
                String var5 = var2.basename();
                if (var3 != null && var3 instanceof PathFileObject) {
                    return ((PathFileObject)var3).getSibling(var5);
                }

                Path var6 = this.getPath(var5);
                var7 = this.fsInfo.getCanonicalFile(var6);
                return PathFileObject.forSimplePath(this, var7, var6);
            }

            var4 = this.getClassOutDir();
        } else if (var1 == StandardLocation.SOURCE_OUTPUT) {
            var4 = this.getSourceOutDir() != null ? this.getSourceOutDir() : this.getClassOutDir();
        } else {
            Collection var9 = this.locations.getLocation(var1);
            var4 = null;
            Iterator var11 = var9.iterator();
            if (var11.hasNext()) {
                var7 = (Path)var11.next();
                var4 = var7;
            }
        }

        try {
            if (var4 == null) {
                var4 = this.getPath(System.getProperty("user.dir"));
            }

            Path var10 = var2.resolveAgainst(this.fsInfo.getCanonicalFile(var4));
            return PathFileObject.forDirectoryPath(this, var10, var4, var2);
        } catch (InvalidPathException var8) {
            throw new IOException("bad filename " + var2, var8);
        }
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> var1) {
        ArrayList<JavaFileObject> var2;
        if (var1 instanceof Collection) {
            var2 = new ArrayList<>(((Collection<? extends JavaFileObject>) var1).size());
        } else {
            var2 = new ArrayList<>();
        }

        for (File var4 : var1) {
            Objects.requireNonNull(var4);
            Path var5 = var4.toPath();
            var2.add(PathFileObject.forSimplePath(this, this.fsInfo.getCanonicalFile(var5), var5));
        }

        return var2;
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromPaths(Iterable<? extends Path> var1) {
        ArrayList var2;
        if (var1 instanceof Collection) {
            var2 = new ArrayList(((Collection)var1).size());
        } else {
            var2 = new ArrayList();
        }

        Iterator var3 = var1.iterator();

        while(var3.hasNext()) {
            Path var4 = (Path)var3.next();
            var2.add(PathFileObject.forSimplePath(this, this.fsInfo.getCanonicalFile(var4), var4));
        }

        return var2;
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... var1) {
        return this.getJavaFileObjectsFromFiles(Arrays.asList(BaseFileManager.nullCheck(var1)));
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjects(Path... var1) {
        return this.getJavaFileObjectsFromPaths(Arrays.asList(BaseFileManager.nullCheck(var1)));
    }

    public void setLocation(Location var1, Iterable<? extends File> var2) throws IOException {
        BaseFileManager.nullCheck(var1);
        this.locations.setLocation(var1, asPaths(var2));
    }

    public void setLocationFromPaths(Location var1, Collection<? extends Path> var2) throws IOException {
        BaseFileManager.nullCheck(var1);
        this.locations.setLocation(var1, BaseFileManager.nullCheck(var2));
    }

    public Iterable<? extends File> getLocation(Location var1) {
        BaseFileManager.nullCheck(var1);
        return asFiles(this.locations.getLocation(var1));
    }

    public Iterable<? extends Path> getLocationAsPaths(Location var1) {
        BaseFileManager.nullCheck(var1);
        return this.locations.getLocation(var1);
    }

    public boolean contains(Location var1, FileObject var2) throws IOException {
        BaseFileManager.nullCheck(var1);
        BaseFileManager.nullCheck(var2);
        Path var3 = this.asPath(var2);
        return this.locations.contains(var1, var3);
    }

    private Path getClassOutDir() {
        return this.locations.getOutputLocation(StandardLocation.CLASS_OUTPUT);
    }

    private Path getSourceOutDir() {
        return this.locations.getOutputLocation(StandardLocation.SOURCE_OUTPUT);
    }

    public Location getLocationForModule(Location var1, String var2) throws IOException {
        this.checkModuleOrientedOrOutputLocation((Location)var1);
        BaseFileManager.nullCheck(var2);
        if (var1 == StandardLocation.SOURCE_OUTPUT && this.getSourceOutDir() == null) {
            var1 = StandardLocation.CLASS_OUTPUT;
        }

        return this.locations.getLocationForModule((Location)var1, var2);
    }

    public <S> ServiceLoader<S> getServiceLoader(Location var1, Class<S> var2) throws IOException {
        BaseFileManager.nullCheck(var1);
        BaseFileManager.nullCheck(var2);
        Module.getModule(this.getClass()).addUses(var2);
        if (var1.isModuleOrientedLocation()) {
            Collection var3 = this.locations.getLocation(var1);
            ModuleFinder var4 = ModuleFinder.of((Path[])var3.toArray(new Path[var3.size()]));
            Layer var5 = Layer.boot();
            Configuration var6 = var5.configuration().resolveAndBind(ModuleFinder.of(new Path[0]), var4, Collections.emptySet());
            Layer var7 = var5.defineModulesWithOneLoader(var6, ClassLoader.getSystemClassLoader());
            return ServiceLoaderHelper.load(var7, var2);
        } else {
            return ServiceLoader.load(var2, this.getClassLoader(var1));
        }
    }

    public Location getLocationForModule(Location var1, JavaFileObject var2) throws IOException {
        this.checkModuleOrientedOrOutputLocation(var1);
        if (!(var2 instanceof PathFileObject)) {
            return null;
        } else {
            Path var3 = Locations.normalize(((PathFileObject)var2).path);
            return this.locations.getLocationForModule(var1, var3);
        }
    }

    public void setLocationForModule(Location var1, String var2, Collection<? extends Path> var3) throws IOException {
        BaseFileManager.nullCheck(var1);
        this.checkModuleOrientedOrOutputLocation(var1);
        this.locations.setLocationForModule(var1, (String) BaseFileManager.nullCheck(var2), BaseFileManager.nullCheck(var3));
    }

    public String inferModuleName(Location var1) {
        this.checkNotModuleOrientedLocation(var1);
        return this.locations.inferModuleName(var1);
    }

    public Iterable<Set<Location>> listLocationsForModules(Location var1) throws IOException {
        this.checkModuleOrientedOrOutputLocation(var1);
        return this.locations.listLocationsForModules(var1);
    }

    public Path asPath(FileObject var1) {
        if (var1 instanceof PathFileObject) {
            return ((PathFileObject)var1).path;
        } else {
            throw new IllegalArgumentException(var1.getName());
        }
    }

    protected static boolean isRelativeUri(URI var0) {
        if (var0.isAbsolute()) {
            return false;
        } else {
            String var1 = var0.normalize().getPath();
            if (var1.length() == 0) {
                return false;
            } else if (!var1.equals(var0.getPath())) {
                return false;
            } else {
                return !var1.startsWith("/") && !var1.startsWith("./") && !var1.startsWith("../");
            }
        }
    }

    protected static boolean isRelativeUri(String var0) {
        try {
            return isRelativeUri(new URI(var0));
        } catch (URISyntaxException var2) {
            return false;
        }
    }

    public static String getRelativeName(File var0) {
        if (!var0.isAbsolute()) {
            String var1 = var0.getPath().replace(File.separatorChar, '/');
            if (isRelativeUri(var1)) {
                return var1;
            }
        }

        throw new IllegalArgumentException("Invalid relative path: " + var0);
    }

    public static String getMessage(IOException var0) {
        String var1 = var0.getLocalizedMessage();
        if (var1 != null) {
            return var1;
        } else {
            var1 = var0.getMessage();
            return var1 != null ? var1 : var0.toString();
        }
    }

    private void checkOutputLocation(Location var1) {
        Objects.requireNonNull(var1);
        if (!var1.isOutputLocation()) {
            throw new IllegalArgumentException("location is not an output location: " + var1.getName());
        }
    }

    private void checkModuleOrientedOrOutputLocation(Location var1) {
        Objects.requireNonNull(var1);
        if (!var1.isModuleOrientedLocation() && !var1.isOutputLocation()) {
            throw new IllegalArgumentException("location is not an output location or a module-oriented location: " + var1.getName());
        }
    }

    private void checkNotModuleOrientedLocation(Location var1) {
        Objects.requireNonNull(var1);
        if (var1.isModuleOrientedLocation()) {
            throw new IllegalArgumentException("location is module-oriented: " + var1.getName());
        }
    }

    private static Iterable<Path> asPaths(Iterable<? extends File> var0) {
        return var0 == null ? null : () -> {
            return new Iterator<Path>() {
                Iterator iter = var0.iterator();

                public boolean hasNext() {
                    return this.iter.hasNext();
                }

                public Path next() {
                    return ((File)this.iter.next()).toPath();
                }
            };
        };
    }

    private static Iterable<File> asFiles(Iterable<? extends Path> var0) {
        return var0 == null ? null : () -> {
            return new Iterator<File>() {
                Iterator iter = var0.iterator();

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
        };
    }

    static {
        fileSystemIsCaseSensitive = File.separatorChar == '/';
    }

    private final class ArchiveContainer implements JavacFileManager.Container {
        private final Path archivePath;
        private final FileSystem fileSystem;
        private final Map<RelativePath, Path> packages;

        public ArchiveContainer(Path var2) throws IOException, ProviderNotFoundException, SecurityException {
            this.archivePath = var2;
//            if (JavacFileManager.this.multiReleaseValue != null && var2.toString().endsWith(".jar")) {
                Map<String, String> var3 = Collections.singletonMap("multi-release", JavacFileManager.this.multiReleaseValue);
                FileSystemProvider var4 = JavacFileManager.this.fsInfo.getJarFSProvider();
                Assert.checkNonNull(var4, "should have been caught before!");
                this.fileSystem = var4.newFileSystem(var2, var3);
//            } else {
//                this.fileSystem = FileSystems.newFileSystem(var2, (ClassLoader)null);
//            }

            this.packages = new HashMap<>();

            for (Path var6 : this.fileSystem.getRootDirectories()) {
                Files.walkFileTree(var6, EnumSet.noneOf(FileVisitOption.class), 2147483647, new SimpleFileVisitor<Path>() {
                    public FileVisitResult preVisitDirectory(Path var1, BasicFileAttributes var2) {
                        if (ArchiveContainer.this.isValid(var1.getFileName())) {
                            ArchiveContainer.this.packages.put(new RelativeDirectory(var6.relativize(var1).toString()), var1);
                            return FileVisitResult.CONTINUE;
                        } else {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                });
            }

        }

        public void list(Path var1, RelativeDirectory var2, final Set<Kind> var3, boolean var4, final ListBuffer<JavaFileObject> var5) throws IOException {
            Path var6 = (Path)this.packages.get(var2);
            if (var6 != null) {
                int var7 = var4 ? 2147483647 : 1;
                EnumSet<FileVisitOption> var8 = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
                Files.walkFileTree(var6, var8, var7, new SimpleFileVisitor<Path>() {
                    public FileVisitResult preVisitDirectory(Path var1, BasicFileAttributes var2) {
                        return ArchiveContainer.this.isValid(var1.getFileName()) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                    }

                    public FileVisitResult visitFile(Path var1, BasicFileAttributes var2) {
                        if (var2.isRegularFile() && var3.contains(BaseFileManager.getKind(var1.getFileName().toString()))) {
                            PathFileObject var3x = PathFileObject.forJarPath(JavacFileManager.this, var1, ArchiveContainer.this.archivePath);
                            var5.append(var3x);
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }

        private boolean isValid(Path var1) {
            if (var1 == null) {
                return true;
            } else {
                String var2 = var1.toString();
                if (var2.endsWith("/")) {
                    var2 = var2.substring(0, var2.length() - 1);
                }

                return SourceVersion.isIdentifier(var2);
            }
        }

        public JavaFileObject getFileObject(Path var1, RelativeFile var2) throws IOException {
            RelativeDirectory var3 = var2.dirname();
            Path var4 = (Path)this.packages.get(var3);
            if (var4 != null) {
                Path var5 = var4.resolve(var2.basename());
                if (Files.exists(var5)) {
                    return PathFileObject.forJarPath(JavacFileManager.this, var5, var1);
                }
            }

            return null;
        }

        public void close() throws IOException {
            this.fileSystem.close();
        }
    }

    private final class DirectoryContainer implements JavacFileManager.Container {
        private final Path directory;

        public DirectoryContainer(Path var2) {
            this.directory = var2;
        }

        public void list(Path var1, RelativeDirectory var2, Set<Kind> var3, boolean var4, ListBuffer<JavaFileObject> var5) throws IOException {
            Path var6;
            try {
                var6 = var2.resolveAgainst(var1);
            } catch (InvalidPathException var22) {
                return;
            }

            if (Files.exists(var6)) {
                if (JavacFileManager.this.caseMapCheck(var6, var2)) {
                    java.util.List<Path> var7;
                    try {
                        Stream<Path> var8 = Files.list(var6);
                        Throwable var9 = null;

                        try {
                            var7 = (JavacFileManager.this.sortFiles == null ? var8 : var8.sorted(JavacFileManager.this.sortFiles)).collect(Collectors.toList());
                        } catch (Throwable var21) {
                            var9 = var21;
                            throw var21;
                        } finally {
                            if (var8 != null) {
                                if (var9 != null) {
                                    try {
                                        var8.close();
                                    } catch (Throwable var20) {
                                        var9.addSuppressed(var20);
                                    }
                                } else {
                                    var8.close();
                                }
                            }

                        }
                    } catch (IOException var25) {
                        return;
                    }

                    for (Path var27 : var7) {
                        String var10 = var27.getFileName().toString();
                        if (var10.endsWith("/")) {
                            var10 = var10.substring(0, var10.length() - 1);
                        }

                        if (Files.isDirectory(var27)) {
                            if (var4 && SourceVersion.isIdentifier(var10)) {
                                this.list(var1, new RelativeDirectory(var2, var10), var3, var4, var5);
                            }
                        } else if (JavacFileManager.this.isValidFile(var10, var3)) {
                            try {
                                RelativeFile var11 = new RelativeFile(var2, var10);
                                PathFileObject var12 = PathFileObject.forDirectoryPath(JavacFileManager.this, var11.resolveAgainst(this.directory), var1, var11);
                                var5.append(var12);
                            } catch (InvalidPathException var23) {
                                throw new IOException("error accessing directory " + this.directory + var23);
                            }
                        }
                    }

                }
            }
        }

        public JavaFileObject getFileObject(Path var1, RelativeFile var2) throws IOException {
            try {
                Path var3 = var2.resolveAgainst(var1);
                if (Files.exists(var3)) {
                    return PathFileObject.forSimplePath(JavacFileManager.this, JavacFileManager.this.fsInfo.getCanonicalFile(var3), var3);
                }
            } catch (InvalidPathException ignored) {
            }

            return null;
        }

        public void close() throws IOException {
        }
    }

    private final class JRTImageContainer implements JavacFileManager.Container {
        private JRTImageContainer() {
        }

        public void list(Path var1, RelativeDirectory var2, Set<Kind> var3, boolean var4, ListBuffer<JavaFileObject> var5) throws IOException {
            try {
                Entry var6 = JavacFileManager.this.getJRTIndex().getEntry(var2);
                if (JavacFileManager.this.symbolFileEnabled && var6.ctSym.hidden) {
                    return;
                }

                Iterator var7 = var6.files.values().iterator();

                while(var7.hasNext()) {
                    Path var8 = (Path)var7.next();
                    if (var3.contains(BaseFileManager.getKind(var8))) {
                        PathFileObject var9 = PathFileObject.forJRTPath(JavacFileManager.this, var8);
                        var5.append(var9);
                    }
                }

                if (var4) {
                    var7 = var6.subdirs.iterator();

                    while(var7.hasNext()) {
                        RelativeDirectory var11 = (RelativeDirectory)var7.next();
                        this.list(var1, var11, var3, var4, var5);
                    }
                }
            } catch (IOException var10) {
                var10.printStackTrace(System.err);
                JavacFileManager.this.log.error("error.reading.file", var1, JavacFileManager.getMessage(var10));
            }

        }

        public JavaFileObject getFileObject(Path var1, RelativeFile var2) throws IOException {
            Entry var3 = JavacFileManager.this.getJRTIndex().getEntry(var2.dirname());
            if (JavacFileManager.this.symbolFileEnabled && var3.ctSym.hidden) {
                return null;
            } else {
                Path var4 = var3.files.get(var2.basename());
                return var4 != null ? PathFileObject.forJRTPath(JavacFileManager.this, var4) : null;
            }
        }

        public void close() throws IOException {
        }
    }

    private interface Container {
        void list(Path var1, RelativeDirectory var2, Set<Kind> var3, boolean var4, ListBuffer<JavaFileObject> var5) throws IOException;

        JavaFileObject getFileObject(Path var1, RelativeFile var2) throws IOException;

        void close() throws IOException;
    }

    protected static enum SortFiles implements Comparator<Path> {
        FORWARD {
            public int compare(Path var1, Path var2) {
                return var1.getFileName().compareTo(var2.getFileName());
            }
        },
        REVERSE {
            public int compare(Path var1, Path var2) {
                return -var1.getFileName().compareTo(var2.getFileName());
            }
        };

        private SortFiles() {
        }
    }
}
