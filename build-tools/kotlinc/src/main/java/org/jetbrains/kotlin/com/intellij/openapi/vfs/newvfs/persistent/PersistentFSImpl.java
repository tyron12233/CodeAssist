//package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent;
//
//import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
//import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
//
//import java.util.concurrent.locks.ReadWriteLock;
//import java.util.concurrent.locks.ReentrantReadWriteLock;
//
//public class PersistentFSImpl extends PersistentFS implements Disposable {
//
//    private static final Logger LOG = Logger.getInstance(PersistentFSImpl.class);
//
//    private final Map<String, VirtualFileSystemEntry> myRoots;
//
//    private final VirtualDirectoryCache myIdToDirCache = new VirtualDirectoryCache();
//    private final ReadWriteLock myInputLock = new ReentrantReadWriteLock();
//}
