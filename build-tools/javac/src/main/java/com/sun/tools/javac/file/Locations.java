//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.sun.tools.javac.file;

import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.jvm.ModuleNameReader;
import com.sun.tools.javac.jvm.ModuleNameReader.BadClassFile;
import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.resources.CompilerProperties.Errors;
import com.sun.tools.javac.resources.CompilerProperties.Warnings;
import com.sun.tools.javac.util.Iterators;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Pair;
import com.sun.tools.javac.util.StringUtils;
import com.sun.tools.javac.util.JCDiagnostic.Warning;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import javax.tools.StandardLocation;
import javax.tools.JavaFileManager.Location;
import javax.tools.StandardJavaFileManager.PathFactory;

import jdk.internal.jrtfs.JrtFileSystemProvider;

public class Locations {
    private Log log;
    private FSInfo fsInfo;
    private boolean warn;
    private ModuleNameReader moduleNameReader;
    private PathFactory pathFactory = Paths::get;
    static Path javaHome = FileSystems.getDefault().getPath(System.getProperty("java.home"));
    static final Path thisSystemModules;
    Map<Path, FileSystem> fileSystems = new LinkedHashMap<>();
    List<Closeable> closeables = new ArrayList<>();
    private Map<String, String> fsEnv = Collections.emptyMap();
    Map<Location, Locations.LocationHandler> handlersForLocation;
    Map<Option, Locations.LocationHandler> handlersForOption;

    /**
     * Used in an android environment where there's no JDK available
     */
    public static void setJavaHome(Path home) {
        javaHome = home;
    }

    Locations() {
        this.initHandlers();
    }

    Path getPath(String first, String... more) {
        try {
            return this.pathFactory.getPath(first, more);
        } catch (InvalidPathException var4) {
            throw new IllegalArgumentException(var4);
        }
    }

    public void close() throws IOException {
        ListBuffer<IOException> list = new ListBuffer<>();
        this.closeables.forEach((closeable) -> {
            try {
                closeable.close();
            } catch (IOException var3) {
                list.add(var3);
            }

        });
        if (list.nonEmpty()) {
            IOException ex = new IOException();
            Iterator var3 = list.iterator();

            while(var3.hasNext()) {
                IOException e = (IOException)var3.next();
                ex.addSuppressed(e);
            }

            throw ex;
        }
    }

    void update(Log log, boolean warn, FSInfo fsInfo) {
        this.log = log;
        this.warn = warn;
        this.fsInfo = fsInfo;
    }

    void setPathFactory(PathFactory f) {
        this.pathFactory = f;
    }

    boolean isDefaultBootClassPath() {
        Locations.BootClassPathLocationHandler h = (Locations.BootClassPathLocationHandler)this.getHandler(StandardLocation.PLATFORM_CLASS_PATH);
        return h.isDefault();
    }

    private Iterable<Path> getPathEntries(String searchPath) {
        return this.getPathEntries(searchPath, (Path)null);
    }

    private Iterable<Path> getPathEntries(String searchPath, Path emptyPathDefault) {
        ListBuffer<Path> entries = new ListBuffer<>();
        String[] var4 = searchPath.split(Pattern.quote(File.pathSeparator), -1);
        int var5 = var4.length;

        for (String s : var4) {
            if (s.isEmpty()) {
                if (emptyPathDefault != null) {
                    entries.add(emptyPathDefault);
                }
            } else {
                try {
                    entries.add(this.getPath(s));
                } catch (IllegalArgumentException var9) {
                    if (this.warn) {
                        this.log.warning(LintCategory.PATH, Warnings.InvalidPath(s));
                    }
                }
            }
        }

        return entries;
    }

    public void setMultiReleaseValue(String multiReleaseValue) {
        this.fsEnv = Collections.singletonMap("multi-release", multiReleaseValue);
    }

    private boolean contains(Collection<Path> searchPath, Path file) throws IOException {
        if (searchPath == null) {
            return false;
        } else {
            Path enclosingJar = null;
            if (file.getFileSystem().provider() == this.fsInfo.getJarFSProvider()) {
                URI uri = file.toUri();
                if (uri.getScheme().equals("jar")) {
                    String ssp = uri.getSchemeSpecificPart();
                    int sep = ssp.lastIndexOf("!");
                    if (ssp.startsWith("file:") && sep > 0) {
                        enclosingJar = Paths.get(URI.create(ssp.substring(0, sep)));
                    }
                }
            }

            Path nf = normalize(file);
            Iterator<Path> var9 = searchPath.iterator();

            Path np;
            do {
                if (!var9.hasNext()) {
                    return false;
                }

                Path p = (Path)var9.next();
                np = normalize(p);
                if (np.getFileSystem() == nf.getFileSystem() && Files.isDirectory(np, new LinkOption[0]) && nf.startsWith(np)) {
                    return true;
                }
            } while(enclosingJar == null || !Files.isSameFile(enclosingJar, np));

            return true;
        }
    }

    void initHandlers() {
        this.handlersForLocation = new HashMap<>();
        this.handlersForOption = new EnumMap<>(Option.class);
        Locations.BasicLocationHandler[] handlers = new Locations.BasicLocationHandler[]{new Locations.BootClassPathLocationHandler(), new Locations.ClassPathLocationHandler(), new Locations.SimpleLocationHandler(StandardLocation.SOURCE_PATH, new Option[]{Option.SOURCE_PATH}), new Locations.SimpleLocationHandler(StandardLocation.ANNOTATION_PROCESSOR_PATH, new Option[]{Option.PROCESSOR_PATH}), new Locations.SimpleLocationHandler(StandardLocation.ANNOTATION_PROCESSOR_MODULE_PATH, new Option[]{Option.PROCESSOR_MODULE_PATH}), new Locations.OutputLocationHandler(StandardLocation.CLASS_OUTPUT, new Option[]{Option.D}), new Locations.OutputLocationHandler(StandardLocation.SOURCE_OUTPUT, new Option[]{Option.S}), new Locations.OutputLocationHandler(StandardLocation.NATIVE_HEADER_OUTPUT, new Option[]{Option.H}), new Locations.ModuleSourcePathLocationHandler(), new Locations.PatchModulesLocationHandler(), new Locations.ModulePathLocationHandler(StandardLocation.UPGRADE_MODULE_PATH, new Option[]{Option.UPGRADE_MODULE_PATH}), new Locations.ModulePathLocationHandler(StandardLocation.MODULE_PATH, new Option[]{Option.MODULE_PATH}), new Locations.SystemModulesLocationHandler()};
        int var3 = handlers.length;

        for(int var4 = 0; var4 < var3; ++var4) {
            Locations.BasicLocationHandler h = handlers[var4];
            this.handlersForLocation.put(h.location, h);

            for (Option o : h.options) {
                this.handlersForOption.put(o, h);
            }
        }

    }

    boolean handleOption(Option option, String value) {
        Locations.LocationHandler h = (Locations.LocationHandler)this.handlersForOption.get(option);
        return h != null && h.handleOption(option, value);
    }

    boolean hasLocation(Location location) {
        Locations.LocationHandler h = this.getHandler(location);
        return h != null && h.isSet();
    }

    boolean hasExplicitLocation(Location location) {
        Locations.LocationHandler h = this.getHandler(location);
        return h != null && h.isExplicit();
    }

    Collection<Path> getLocation(Location location) {
        Locations.LocationHandler h = this.getHandler(location);
        return h == null ? null : h.getPaths();
    }

    Path getOutputLocation(Location location) {
        if (!location.isOutputLocation()) {
            throw new IllegalArgumentException();
        } else {
            Locations.LocationHandler h = this.getHandler(location);
            return ((Locations.OutputLocationHandler)h).outputDir;
        }
    }

