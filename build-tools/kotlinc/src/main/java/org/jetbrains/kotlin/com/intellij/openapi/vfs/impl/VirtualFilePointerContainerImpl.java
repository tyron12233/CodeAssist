package org.jetbrains.kotlin.com.intellij.openapi.vfs.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.ide.highlighter.ArchiveFileType;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ex.ApplicationManagerEx;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.util.InvalidDataException;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.Trinity;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerContainer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerManagerEx;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtilRt;
import org.jetbrains.kotlin.com.intellij.util.TraceableDisposable;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class VirtualFilePointerContainerImpl extends TraceableDisposable implements VirtualFilePointerContainer, Disposable {
    private static final Logger LOG = Logger.getInstance(VirtualFilePointerContainer.class);
    private static final int UNINITIALIZED = -1;
    @NonNull
    private final ConcurrentList<VirtualFilePointer> myList = ContainerUtil.createConcurrentList();
    @NonNull
    private final ConcurrentList<VirtualFilePointer> myJarDirectories =
            ContainerUtil.createConcurrentList();
    @NonNull
    private final ConcurrentList<VirtualFilePointer> myJarRecursiveDirectories =
            ContainerUtil.createConcurrentList();
    @NonNull
    private final VirtualFilePointerManagerEx myVirtualFilePointerManager;
    @NonNull
    private final Disposable myParent;
    private final VirtualFilePointerListener myListener;
    private volatile Trinity<String[], VirtualFile[], VirtualFile[]> myCachedThings;
    private volatile long myTimeStampOfCachedThings = UNINITIALIZED;
    public static final String URL_ATTR = "url";
    private boolean myDisposed;
    private static final boolean TRACE_CREATION =
            LOG.isDebugEnabled() || ApplicationManager.getApplication().isUnitTestMode();
    public static final String JAR_DIRECTORY_ELEMENT = "jarDirectory";
    public static final String RECURSIVE_ATTR = "recursive";

    VirtualFilePointerContainerImpl(@NonNull VirtualFilePointerManagerEx manager,
                                    @NonNull Disposable parentDisposable,
                                    @Nullable VirtualFilePointerListener listener) {
        super(TRACE_CREATION);
        myVirtualFilePointerManager = manager;
        myParent = parentDisposable;
        myListener = listener;
    }

    private static List<Element> toList(NodeList list) {
        return IntStream.range(0, list.getLength())
                .mapToObj(list::item)
                .filter(it -> it instanceof Element)
                .map(it -> (Element) it)
                .collect(Collectors.toList());
    }

    @Override
    public void readExternal(@NonNull final Element rootChild,
                             @NonNull final String childName,
                             boolean externalizeJarDirectories) throws InvalidDataException {
        final List<Element> urls = toList(rootChild.getElementsByTagName(childName));
        addAll(ContainerUtil.map(urls, url -> url.getAttribute(URL_ATTR)));
        if (externalizeJarDirectories) {
            List<Element> jarDirs = toList(rootChild.getElementsByTagName(JAR_DIRECTORY_ELEMENT));
            for (Element jarDir : jarDirs) {
                String url = jarDir.getAttribute(URL_ATTR);
                if (url.isEmpty()) {
                    throw new RuntimeException();
                }
                boolean recursive =
                        Boolean.parseBoolean(jarDir.getAttributeNS(RECURSIVE_ATTR, "false"));
                addJarDirectory(url, recursive);
            }
        }
    }

    @Override
    public void writeExternal(@NonNull final Element element,
                              @NonNull final String childElementName,
                              boolean externalizeJarDirectories) {
        for (VirtualFilePointer pointer : myList) {
            String url = pointer.getUrl();
            Element rootPathElement = element.getOwnerDocument().createElement(childElementName);
            rootPathElement.setAttribute(URL_ATTR, url);

            element.appendChild(rootPathElement);
        }
        if (externalizeJarDirectories) {
            writeJarDirs(myJarDirectories, element, false);
            writeJarDirs(myJarRecursiveDirectories, element, true);
        }
    }

    private static void writeJarDirs(@NonNull List<? extends VirtualFilePointer> myJarDirectories,
                                     @NonNull Element element,
                                     boolean recursive) {
        List<VirtualFilePointer> jarDirectories = new ArrayList<>(myJarDirectories);
        jarDirectories.sort(Comparator.comparing(VirtualFilePointer::getUrl,
                String.CASE_INSENSITIVE_ORDER));
        for (VirtualFilePointer pointer : jarDirectories) {
            String url = pointer.getUrl();
            final Element jarDirElement =
                    element.getOwnerDocument().createElement(JAR_DIRECTORY_ELEMENT);
            jarDirElement.setAttribute(URL_ATTR, url);
            jarDirElement.setAttribute(RECURSIVE_ATTR, Boolean.toString(recursive));
            element.appendChild(jarDirElement);
        }
    }

    @Override
    public void moveUp(@NonNull String url) {
        int index = indexOf(url);
        if (index <= 0) {
            return;
        }
        dropCaches();
        ContainerUtil.swapElements(myList, index - 1, index);
    }

    @Override
    public void moveDown(@NonNull String url) {
        int index = indexOf(url);
        if (index < 0 || index + 1 >= myList.size()) {
            return;
        }
        dropCaches();
        ContainerUtil.swapElements(myList, index, index + 1);
    }

    private int indexOf(@NonNull final String url) {
        for (int i = 0; i < myList.size(); i++) {
            final VirtualFilePointer pointer = myList.get(i);
            if (url.equals(pointer.getUrl())) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public void killAll() {
        myList.clear();
        myJarDirectories.clear();
        myJarRecursiveDirectories.clear();
    }

    @Override
    public void add(@NonNull VirtualFile file) {
        checkDisposed();
        dropCaches();
        myList.addIfAbsent(create(file));
    }

    @Override
    public void add(@NonNull String url) {
        checkDisposed();
        dropCaches();
        myList.addIfAbsent(create(url));
    }

    @Override
    public void remove(@NonNull VirtualFilePointer pointer) {
        checkDisposed();
        dropCaches();
        final boolean result = myList.remove(pointer);
        LOG.assertTrue(result);
    }

    @Override
    @NonNull
    public List<VirtualFilePointer> getList() {
        checkDisposed();
        return Collections.unmodifiableList(myList);
    }

    @Override
    public void addAll(@NonNull VirtualFilePointerContainer that) {
        checkDisposed();
        dropCaches();

        addAll(Arrays.asList(that.getUrls()));

        List<VirtualFilePointer> jarDups =
                ContainerUtil.map(((VirtualFilePointerContainerImpl) that).myJarDirectories,
                        this::duplicate);
        List<VirtualFilePointer> jarRecursiveDups =
                ContainerUtil.map(((VirtualFilePointerContainerImpl) that).myJarRecursiveDirectories,
                        this::duplicate);

        jarDups.forEach(myJarDirectories::addIfAbsent);
        jarRecursiveDups.forEach(myJarRecursiveDirectories::addIfAbsent);
    }

    public void addAll(@NonNull Collection<String> urls) {
        // optimization: faster than calling .add() one by one
        ContainerUtil.map(urls, this::create).forEach(myList::addIfAbsent);
    }

    private void dropCaches() {
        myTimeStampOfCachedThings =
                -1; // make it never equal to myVirtualFilePointerManager.getModificationCount()
        myCachedThings = EMPTY;
    }

    @Override
    public String[] getUrls() {
        if (myTimeStampOfCachedThings == UNINITIALIZED) {
            // optimization: when querying urls, and nothing was cached yet, do not access disk
            // (in cacheThings()) - can be expensive
            return ContainerUtil.map2Array(myList, String.class, VirtualFilePointer::getUrl);
        }
        return getOrCache().first;
    }

    @NonNull
    private Trinity<String[], VirtualFile[], VirtualFile[]> getOrCache() {
        checkDisposed();
        long timeStamp = myTimeStampOfCachedThings;
        Trinity<String[], VirtualFile[], VirtualFile[]> cached = myCachedThings;
        return timeStamp ==
               myVirtualFilePointerManager.getModificationCount() ? cached : cacheThings();
    }

    private static final Trinity<String[], VirtualFile[], VirtualFile[]> EMPTY = Trinity.create(
            ArrayUtilRt.EMPTY_STRING_ARRAY,
            VirtualFile.EMPTY_ARRAY,
            VirtualFile.EMPTY_ARRAY);

    @NonNull
    private Trinity<String[], VirtualFile[], VirtualFile[]> cacheThings() {
        Trinity<String[], VirtualFile[], VirtualFile[]> result;
        if (isEmpty()) {
            result = EMPTY;
        } else {
            List<VirtualFile> cachedFiles = new ArrayList<>(myList.size());
            List<String> cachedUrls = new ArrayList<>(myList.size());
            List<VirtualFile> cachedDirectories = new ArrayList<>(myList.size() / 3);
            boolean allFilesAreDirs = true;
            for (VirtualFilePointer v : myList) {
                VirtualFile file = v.getFile();
                String url = v.getUrl();
                cachedUrls.add(url);
                if (file != null) {
                    cachedFiles.add(file);
                    if (file.isDirectory()) {
                        cachedDirectories.add(file);
                    } else {
                        allFilesAreDirs = false;
                    }
                }
            }
            for (VirtualFilePointer jarDirectoryPtr : myJarDirectories) {
                VirtualFile jarDirectory = jarDirectoryPtr.getFile();
                if (jarDirectory != null) {
                    // getFiles() must return files under jar directories but must not return
                    // jarDirectories themselves
                    cachedDirectories.remove(jarDirectory);

                    VirtualFile[] children = jarDirectory.getChildren();
                    for (VirtualFile file : children) {
                        if (!file.isDirectory() &&
                            FileTypeRegistry.getInstance()
                                    .getFileTypeByFileName(file.getNameSequence()) ==
                            ArchiveFileType.INSTANCE) {
                            VirtualFile jarRoot =
                                    StandardFileSystems.jar().findFileByPath(file.getPath() + "!/");
                            if (jarRoot != null) {
                                cachedFiles.add(jarRoot);
                                cachedDirectories.add(jarRoot);
                            }
                        }
                    }
                }
            }
            for (VirtualFilePointer jarDirectoryPtr : myJarRecursiveDirectories) {
                VirtualFile jarDirectory = jarDirectoryPtr.getFile();
                if (jarDirectory != null) {
                    // getFiles() must return files under jar directories but must not return
                    // jarDirectories themselves
                    cachedDirectories.remove(jarDirectory);

                    VfsUtilCore.visitChildrenRecursively(jarDirectory,
                            new VirtualFileVisitor<Void>() {
                                @Override
                                public boolean visitFile(@NonNull VirtualFile file) {
                                    if (!file.isDirectory() &&
                                        FileTypeRegistry.getInstance()
                                                .getFileTypeByFileName(file.getNameSequence()) ==
                                        ArchiveFileType.INSTANCE) {
                                        VirtualFile jarRoot = StandardFileSystems.jar()
                                                .findFileByPath(file.getPath() + "!/");
                                        if (jarRoot != null) {
                                            cachedFiles.add(jarRoot);
                                            cachedDirectories.add(jarRoot);
                                            return false;
                                        }
                                    }
                                    return true;
                                }
                            });
                }
            }
            String[] urlsArray = ArrayUtilRt.toStringArray(cachedUrls);
            VirtualFile[] directories = VfsUtilCore.toVirtualFileArray(cachedDirectories);
            VirtualFile[] files =
                    allFilesAreDirs ? directories : VfsUtilCore.toVirtualFileArray(cachedFiles);
            result = Trinity.create(urlsArray, files, directories);
        }
        myCachedThings = result;
        myTimeStampOfCachedThings = myVirtualFilePointerManager.getModificationCount();
        return result;
    }

    @Override
    public boolean isEmpty() {
        return myList.isEmpty() &&
               myJarDirectories.isEmpty() &&
               myJarRecursiveDirectories.isEmpty();
    }

    @Override
    public VirtualFile[] getFiles() {
        return getOrCache().second;
    }

    @Override
    public VirtualFile[] getDirectories() {
        return getOrCache().third;
    }

    @Override
    @Nullable
    public VirtualFilePointer findByUrl(@NonNull String url) {
        checkDisposed();
        for (VirtualFilePointer pointer : ContainerUtil.concat(myList,
                myJarDirectories,
                myJarRecursiveDirectories)) {
            if (url.equals(pointer.getUrl())) {
                return pointer;
            }
        }
        return null;
    }

    @Override
    public void clear() {
        dropCaches();
        killAll();
    }

    @Override
    public int size() {
        return myList.size() + myJarDirectories.size() + myJarRecursiveDirectories.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VirtualFilePointerContainerImpl)) {
            return false;
        }

        VirtualFilePointerContainerImpl impl = (VirtualFilePointerContainerImpl) o;

        return myList.equals(impl.myList) &&
               myJarDirectories.equals(impl.myJarDirectories) &&
               myJarRecursiveDirectories.equals(impl.myJarRecursiveDirectories);
    }

    @Override
    public int hashCode() {
        return myList.hashCode();
    }

    @NonNull
    private VirtualFilePointer create(@NonNull VirtualFile file) {
        return myVirtualFilePointerManager.create(file, myParent, myListener);
    }

    @NonNull
    private VirtualFilePointer create(@NonNull String url) {
        return myVirtualFilePointerManager.create(url, myParent, myListener);
    }

    @NonNull
    private VirtualFilePointer duplicate(@NonNull VirtualFilePointer virtualFilePointer) {
        return myVirtualFilePointerManager.duplicate(virtualFilePointer, myParent, myListener);
    }

    @NonNull
    @Override
    public String toString() {
        return "VFPContainer: " +
               myList +
               (myJarDirectories.isEmpty() ? "" : ", jars: " + myJarDirectories) +
               (myJarRecursiveDirectories.isEmpty() ? "" : ", jars(recursively): " +
                                                           myJarRecursiveDirectories);
    }

    @Override
    @NonNull
    public VirtualFilePointerContainer clone(@NonNull Disposable parent) {
        return clone(parent, null);
    }

    @Override
    @NonNull
    public VirtualFilePointerContainer clone(@NonNull Disposable parent,
                                             @Nullable VirtualFilePointerListener listener) {
        checkDisposed();
        VirtualFilePointerContainerImpl clone =
                (VirtualFilePointerContainerImpl) myVirtualFilePointerManager.createContainer(parent,
                        listener);

        List<VirtualFilePointer> toAdd = ContainerUtil.map(myList, p -> clone.create(p.getUrl()));
        clone.myList.addAll(toAdd);
        clone.addAllJarDirectories(ContainerUtil.map(myJarDirectories, VirtualFilePointer::getUrl),
                false);
        clone.addAllJarDirectories(ContainerUtil.map(myJarRecursiveDirectories,
                VirtualFilePointer::getUrl), true);
        return clone;
    }

    @Override
    public void dispose() {
        checkDisposed();
        myDisposed = true;
        kill(null);
        clear();
    }

    private void checkDisposed() {
        if (myDisposed) {
            throwDisposalError("Already disposed:\n" + getStackTrace());
        }
    }

    @Override
    public void addJarDirectory(@NonNull String directoryUrl, boolean recursively) {
        VirtualFilePointer pointer =
                myVirtualFilePointerManager.createDirectoryPointer(directoryUrl,
                        recursively,
                        myParent,
                        myListener);
        (recursively ? myJarRecursiveDirectories : myJarDirectories).addIfAbsent(pointer);

        myList.addIfAbsent(pointer); // hack. jar directories need to be contained in class roots too (for externalization compatibility) but be ignored in getFiles()
        dropCaches();
    }

    /**
     * optimization: faster than calling {@link #addJarDirectory(String, boolean)} one by one
     */
    public void addAllJarDirectories(@NonNull Collection<String> directoryUrls,
                                     boolean recursively) {
        if (directoryUrls.isEmpty()) {
            return;
        }
        List<VirtualFilePointer> pointers = ContainerUtil.map(directoryUrls,
                url -> myVirtualFilePointerManager.createDirectoryPointer(url,
                        recursively,
                        myParent,
                        myListener));
        pointers.forEach((recursively ? myJarRecursiveDirectories : myJarDirectories)::addIfAbsent);
        pointers.forEach(myList::addIfAbsent); // hack. jar directories need to be contained in class roots too (for externalization compatibility) but be ignored in getFiles()
        dropCaches();
    }

    @Override
    public boolean removeJarDirectory(@NonNull String directoryUrl) {
        dropCaches();
        Predicate<VirtualFilePointer> filter =
                ptr -> FileUtil.pathsEqual(ptr.getUrl(), directoryUrl);
        boolean removed0 = myList.removeIf(filter);
        boolean removed1 = myJarDirectories.removeIf(filter);
        boolean removed2 = myJarRecursiveDirectories.removeIf(filter);
        return removed0 || removed1 || removed2;
    }

    @NonNull
    @Override
    public List<Pair<String, Boolean>> getJarDirectories() {
        List<Pair<String, Boolean>> jars =
                ContainerUtil.map(myJarDirectories, ptr -> Pair.create(ptr.getUrl(), false));
        List<Pair<String, Boolean>> recJars = ContainerUtil.map(myJarRecursiveDirectories,
                ptr -> Pair.create(ptr.getUrl(), true));
        return ContainerUtil.concat(jars, recJars);
    }
}