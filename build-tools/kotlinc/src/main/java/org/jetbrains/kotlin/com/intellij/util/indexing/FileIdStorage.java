package org.jetbrains.kotlin.com.intellij.util.indexing;

import com.google.common.collect.Maps;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import org.jetbrains.kotlin.com.intellij.util.containers.BidirectionalMap;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileIdStorage {

    /**
     * Map of files absolute path's hashcode to its file id
     */
    private static final BidirectionalMap<String, Integer> ourMap = new BidirectionalMap<>();

    private static File getFileIdsFile() throws IOException {
        File fileIds = new File(PathManager.getIndexRoot(), "fileIds");
        if (fileIds.exists()) {
            return fileIds;
        }
        FileUtil.createParentDirs(fileIds);
        if (!fileIds.exists() && !fileIds.createNewFile()) {
            throw new IOException("Failed to create fileIds file");
        }
        return fileIds;
    }

    @SuppressWarnings("unchecked")
    public static void loadIds() throws IOException, ClassNotFoundException {

        try (InputStream inputStream = Files.newInputStream(getFileIdsFile().toPath())) {
            try (ObjectInputStream objectInputStream = new ObjectInputStream(inputStream)) {
                Map<String, Integer> map =
                        (Map<String, Integer>) objectInputStream.readObject();
                if (map != null) {
                    ourMap.putAll(map);
                }
            } catch (EOFException e) {
                // ignored, dont load corrupt index
            }
        }
    }

    public static void saveIds() throws IOException {
        HashMap<String, Integer> map = Maps.newHashMap(ourMap);
        try (OutputStream outputStream = Files.newOutputStream(getFileIdsFile().toPath())) {
            try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream)) {
                objectOutputStream.writeObject(map);
            }
        }
    }

    public static boolean hasId(VirtualFile file) {
        return ourMap.containsKey(file.getPath());
    }

    public static int getId(VirtualFile file) {
        Integer id = ourMap.get(file.getPath());
        if (id == null) {
            return 0;
        }
        return id;
    }

    public static int getAndStoreId(VirtualFile file) {
        if (hasId(file)) {
            return getId(file);
        }
        int newId = FSRecords.getRecords().allocateRecord();
        ourMap.put(file.getPath(), newId);
        fillRecord(newId, file);
        return getId(file);
    }

    private static void fillRecord(int id, VirtualFile file) {
        VirtualFile parent = file.getParent();
        int parentId = -1;
        if (parent != null) {
            parentId = getAndStoreId(file);
        }
        try {
            FSRecords.getRecords().fillRecord(id,
                    file.getTimeStamp(),
                    file.getLength(),
                    0,
                    file.getName().hashCode(),
                    parentId,
                    false
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public static String findPathById(int id) {
        List<String> keysByValue = ourMap.getKeysByValue(id);
        if (keysByValue == null) {
            return null;
        }
        return keysByValue.stream().findAny().orElse(null);
    }
}