    void setLocation(Location location, Iterable<? extends Path> files) throws IOException {
        Locations.LocationHandler h = this.getHandler(location);
        if (h == null) {
            if (location.isOutputLocation()) {
                h = new Locations.OutputLocationHandler(location);
            } else {
                h = new Locations.SimpleLocationHandler(location);
            }

            this.handlersForLocation.put(location, h);
        }

        ((Locations.LocationHandler)h).setPaths(files);
    }

    Location getLocationForModule(Location location, String name) throws IOException {
        Locations.LocationHandler h = this.getHandler(location);
        return h == null ? null : h.getLocationForModule(name);
    }

    Location getLocationForModule(Location location, Path file) throws IOException {
        Locations.LocationHandler h = this.getHandler(location);
        return h == null ? null : h.getLocationForModule(file);
    }

    void setLocationForModule(Location location, String moduleName, Iterable<? extends Path> files) throws IOException {
        Locations.LocationHandler h = this.getHandler(location);
        if (h == null) {
            if (location.isOutputLocation()) {
                h = new Locations.OutputLocationHandler(location, new Option[0]);
            } else {
                h = new Locations.ModulePathLocationHandler(location, new Option[0]);
            }

            this.handlersForLocation.put(location, h);
        }

        ((Locations.LocationHandler)h).setPathsForModule(moduleName, files);
    }

    String inferModuleName(Location location) {
        Locations.LocationHandler h = this.getHandler(location);
        return h == null ? null : h.inferModuleName();
    }

    Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        Locations.LocationHandler h = this.getHandler(location);
        return h == null ? null : h.listLocationsForModules();
    }

    boolean contains(Location location, Path file) throws IOException {
        Locations.LocationHandler h = this.getHandler(location);
        if (h == null) {
            throw new IllegalArgumentException("unknown location");
        } else {
            return h.contains(file);
        }
    }

    protected Locations.LocationHandler getHandler(Location location) {
        Objects.requireNonNull(location);
        return location instanceof Locations.LocationHandler ? (Locations.LocationHandler)location : (Locations.LocationHandler)this.handlersForLocation.get(location);
    }

    private boolean isArchive(Path file) {
        String n = StringUtils.toLowerCase(file.getFileName().toString());
        return this.fsInfo.isFile(file) && (n.endsWith(".jar") || n.endsWith(".zip"));
    }

    static Path normalize(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException var2) {
            return p.toAbsolutePath().normalize();
        }
    }

    static {
        thisSystemModules = javaHome.resolve("lib").resolve("modules");
    }

    private class PatchModulesLocationHandler extends Locations.BasicLocationHandler {
        private final Locations.ModuleTable moduleTable = Locations.this.new ModuleTable();

        PatchModulesLocationHandler() {
            super(StandardLocation.PATCH_MODULE_PATH, Option.PATCH_MODULE);
        }

        boolean handleOption(Option option, String value) {
            if (!this.options.contains(option)) {
                return false;
            } else {
                this.explicit = true;
                this.moduleTable.clear();
                String[] var3 = value.split("\u0000");
                int var4 = var3.length;

                for(int var5 = 0; var5 < var4; ++var5) {
                    String v = var3[var5];
                    int eq = v.indexOf(61);
                    if (eq > 0) {
                        String moduleName = v.substring(0, eq);
                        Locations.SearchPath mPatchPath = (Locations.this.new SearchPath()).addFiles(v.substring(eq + 1));
                        String name = this.location.getName() + "[" + moduleName + "]";
                        Locations.ModuleLocationHandler h = Locations.this.new ModuleLocationHandler(this, name, moduleName, mPatchPath, false);
                        this.moduleTable.add(h);
                    } else {
                        Locations.this.log.error(Errors.LocnInvalidArgForXpatch(value));
                    }
                }

                return true;
            }
        }

        boolean isSet() {
            return !this.moduleTable.isEmpty();
        }

        Collection<Path> getPaths() {
            throw new UnsupportedOperationException();
        }

        void setPaths(Iterable<? extends Path> files) throws IOException {
            throw new UnsupportedOperationException();
        }

        void setPathsForModule(String moduleName, Iterable<? extends Path> files) throws IOException {
            throw new UnsupportedOperationException();
        }

        Location getLocationForModule(String name) throws IOException {
            return this.moduleTable.get(name);
        }

        Location getLocationForModule(Path file) throws IOException {
            return this.moduleTable.get(file);
        }

        Iterable<Set<Location>> listLocationsForModules() throws IOException {
            return Collections.singleton(this.moduleTable.locations());
        }

        boolean contains(Path file) throws IOException {
            return this.moduleTable.contains(file);
        }
    }

    private class SystemModulesLocationHandler extends Locations.BasicLocationHandler {
        private Path systemJavaHome;
        private Path modules;
        private Locations.ModuleTable moduleTable;

        SystemModulesLocationHandler() {
            super(StandardLocation.SYSTEM_MODULES, Option.SYSTEM);
            this.systemJavaHome = Locations.javaHome;
        }

        boolean handleOption(Option option, String value) {
            if (!this.options.contains(option)) {
                return false;
            } else {
                this.explicit = true;
                if (value == null) {
                    this.systemJavaHome = Locations.javaHome;
                } else if (value.equals("none")) {
                    this.systemJavaHome = null;
                } else {
                    this.update(Locations.this.getPath(value));
                }

                this.modules = null;
                return true;
            }
        }

        Collection<Path> getPaths() {
            return this.systemJavaHome == null ? null : Collections.singleton(this.systemJavaHome);
        }

        void setPaths(Iterable<? extends Path> files) throws IOException {
            if (files == null) {
                this.systemJavaHome = null;
            } else {
                this.explicit = true;
                Path dir = this.checkSingletonDirectory(files);
                this.update(dir);
            }

        }

        void setPathsForModule(String name, Iterable<? extends Path> paths) throws IOException {
            List<Path> checkedPaths = this.checkPaths(paths);
            this.initSystemModules();
            Locations.ModuleLocationHandler l = this.moduleTable.get(name);
            if (l == null) {
                l = Locations.this.new ModuleLocationHandler(this, this.location.getName() + "[" + name + "]", name, checkedPaths, true);
                this.moduleTable.add(l);
            } else {
                l.searchPath = checkedPaths;
                this.moduleTable.updatePaths(l);
            }

            this.explicit = true;
        }

        private List<Path> checkPaths(Iterable<? extends Path> paths) throws IOException {
            Objects.requireNonNull(paths);
            List<Path> validPaths = new ArrayList();
            Iterator var3 = paths.iterator();

            while(var3.hasNext()) {
                Path p = (Path)var3.next();
                validPaths.add(this.checkDirectory(p));
            }

            return validPaths;
        }

        private void update(Path p) {
            if (!this.isCurrentPlatform(p) && !Files.exists(p.resolve("lib").resolve("jrt-fs.jar"), new LinkOption[0]) && !Files.exists(this.systemJavaHome.resolve("modules"), new LinkOption[0])) {
                throw new IllegalArgumentException(p.toString());
            } else {
                this.systemJavaHome = p;
                this.modules = null;
            }
        }

        private boolean isCurrentPlatform(Path p) {
            try {
                return Files.isSameFile(p, Locations.javaHome);
            } catch (IOException var3) {
                throw new IllegalArgumentException(p.toString(), var3);
            }
        }

        Location getLocationForModule(String name) throws IOException {
            this.initSystemModules();
            return this.moduleTable.get(name);
        }

        Location getLocationForModule(Path file) throws IOException {
            this.initSystemModules();
            return this.moduleTable.get(file);
        }

        Iterable<Set<Location>> listLocationsForModules() throws IOException {
            this.initSystemModules();
            return Collections.singleton(this.moduleTable.locations());
        }

        boolean contains(Path file) throws IOException {
            this.initSystemModules();
            return this.moduleTable.contains(file);
        }

        private void initSystemModules() throws IOException {
            if (this.moduleTable == null) {
                if (this.systemJavaHome == null) {
                    this.moduleTable = Locations.this.new ModuleTable();
                } else {
                    if (this.modules == null) {
                        try {
                            URI jrtURI = URI.create("jrt:/");
                            FileSystem jrtfs;
                            if (this.isCurrentPlatform(this.systemJavaHome)) {
                                Map<String, String> attrMap = Collections.singletonMap("java.home", this.systemJavaHome.toString());
                                jrtfs = new JrtFileSystemProvider().newFileSystem(jrtURI, attrMap);
                            } else {
                                try {
                                    Map<String, String> attrMap = Collections.singletonMap("java.home", this.systemJavaHome.toString());
                                    jrtfs = FileSystems.newFileSystem(jrtURI, attrMap);
                                } catch (ProviderNotFoundException var8) {
                                    URL javaHomeURL = this.systemJavaHome.resolve("jrt-fs.jar").toUri().toURL();
                                    ClassLoader currentLoader = Locations.class.getClassLoader();
                                    URLClassLoader fsLoader = new URLClassLoader(new URL[]{javaHomeURL}, currentLoader);
                                    jrtfs = FileSystems.newFileSystem(jrtURI, Collections.emptyMap(), fsLoader);
                                    Locations.this.closeables.add(fsLoader);
                                }

                                Locations.this.closeables.add(jrtfs);
                            }

                            this.modules = jrtfs.getPath("/modules");
                        } catch (ProviderNotFoundException | FileSystemNotFoundException var10) {
                            this.modules = this.systemJavaHome.resolve("modules");
                            if (!Files.exists(this.modules, new LinkOption[0])) {
                                throw new IOException("can't find system classes", var10);
                            }
                        }
                    }

                    this.moduleTable = Locations.this.new ModuleTable();
                    DirectoryStream stream = Files.newDirectoryStream(this.modules, (x$0) -> {
                        return Files.isDirectory(x$0, new LinkOption[0]);
                    });

                    try {
                        Iterator var13 = stream.iterator();

                        while(var13.hasNext()) {
                            Path entry = (Path)var13.next();
                            String moduleName = entry.getFileName().toString();
                            String name = this.location.getName() + "[" + moduleName + "]";
                            Locations.ModuleLocationHandler h = Locations.this.new ModuleLocationHandler(this, name, moduleName, Collections.singletonList(entry), false);
                            this.moduleTable.add(h);
                        }
                    } catch (Throwable var9) {
                        if (stream != null) {
                            try {
                                stream.close();
                            } catch (Throwable var7) {
                                var9.addSuppressed(var7);
                            }
                        }

                        throw var9;
                    }

                    if (stream != null) {
                        stream.close();
                    }

                }
            }
        }
    }

    private class ModuleSourcePathLocationHandler extends Locations.BasicLocationHandler {
        private Locations.ModuleTable moduleTable;
        private List<Path> paths;
        private final Predicate<Path> checkModuleInfo = (p) -> {
            return Files.exists(p.resolve("module-info.java"), new LinkOption[0]);
        };

        ModuleSourcePathLocationHandler() {
            super(StandardLocation.MODULE_SOURCE_PATH, Option.MODULE_SOURCE_PATH);
        }

        boolean handleOption(Option option, String value) {
            this.explicit = true;
            this.init(value);
            return true;
        }

        void init(String value) {
            Collection<String> segments = new ArrayList<>();
            String[] var3 = value.split(File.pathSeparator);
            int var4 = var3.length;

            String MARKER;
            for(int var5 = 0; var5 < var4; ++var5) {
                MARKER = var3[var5];
                this.expandBraces(MARKER, segments);
            }

            Map<String, List<Path>> map = new LinkedHashMap<>();
            List<Path> noSuffixPaths = new ArrayList();
            boolean anySuffix = false;
            MARKER = "*";
            Iterator var7 = segments.iterator();

            while(true) {
                while(var7.hasNext()) {
                    String seg = (String)var7.next();
                    int markStart = seg.indexOf("*");
                    Path prefix;
                    if (markStart != -1) {
                        if (markStart == 0 || !this.isSeparator(seg.charAt(markStart - 1))) {
                            throw new IllegalArgumentException("illegal use of * in " + seg);
                        }

                        prefix = Locations.this.getPath(seg.substring(0, markStart - 1));
                        int markEnd = markStart + "*".length();
                        Path suffix;
                        if (markEnd == seg.length()) {
                            suffix = null;
                        } else {
                            if (!this.isSeparator(seg.charAt(markEnd)) || seg.indexOf("*", markEnd) != -1) {
                                throw new IllegalArgumentException("illegal use of * in " + seg);
                            }

                            suffix = Locations.this.getPath(seg.substring(markEnd + 1));
                            anySuffix = true;
                        }

                        this.add(map, prefix, suffix);
                        if (suffix == null) {
                            noSuffixPaths.add(prefix);
                        }
                    } else {
                        prefix = Locations.this.getPath(seg);
                        this.add(map, prefix, (Path)null);
                        noSuffixPaths.add(prefix);
                    }
                }

                this.initModuleTable(map);
                this.paths = anySuffix ? null : noSuffixPaths;
                return;
            }
        }

        private void initModuleTable(Map<String, List<Path>> map) {
            this.moduleTable = Locations.this.new ModuleTable();
            map.forEach((modName, modPath) -> {
                boolean hasModuleInfo = modPath.stream().anyMatch(this.checkModuleInfo);
                if (hasModuleInfo) {
                    String locnName = this.location.getName() + "[" + modName + "]";
                    Locations.ModuleLocationHandler l = Locations.this.new ModuleLocationHandler(this, locnName, modName, modPath, false);
                    this.moduleTable.add(l);
                }

            });
        }

        private boolean isSeparator(char ch) {
            return ch == File.separatorChar || ch == '/';
        }

        void add(Map<String, List<Path>> map, Path prefix, Path suffix) {
            if (!Files.isDirectory(prefix, new LinkOption[0])) {
                if (Locations.this.warn) {
                    Warning key = Files.exists(prefix, new LinkOption[0]) ? Warnings.DirPathElementNotDirectory(prefix) : Warnings.DirPathElementNotFound(prefix);
                    Locations.this.log.warning(LintCategory.PATH, key);
                }

            } else {
                try {
                    DirectoryStream stream = Files.newDirectoryStream(prefix, (pathx) -> {
                        return Files.isDirectory(pathx, new LinkOption[0]);
                    });

                    try {
                        Iterator var5 = stream.iterator();

                        while(var5.hasNext()) {
                            Path entry = (Path)var5.next();
                            Path path = suffix == null ? entry : entry.resolve(suffix);
                            if (Files.isDirectory(path, new LinkOption[0])) {
                                String name = entry.getFileName().toString();
                                List<Path> paths = (List)map.get(name);
                                if (paths == null) {
                                    map.put(name, paths = new ArrayList());
                                }

                                ((List)paths).add(path);
                            }
                        }
                    } catch (Throwable var11) {
                        if (stream != null) {
                            try {
                                stream.close();
                            } catch (Throwable var10) {
                                var11.addSuppressed(var10);
                            }
                        }

                        throw var11;
                    }

                    if (stream != null) {
                        stream.close();
                    }
                } catch (IOException var12) {
                    System.err.println(var12);
                }

            }
        }

        private void expandBraces(String value, Collection<String> results) {
            int depth = 0;
            int start = -1;
            String prefix = null;
            String suffix = null;

            for(int i = 0; i < value.length(); ++i) {
                String elem;
                switch(value.charAt(i)) {
                    case ',':
                        if (depth == 1) {
                            elem = value.substring(start, i);
                            this.expandBraces(prefix + elem + suffix, results);
                            start = i + 1;
                        }
                        break;
                    case '{':
                        ++depth;
                        if (depth == 1) {
                            prefix = value.substring(0, i);
                            suffix = value.substring(this.getMatchingBrace(value, i) + 1);
                            start = i + 1;
                        }
                        break;
                    case '}':
                        switch(depth) {
                            case 0:
                                throw new IllegalArgumentException("mismatched braces");
                            case 1:
                                elem = value.substring(start, i);
                                this.expandBraces(prefix + elem + suffix, results);
                                return;
                            default:
                                --depth;
                        }
                }
            }

            if (depth > 0) {
                throw new IllegalArgumentException("mismatched braces");
            } else {
                results.add(value);
            }
        }

        int getMatchingBrace(String value, int offset) {
            int depth = 1;

            for(int i = offset + 1; i < value.length(); ++i) {
                switch(value.charAt(i)) {
                    case '{':
                        ++depth;
                        break;
                    case '}':
                        --depth;
                        if (depth == 0) {
                            return i;
                        }
                }
            }

            throw new IllegalArgumentException("mismatched braces");
        }

        boolean isSet() {
            return this.moduleTable != null;
        }

        Collection<Path> getPaths() {
            if (this.paths == null) {
                throw new IllegalStateException("paths not available");
            } else {
                return this.paths;
            }
        }

        void setPaths(Iterable<? extends Path> files) throws IOException {
            Map<String, List<Path>> map = new LinkedHashMap();
            List<Path> newPaths = new ArrayList();
            Iterator var4 = files.iterator();

            while(var4.hasNext()) {
                Path file = (Path)var4.next();
                this.add(map, file, (Path)null);
                newPaths.add(file);
            }

            this.initModuleTable(map);
            this.explicit = true;
            this.paths = Collections.unmodifiableList(newPaths);
        }

        void setPathsForModule(String name, Iterable<? extends Path> paths) throws IOException {
            List<Path> validPaths = this.checkPaths(paths);
            if (this.moduleTable == null) {
                this.moduleTable = Locations.this.new ModuleTable();
            }

            Locations.ModuleLocationHandler l = this.moduleTable.get(name);
            if (l == null) {
                l = Locations.this.new ModuleLocationHandler(this, this.location.getName() + "[" + name + "]", name, validPaths, true);
                this.moduleTable.add(l);
            } else {
                l.searchPath = validPaths;
                this.moduleTable.updatePaths(l);
            }

            this.explicit = true;
        }

        private List<Path> checkPaths(Iterable<? extends Path> paths) throws IOException {
            Objects.requireNonNull(paths);
            List<Path> validPaths = new ArrayList();
            Iterator var3 = paths.iterator();

            while(var3.hasNext()) {
                Path p = (Path)var3.next();
                validPaths.add(this.checkDirectory(p));
            }

            return validPaths;
        }

        Location getLocationForModule(String name) {
            return this.moduleTable == null ? null : this.moduleTable.get(name);
        }

        Location getLocationForModule(Path file) {
            return this.moduleTable == null ? null : this.moduleTable.get(file);
        }

        Iterable<Set<Location>> listLocationsForModules() {
            return this.moduleTable == null ? Collections.emptySet() : Collections.singleton(this.moduleTable.locations());
        }

        boolean contains(Path file) throws IOException {
            return this.moduleTable == null ? false : this.moduleTable.contains(file);
        }
    }

    private class ModulePathLocationHandler extends Locations.SimpleLocationHandler {
        private Locations.ModuleTable moduleTable;

        ModulePathLocationHandler(Location location, Option... options) {
            super(location, options);
        }

        public boolean handleOption(Option option, String value) {
            if (!this.options.contains(option)) {
                return false;
            } else {
                this.setPaths(value == null ? null : Locations.this.getPathEntries(value));
                return true;
            }
        }

        public Location getLocationForModule(String moduleName) {
            this.initModuleLocations();
            return this.moduleTable.get(moduleName);
        }

        public Location getLocationForModule(Path file) {
            this.initModuleLocations();
            return this.moduleTable.get(file);
        }

        Iterable<Set<Location>> listLocationsForModules() {
            Set<Location> explicitLocations = this.moduleTable != null ? this.moduleTable.explicitLocations() : Collections.emptySet();
            Iterable<Set<Location>> explicitLocationsList = !explicitLocations.isEmpty() ? Collections.singletonList(explicitLocations) : Collections.emptyList();
            if (this.searchPath == null) {
                return explicitLocationsList;
            } else {
                Iterable<Set<Location>> searchPathLocations = () -> {
                    return new Locations.ModulePathLocationHandler.ModulePathIterator();
                };
                return () -> {
                    return Iterators.createCompoundIterator(Arrays.asList(explicitLocationsList, searchPathLocations), Iterable::iterator);
                };
            }
        }

        boolean contains(Path file) throws IOException {
            if (this.moduleTable == null) {
                this.initModuleLocations();
            }

            return this.moduleTable.contains(file);
        }

        void setPaths(Iterable<? extends Path> paths) {
            if (paths != null) {
                Iterator var2 = paths.iterator();

                while(var2.hasNext()) {
                    Path p = (Path)var2.next();
                    this.checkValidModulePathEntry(p);
                }
            }

            super.setPaths(paths);
            this.moduleTable = null;
        }

        void setPathsForModule(String name, Iterable<? extends Path> paths) throws IOException {
            List<Path> checkedPaths = this.checkPaths(paths);
            this.initModuleLocations();
            Locations.ModuleLocationHandler l = this.moduleTable.get(name);
            if (l == null) {
                l = Locations.this.new ModuleLocationHandler(this, this.location.getName() + "[" + name + "]", name, checkedPaths, true);
                this.moduleTable.add(l);
            } else {
                l.searchPath = checkedPaths;
                this.moduleTable.updatePaths(l);
            }

            l.explicit = true;
            this.explicit = true;
        }

        private List<Path> checkPaths(Iterable<? extends Path> paths) throws IOException {
            Objects.requireNonNull(paths);
            List<Path> validPaths = new ArrayList<>();

            for (Path p : paths) {
                validPaths.add(this.checkDirectory(p));
            }

            return validPaths;
        }

        private void initModuleLocations() {
            if (this.moduleTable == null) {
                this.moduleTable = Locations.this.new ModuleTable();

                for (Set<Location> locations : this.listLocationsForModules()) {
                    Set<Location> set = (Set) locations;

                    for (Location locn : set) {
                        if (locn instanceof ModuleLocationHandler) {
                            ModuleLocationHandler l = (ModuleLocationHandler) locn;
                            if (!this.moduleTable.nameMap.containsKey(l.moduleName)) {
                                this.moduleTable.add(l);
                            }
                        }
                    }
                }

            }
        }

        private void checkValidModulePathEntry(Path p) {
            if (Files.exists(p)) {
                if (!Files.isDirectory(p)) {
                    String name = p.getFileName().toString();
                    int lastDot = name.lastIndexOf(".");
                    if (lastDot > 0) {
                        String var4 = name.substring(lastDot);
                        byte var5 = -1;
                        switch(var4.hashCode()) {
                            case 1475373:
                                if (var4.equals(".jar")) {
                                    var5 = 0;
                                }
                                break;
                            case 45748102:
                                if (var4.equals(".jmod")) {
                                    var5 = 1;
                                }
                        }

                        switch(var5) {
                            case 0:
                            case 1:
                                return;
                        }
                    }

                    throw new IllegalArgumentException(p.toString());
                }
            }
        }

        private boolean isModuleName(String name) {
            int next;
            int off;
            String id;
            for(off = 0; (next = name.indexOf(46, off)) != -1; off = next + 1) {
                id = name.substring(off, next);
                if (!SourceVersion.isName(id)) {
                    return false;
                }
            }

            id = name.substring(off);
            return SourceVersion.isName(id);
        }

        class ModulePathIterator implements Iterator<Set<Location>> {
            Iterator<Path> pathIter;
            int pathIndex;
            Set<Location> next;

            ModulePathIterator() {
                this.pathIter = ModulePathLocationHandler.this.searchPath.iterator();
                this.pathIndex = 0;
                this.next = null;
            }

            public boolean hasNext() {
                if (this.next != null) {
                    return true;
                } else {
                    for(; this.next == null; ++this.pathIndex) {
                        if (!this.pathIter.hasNext()) {
                            return false;
                        }

                        Path path = (Path)this.pathIter.next();
                        if (Files.isDirectory(path)) {
                            this.next = this.scanDirectory(path);
                        } else {
                            this.next = this.scanFile(path);
                        }
                    }

                    return true;
                }
            }

            public Set<Location> next() {
                this.hasNext();
                if (this.next != null) {
                    Set<Location> result = this.next;
                    this.next = null;
                    return result;
                } else {
                    throw new NoSuchElementException();
                }
            }

            private Set<Location> scanDirectory(Path path) {
                Set<Path> paths = new LinkedHashSet();
                Path moduleInfoClass = null;

                try {
                    DirectoryStream<Path> stream = Files.newDirectoryStream(path);

                    try {

                        for (Path entry : stream) {
                            if (entry.endsWith("module-info.class")) {
                                moduleInfoClass = entry;
                                break;
                            }

                            paths.add(entry);
                        }
                    } catch (Throwable var16) {
                        if (stream != null) {
                            try {
                                stream.close();
                            } catch (Throwable var13) {
                                var16.addSuppressed(var13);
                            }
                        }

                        throw var16;
                    }

                    stream.close();
                } catch (IOException | DirectoryIteratorException var17) {
                    Locations.this.log.error(Errors.LocnCantReadDirectory(path));
                    return Collections.emptySet();
                }

                if (moduleInfoClass != null) {
                    try {
                        String moduleNamex = this.readModuleName(moduleInfoClass);
                        String namex = ModulePathLocationHandler.this.location.getName() + "[" + this.pathIndex + ":" + moduleNamex + "]";
                        Locations.ModuleLocationHandler l = Locations.this.new ModuleLocationHandler(ModulePathLocationHandler.this, namex, moduleNamex, Collections.singletonList(path), false);
                        return Collections.singleton(l);
                    } catch (BadClassFile var14) {
                        Locations.this.log.error(Errors.LocnBadModuleInfo(path));
                        return Collections.emptySet();
                    } catch (IOException var15) {
                        Locations.this.log.error(Errors.LocnCantReadFile(path));
                        return Collections.emptySet();
                    }
                } else {
                    Set<Location> result = new LinkedHashSet<>();
                    int index = 0;

                    for (Path entryx : paths) {
                        Pair<String, Path> module = this.inferModuleName(entryx);
                        if (module != null) {
                            String moduleName = (String) module.fst;
                            Path modulePath = (Path) module.snd;
                            String name =
                                    ModulePathLocationHandler.this.location.getName() + "[" + this.pathIndex + "." + index++ + ":" + moduleName + "]";
                            ModuleLocationHandler lx =
                                    Locations.this.new ModuleLocationHandler(ModulePathLocationHandler.this, name, moduleName, Collections.singletonList(modulePath), false);
                            result.add(lx);
                        }
                    }

                    return result;
                }
            }

            private Set<Location> scanFile(Path path) {
                Pair<String, Path> module = this.inferModuleName(path);
                if (module == null) {
                    return Collections.emptySet();
                } else {
                    String moduleName = (String)module.fst;
                    Path modulePath = (Path)module.snd;
                    String name = ModulePathLocationHandler.this.location.getName() + "[" + this.pathIndex + ":" + moduleName + "]";
                    Locations.ModuleLocationHandler l = Locations.this.new ModuleLocationHandler(ModulePathLocationHandler.this, name, moduleName, Collections.singletonList(modulePath), false);
                    return Collections.singleton(l);
                }
            }

            private Pair<String, Path> inferModuleName(Path p) {
                if (Files.isDirectory(p, new LinkOption[0])) {
                    if (Files.exists(p.resolve("module-info.class"), new LinkOption[0]) || Files.exists(p.resolve("module-info.sig"), new LinkOption[0])) {
                        String name = p.getFileName().toString();
                        if (SourceVersion.isName(name)) {
                            return new Pair(name, p);
                        }
                    }

                    return null;
                } else {
                    Path moduleInfoClass;
                    String moduleName;
                    if (p.getFileName().toString().endsWith(".jar") && Locations.this.fsInfo.exists(p)) {
                        FileSystemProvider jarFSProviderx = Locations.this.fsInfo.getJarFSProvider();
                        if (jarFSProviderx == null) {
                            Locations.this.log.error(Errors.NoZipfsForArchive(p));
                            return null;
                        } else {
                            try {
                                FileSystem fsx = jarFSProviderx.newFileSystem(p, Locations.this.fsEnv);

                                label369: {
                                    Pair var39;
                                    label370: {
                                        Pair var10;
                                        label371: {
                                            try {
                                                label372: {
                                                    moduleInfoClass = fsx.getPath("module-info.class");
                                                    if (Files.exists(moduleInfoClass, new LinkOption[0])) {
                                                        moduleName = this.readModuleName(moduleInfoClass);
                                                        var39 = new Pair(moduleName, p);
                                                        break label370;
                                                    }

                                                    Path mf = fsx.getPath("META-INF/MANIFEST.MF");
                                                    if (!Files.exists(mf, new LinkOption[0])) {
                                                        break label369;
                                                    }

                                                    InputStream in = Files.newInputStream(mf);

                                                    label373: {
                                                        label374: {
                                                            try {
                                                                Manifest man = new Manifest(in);
                                                                Attributes attrs = man.getMainAttributes();
                                                                if (attrs == null) {
                                                                    break label373;
                                                                }

                                                                String moduleNamex = attrs.getValue(new Name("Automatic-Module-Name"));
                                                                if (moduleNamex == null) {
                                                                    break label373;
                                                                }

                                                                if (!ModulePathLocationHandler.this.isModuleName(moduleNamex)) {
                                                                    Locations.this.log.error(Errors.LocnCantGetModuleNameForJar(p));
                                                                    var10 = null;
                                                                    break label374;
                                                                }

                                                                var10 = new Pair(moduleNamex, p);
                                                            } catch (Throwable var23) {
                                                                if (in != null) {
                                                                    try {
                                                                        in.close();
                                                                    } catch (Throwable var22) {
                                                                        var23.addSuppressed(var22);
                                                                    }
                                                                }

                                                                throw var23;
                                                            }

                                                            if (in != null) {
                                                                in.close();
                                                            }
                                                            break label371;
                                                        }

                                                        if (in != null) {
                                                            in.close();
                                                        }
                                                        break label372;
                                                    }

                                                    if (in != null) {
                                                        in.close();
                                                    }
                                                    break label369;
                                                }
                                            } catch (Throwable var24) {
                                                if (fsx != null) {
                                                    try {
                                                        fsx.close();
                                                    } catch (Throwable var21) {
                                                        var24.addSuppressed(var21);
                                                    }
                                                }

                                                throw var24;
                                            }

                                            fsx.close();

                                            return var10;
                                        }

                                        fsx.close();

                                        return var10;
                                    }

                                    fsx.close();

                                    return var39;
                                }

                                fsx.close();
                            } catch (BadClassFile var25) {
                                Locations.this.log.error(Errors.LocnBadModuleInfo(p));
                                return null;
                            } catch (IOException var26) {
                                Locations.this.log.error(Errors.LocnCantReadFile(p));
                                return null;
                            }

                            String fn = p.getFileName().toString();
                            String mn = fn.substring(0, fn.length() - 4);
                            Matcher matcher = Pattern.compile("-(\\d+(\\.|$))").matcher(mn);
                            if (matcher.find()) {
                                int start = matcher.start();
                                mn = mn.substring(0, start);
                            }

                            mn = mn.replaceAll("[^A-Za-z0-9]", ".").replaceAll("(\\.)(\\1)+", ".").replaceAll("^\\.", "").replaceAll("\\.$", "");
                            if (!mn.isEmpty()) {
                                return new Pair(mn, p);
                            } else {
                                Locations.this.log.error(Errors.LocnCantGetModuleNameForJar(p));
                                return null;
                            }
                        }
                    } else {
                        if (p.getFileName().toString().endsWith(".jmod")) {
                            try {
                                FileSystem fs = (FileSystem)Locations.this.fileSystems.get(p);
                                if (fs == null) {
                                    FileSystemProvider jarFSProvider = Locations.this.fsInfo.getJarFSProvider();
                                    if (jarFSProvider == null) {
                                        Locations.this.log.error(Errors.LocnCantReadFile(p));
                                        return null;
                                    }

                                    fs = jarFSProvider.newFileSystem(p, Collections.emptyMap());

                                    Pair<String, Path> var7;
                                    try {
                                        moduleInfoClass = fs.getPath("classes/module-info.class");
                                        moduleName = this.readModuleName(moduleInfoClass);
                                        Path modulePath = fs.getPath("classes");
                                        Locations.this.fileSystems.put(p, fs);
                                        Locations.this.closeables.add(fs);
                                        fs = null;
                                        var7 = new Pair<>(moduleName, modulePath);
                                    } finally {
                                        if (fs != null) {
                                            fs.close();
                                        }

                                    }

                                    return var7;
                                }
                            } catch (BadClassFile var28) {
                                Locations.this.log.error(Errors.LocnBadModuleInfo(p));
                            } catch (IOException var29) {
                                Locations.this.log.error(Errors.LocnCantReadFile(p));
                                return null;
                            }
                        }

                        return null;
                    }
                }
            }

            private String readModuleName(Path path) throws IOException, BadClassFile {
                if (Locations.this.moduleNameReader == null) {
                    Locations.this.moduleNameReader = new ModuleNameReader();
                }

                return Locations.this.moduleNameReader.readModuleName(path);
            }
        }
    }

    private class ModuleTable {
        private final Map<String, Locations.ModuleLocationHandler> nameMap = new LinkedHashMap<>();
        private final Map<Path, Locations.ModuleLocationHandler> pathMap = new LinkedHashMap<>();

        private ModuleTable() {
        }

        void add(Locations.ModuleLocationHandler h) {
            this.nameMap.put(h.moduleName, h);

            for (Path p : h.searchPath) {
                this.pathMap.put(Locations.normalize(p), h);
            }

        }

        void updatePaths(Locations.ModuleLocationHandler h) {

            this.pathMap.entrySet().removeIf(e -> e.getValue() == h);

            for (Path path : h.searchPath) {
                this.pathMap.put(Locations.normalize(path), h);
            }

        }

        Locations.ModuleLocationHandler get(String name) {
            return (Locations.ModuleLocationHandler)this.nameMap.get(name);
        }

        Locations.ModuleLocationHandler get(Path path) {
            while(path != null) {
                Locations.ModuleLocationHandler l = (Locations.ModuleLocationHandler)this.pathMap.get(path);
                if (l != null) {
                    return l;
                }

                path = path.getParent();
            }

            return null;
        }

        void clear() {
            this.nameMap.clear();
            this.pathMap.clear();
        }

        boolean isEmpty() {
            return this.nameMap.isEmpty();
        }

        boolean contains(Path file) throws IOException {
            return Locations.this.contains(this.pathMap.keySet(), file);
        }

        Set<Location> locations() {
            return Collections.unmodifiableSet(new HashSet<>(this.nameMap.values()));
        }

        Set explicitLocations() {
            return Collections.unmodifiableSet(this.nameMap.entrySet().stream().filter((e) -> ((ModuleLocationHandler)e.getValue()).explicit).map((e) -> (ModuleLocationHandler)e.getValue()).collect(Collectors.toSet()));
        }
    }

    private class ModuleLocationHandler extends Locations.LocationHandler implements Location {
        private final Locations.LocationHandler parent;
        private final String name;
        private final String moduleName;
        private final boolean output;
        boolean explicit;
        Collection<Path> searchPath;

        ModuleLocationHandler(Locations.LocationHandler parent, String name, String moduleName, Collection<Path> searchPath, boolean output) {
            this.parent = parent;
            this.name = name;
            this.moduleName = moduleName;
            this.searchPath = searchPath;
            this.output = output;
        }

        public String getName() {
            return this.name;
        }

        public boolean isOutputLocation() {
            return this.output;
        }

        boolean handleOption(Option option, String value) {
            throw new UnsupportedOperationException();
        }

        Collection<Path> getPaths() {
            return Collections.unmodifiableCollection(this.searchPath);
        }

        boolean isExplicit() {
            return true;
        }

        void setPaths(Iterable<? extends Path> paths) throws IOException {
            this.parent.setPathsForModule(this.moduleName, paths);
        }

        void setPathsForModule(String moduleName, Iterable<? extends Path> paths) {
            throw new UnsupportedOperationException("not supported for " + this.name);
        }

        String inferModuleName() {
            return this.moduleName;
        }

        boolean contains(Path file) throws IOException {
            return Locations.this.contains(this.searchPath, file);
        }

        public String toString() {
            return this.name;
        }
    }

    private class BootClassPathLocationHandler extends Locations.BasicLocationHandler {
        private Collection<Path> searchPath;
        final Map<Option, String> optionValues = new EnumMap<>(Option.class);
        private boolean isDefault;

        BootClassPathLocationHandler() {
            super(StandardLocation.PLATFORM_CLASS_PATH, Option.BOOT_CLASS_PATH, Option.XBOOTCLASSPATH, Option.XBOOTCLASSPATH_PREPEND, Option.XBOOTCLASSPATH_APPEND, Option.ENDORSEDDIRS, Option.DJAVA_ENDORSED_DIRS, Option.EXTDIRS, Option.DJAVA_EXT_DIRS);
        }

        boolean isDefault() {
            this.lazy();
            return this.isDefault;
        }

        boolean handleOption(Option option, String value) {
            if (!this.options.contains(option)) {
                return false;
            } else {
                this.explicit = true;
                option = this.canonicalize(option);
                this.optionValues.put(option, value);
                if (option == Option.BOOT_CLASS_PATH) {
                    this.optionValues.remove(Option.XBOOTCLASSPATH_PREPEND);
                    this.optionValues.remove(Option.XBOOTCLASSPATH_APPEND);
                }

                this.searchPath = null;
                return true;
            }
        }

        private Option canonicalize(Option option) {
            switch(option) {
                case XBOOTCLASSPATH:
                    return Option.BOOT_CLASS_PATH;
                case DJAVA_ENDORSED_DIRS:
                    return Option.ENDORSEDDIRS;
                case DJAVA_EXT_DIRS:
                    return Option.EXTDIRS;
                default:
                    return option;
            }
        }

        Collection<Path> getPaths() {
            this.lazy();
            return this.searchPath;
        }

        void setPaths(Iterable<? extends Path> files) {
            if (files == null) {
                this.searchPath = null;
            } else {
                this.isDefault = false;
                this.explicit = true;
                Locations.SearchPath p = (Locations.this.new SearchPath()).addFiles(files, false);
                this.searchPath = Collections.unmodifiableCollection(p);
                this.optionValues.clear();
            }

        }

        Locations.SearchPath computePath() throws IOException {
            Locations.SearchPath path = Locations.this.new SearchPath();
            String bootclasspathOpt = (String)this.optionValues.get(Option.BOOT_CLASS_PATH);
            String endorseddirsOpt = (String)this.optionValues.get(Option.ENDORSEDDIRS);
            String extdirsOpt = (String)this.optionValues.get(Option.EXTDIRS);
            String xbootclasspathPrependOpt = (String)this.optionValues.get(Option.XBOOTCLASSPATH_PREPEND);
            String xbootclasspathAppendOpt = (String)this.optionValues.get(Option.XBOOTCLASSPATH_APPEND);
            path.addFiles(xbootclasspathPrependOpt);
            if (endorseddirsOpt != null) {
                path.addDirectories(endorseddirsOpt);
            } else {
                path.addDirectories(System.getProperty("java.endorsed.dirs"), false);
            }

            if (bootclasspathOpt != null) {
                path.addFiles(bootclasspathOpt);
            } else {
                Collection<Path> systemClasses = this.systemClasses();
                if (systemClasses != null) {
                    path.addFiles(systemClasses, false);
                } else {
                    String files = System.getProperty("sun.boot.class.path");
                    path.addFiles(files, false);
                }
            }

            path.addFiles(xbootclasspathAppendOpt);
            if (extdirsOpt != null) {
                path.addDirectories(extdirsOpt);
            } else {
                Path jfxrt = Locations.javaHome.resolve("lib/jfxrt.jar");
                if (Files.exists(jfxrt)) {
                    path.addFile(jfxrt, false);
                }

                path.addDirectories(System.getProperty("java.ext.dirs"), false);
            }

            this.isDefault = xbootclasspathPrependOpt == null && bootclasspathOpt == null && xbootclasspathAppendOpt == null;
            return path;
        }

        private Collection<Path> systemClasses() throws IOException {
            if (Files.isRegularFile(Locations.thisSystemModules)) {
                return Collections.singleton(Locations.thisSystemModules);
            } else {
                Path modules = Locations.javaHome.resolve("modules");
                if (Files.isDirectory(modules.resolve("java.base"))) {
                    Stream<Path> listedModules = Files.list(modules);

                    Collection<Path> var3;
                    try {
                        var3 = listedModules.collect(Collectors.toList());
                    } catch (Throwable var6) {
                        if (listedModules != null) {
                            try {
                                listedModules.close();
                            } catch (Throwable var5) {
                                var6.addSuppressed(var5);
                            }
                        }

                        throw var6;
                    }

                    listedModules.close();

                    return var3;
                } else {
                    return null;
                }
            }
        }

        private void lazy() {
            if (this.searchPath == null) {
                try {
                    this.searchPath = Collections.unmodifiableCollection(this.computePath());
                } catch (IOException var2) {
                    throw new UncheckedIOException(var2);
                }
            }

        }

        boolean contains(Path file) throws IOException {
            return Locations.this.contains(this.searchPath, file);
        }
    }

    private class ClassPathLocationHandler extends Locations.SimpleLocationHandler {
        ClassPathLocationHandler() {
            super(StandardLocation.CLASS_PATH, Option.CLASS_PATH);
        }

        Collection<Path> getPaths() {
            this.lazy();
            return this.searchPath;
        }

        protected Locations.SearchPath computePath(String value) {
            String cp = value;
            if (value == null) {
                cp = System.getProperty("env.class.path");
            }

            if (cp == null && System.getProperty("application.home") == null) {
                cp = System.getProperty("java.class.path");
            }

            if (cp == null) {
                cp = ".";
            }

            return this.createPath().addFiles(cp);
        }

        protected Locations.SearchPath createPath() {
            return (Locations.this.new SearchPath()).expandJarClassPaths(true).emptyPathDefault(Locations.this.getPath("."));
        }

        private void lazy() {
            if (this.searchPath == null) {
                this.setPaths(null);
            }

        }
    }

    private class SimpleLocationHandler extends Locations.BasicLocationHandler {
        protected Collection<Path> searchPath;

        SimpleLocationHandler(Location location, Option... options) {
            super(location, options);
        }

        boolean handleOption(Option option, String value) {
            if (!this.options.contains(option)) {
                return false;
            } else {
                this.explicit = true;
                this.searchPath = value == null ? null : Collections.unmodifiableCollection(this.createPath().addFiles(value));
                return true;
            }
        }

        Collection<Path> getPaths() {
            return this.searchPath;
        }

        void setPaths(Iterable<? extends Path> files) {
            Locations.SearchPath p;
            if (files == null) {
                p = this.computePath((String)null);
            } else {
                this.explicit = true;
                p = this.createPath().addFiles(files);
            }

            this.searchPath = Collections.unmodifiableCollection(p);
        }

        protected Locations.SearchPath computePath(String value) {
            return this.createPath().addFiles(value);
        }

        protected Locations.SearchPath createPath() {
            return Locations.this.new SearchPath();
        }

        boolean contains(Path file) throws IOException {
            return Locations.this.contains(this.searchPath, file);
        }
    }

    private class OutputLocationHandler extends Locations.BasicLocationHandler {
        private Path outputDir;
        private Locations.ModuleTable moduleTable;
        private boolean listed;

        OutputLocationHandler(Location location, Option... options) {
            super(location, options);
        }

        boolean handleOption(Option option, String value) {
            if (!this.options.contains(option)) {
                return false;
            } else {
                this.explicit = true;
                this.outputDir = value == null ? null : Locations.this.getPath(value);
                return true;
            }
        }

        Collection<Path> getPaths() {
            return this.outputDir == null ? null : Collections.singleton(this.outputDir);
        }

        void setPaths(Iterable<? extends Path> paths) throws IOException {
            if (paths == null) {
                this.outputDir = null;
            } else {
                this.explicit = true;
                this.outputDir = this.checkSingletonDirectory(paths);
            }

            this.moduleTable = null;
            this.listed = false;
        }

        Location getLocationForModule(String name) {
            if (this.moduleTable == null) {
                this.moduleTable = Locations.this.new ModuleTable();
            }

            Locations.ModuleLocationHandler l = this.moduleTable.get(name);
            if (l == null) {
                Path out = this.outputDir.resolve(name);
                l = Locations.this.new ModuleLocationHandler(this, this.location.getName() + "[" + name + "]", name, Collections.singletonList(out), true);
                this.moduleTable.add(l);
            }

            return l;
        }

        void setPathsForModule(String name, Iterable<? extends Path> paths) throws IOException {
            Path out = this.checkSingletonDirectory(paths);
            if (this.moduleTable == null) {
                this.moduleTable = Locations.this.new ModuleTable();
            }

            Locations.ModuleLocationHandler l = this.moduleTable.get(name);
            if (l == null) {
                l = Locations.this.new ModuleLocationHandler(this, this.location.getName() + "[" + name + "]", name, Collections.singletonList(out), true);
                this.moduleTable.add(l);
            } else {
                l.searchPath = Collections.singletonList(out);
                this.moduleTable.updatePaths(l);
            }

            this.explicit = true;
        }

        Location getLocationForModule(Path file) {
            return this.moduleTable == null ? null : this.moduleTable.get(file);
        }

        Iterable<Set<Location>> listLocationsForModules() throws IOException {
            if (!this.listed && this.outputDir != null) {
                DirectoryStream<Path> stream = Files.newDirectoryStream(this.outputDir);

                try {

                    for (Path p : stream) {
                        this.getLocationForModule(p.getFileName().toString());
                    }
                } catch (Throwable var5) {
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (Throwable var4) {
                            var5.addSuppressed(var4);
                        }
                    }

                    throw var5;
                }

                if (stream != null) {
                    stream.close();
                }

                this.listed = true;
            }

            return this.moduleTable != null && !this.moduleTable.isEmpty() ? Collections.singleton(this.moduleTable.locations()) : Collections.emptySet();
        }

        boolean contains(Path file) throws IOException {
            if (this.moduleTable != null) {
                return this.moduleTable.contains(file);
            } else {
                return this.outputDir != null && Locations.normalize(file).startsWith(Locations.normalize(this.outputDir));
            }
        }
    }

    private abstract static class BasicLocationHandler extends Locations.LocationHandler {
        final Location location;
        final Set<Option> options;
        boolean explicit;

        protected BasicLocationHandler(Location location, Option... options) {
            this.location = location;
            this.options = options.length == 0 ? EnumSet.noneOf(Option.class) : EnumSet.copyOf(Arrays.asList(options));
        }

        void setPathsForModule(String moduleName, Iterable<? extends Path> files) throws IOException {
            throw new UnsupportedOperationException("not supported for " + this.location);
        }

        protected Path checkSingletonDirectory(Iterable<? extends Path> paths) throws IOException {
            Iterator<? extends Path> pathIter = paths.iterator();
            if (!pathIter.hasNext()) {
                throw new IllegalArgumentException("empty path for directory");
            } else {
                Path path = (Path)pathIter.next();
                if (pathIter.hasNext()) {
                    throw new IllegalArgumentException("path too long for directory");
                } else {
                    this.checkDirectory(path);
                    return path;
                }
            }
        }

        protected Path checkDirectory(Path path) throws IOException {
            Objects.requireNonNull(path);
            if (!Files.exists(path)) {
                throw new FileNotFoundException(path + ": does not exist");
            } else if (!Files.isDirectory(path)) {
                throw new IOException(path + ": not a directory");
            } else {
                return path;
            }
        }

        boolean isExplicit() {
            return this.explicit;
        }
    }

    protected abstract static class LocationHandler {
        protected LocationHandler() {
        }

        abstract boolean handleOption(Option var1, String var2);

        boolean isSet() {
            return this.getPaths() != null;
        }

        abstract boolean isExplicit();

        abstract Collection<Path> getPaths();

        abstract void setPaths(Iterable<? extends Path> var1) throws IOException;

        abstract void setPathsForModule(String var1, Iterable<? extends Path> var2) throws IOException;

        Location getLocationForModule(String moduleName) throws IOException {
            return null;
        }

        Location getLocationForModule(Path file) throws IOException {
            return null;
        }

        String inferModuleName() {
            return null;
        }

        Iterable<Set<Location>> listLocationsForModules() throws IOException {
            return null;
        }

        abstract boolean contains(Path var1) throws IOException;
    }

    private class SearchPath extends LinkedHashSet<Path> {
        private static final long serialVersionUID = 0L;
        private boolean expandJarClassPaths = false;
        private final Set<Path> canonicalValues = new HashSet();
        private Path emptyPathDefault = null;

        private SearchPath() {
        }

        public Locations.SearchPath expandJarClassPaths(boolean x) {
            this.expandJarClassPaths = x;
            return this;
        }

        public Locations.SearchPath emptyPathDefault(Path x) {
            this.emptyPathDefault = x;
            return this;
        }

        public Locations.SearchPath addDirectories(String dirs, boolean warn) {
            boolean prev = this.expandJarClassPaths;
            this.expandJarClassPaths = true;

            Locations.SearchPath var9;
            try {
                if (dirs != null) {

                    for (Path dir : Locations.this.getPathEntries(dirs)) {
                        this.addDirectory(dir, warn);
                    }
                }

                var9 = this;
            } finally {
                this.expandJarClassPaths = prev;
            }

            return var9;
        }

        public Locations.SearchPath addDirectories(String dirs) {
            return this.addDirectories(dirs, Locations.this.warn);
        }

        private void addDirectory(Path dir, boolean warn) {
            if (!Files.isDirectory(dir, new LinkOption[0])) {
                if (warn) {
                    Locations.this.log.warning(LintCategory.PATH, Warnings.DirPathElementNotFound(dir));
                }

            } else {
                try {
                    Stream<Path> s = Files.list(dir);

                    try {
                        s.filter((x$0) -> {
                            return Locations.this.isArchive(x$0);
                        }).forEach((dirEntry) -> {
                            this.addFile(dirEntry, warn);
                        });
                    } catch (Throwable var7) {
                        if (s != null) {
                            try {
                                s.close();
                            } catch (Throwable var6) {
                                var7.addSuppressed(var6);
                            }
                        }

                        throw var7;
                    }

                    s.close();
                } catch (IOException var8) {
                }

            }
        }

        public Locations.SearchPath addFiles(String files, boolean warn) {
            if (files != null) {
                this.addFiles(Locations.this.getPathEntries(files, this.emptyPathDefault), warn);
            }

            return this;
        }

        public Locations.SearchPath addFiles(String files) {
            return this.addFiles(files, Locations.this.warn);
        }

        public Locations.SearchPath addFiles(Iterable<? extends Path> files, boolean warn) {
            if (files != null) {

                for (Path file : files) {
                    this.addFile(file, warn);
                }
            }

            return this;
        }

        public Locations.SearchPath addFiles(Iterable<? extends Path> files) {
            return this.addFiles(files, Locations.this.warn);
        }

        public void addFile(Path file, boolean warn) {
            if (!this.contains(file)) {
                if (!Locations.this.fsInfo.exists(file)) {
                    if (warn) {
                        Locations.this.log.warning(LintCategory.PATH, Warnings.PathElementNotFound(file));
                    }

                    super.add(file);
                } else {
                    Path canonFile = Locations.this.fsInfo.getCanonicalFile(file);
                    if (!this.canonicalValues.contains(canonFile)) {
                        if (Locations.this.fsInfo.isFile(file) && !file.getFileName().toString().endsWith(".jmod") && !file.endsWith("modules")) {
                            if (!Locations.this.isArchive(file)) {
                                try {
                                    FileSystems.newFileSystem(file, (ClassLoader)null).close();
                                    if (warn) {
                                        Locations.this.log.warning(LintCategory.PATH, Warnings.UnexpectedArchiveFile(file));
                                    }
                                } catch (ProviderNotFoundException | IOException var5) {
                                    if (warn) {
                                        Locations.this.log.warning(LintCategory.PATH, Warnings.InvalidArchiveFile(file));
                                    }

                                    return;
                                }
                            } else if (Locations.this.fsInfo.getJarFSProvider() == null) {
                                Locations.this.log.error(Errors.NoZipfsForArchive(file));
                                return;
                            }
                        }

                        super.add(file);
                        this.canonicalValues.add(canonFile);
                        if (this.expandJarClassPaths && Locations.this.fsInfo.isFile(file) && !file.endsWith("modules")) {
                            this.addJarClassPath(file, warn);
                        }

                    }
                }
            }
        }

        private void addJarClassPath(Path jarFile, boolean warn) {
            try {

                for (Path f : Locations.this.fsInfo.getJarClassPath(jarFile)) {
                    this.addFile(f, warn);
                }
            } catch (IOException var5) {
                Locations.this.log.error(Errors.ErrorReadingFile(jarFile, JavacFileManager.getMessage(var5)));
            }

        }
    }
}