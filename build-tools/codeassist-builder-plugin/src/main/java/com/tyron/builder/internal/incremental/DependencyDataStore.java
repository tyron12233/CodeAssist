package com.tyron.builder.internal.incremental;

import com.android.annotations.NonNull;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.io.Closer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 *
 * Stores a collection of {@link DependencyData}.
 *
 * The format is binary and follows the following format:
 *
 * (Header Tag)(version number: int)
 * (Start Tag)(Main File)[(secondary Tag)(secondary File)...][(Output tag)(output file)...][(secondary Output tag)(output file)...]
 * (Start Tag)(Main File)[(secondary Tag)(secondary File)...][(Output tag)(output file)...][(secondary Output tag)(output file)...]
 * ...
 *
 * All files are written as (size in int)(byte array, using UTF8 encoding).
 */
public class DependencyDataStore {

    private static final byte TAG_HEADER = 0x7F;
    private static final byte TAG_START = 0x70;
    private static final byte TAG_SECONDARY_FILE = 0x71;
    private static final byte TAG_OUTPUT = 0x73;
    private static final byte TAG_SECONDARY_OUTPUT = 0x74;
    private static final byte TAG_END = 0x77;

    private static final int CURRENT_VERSION = 1;

    private final Map<String, DependencyData> mMainFileMap = Maps.newHashMap();

    public DependencyDataStore() {
    }

    public void addData(@NonNull List<DependencyData> dataList) {
        for (DependencyData data : dataList) {
            mMainFileMap.put(data.getMainFile(), data);
        }
    }

    public void addData(@NonNull DependencyData data) {
        mMainFileMap.put(data.getMainFile(), data);
    }

    public void remove(@NonNull DependencyData data) {
        mMainFileMap.remove(data.getMainFile());
    }

    public void updateAll(@NonNull List<DependencyData> dataList) {
        for (DependencyData data : dataList) {
            mMainFileMap.put(data.getMainFile(), data);
        }
    }

    @NonNull
    public Collection<DependencyData> getData() {
        return mMainFileMap.values();
    }

    @VisibleForTesting
    DependencyData getByMainFile(String path) {
        return mMainFileMap.get(path);
    }

    /**
     * Returns the map of data using the main file as key.
     *
     * @see com.android.builder.internal.incremental.DependencyData#getMainFile()
     */
    @NonNull
    public Map<String, DependencyData> getMainFileMap() {
        return mMainFileMap;
    }

    /**
     * Saves the dependency data to a given file.
     *
     * @param file the file to save the data to.
     * @throws IOException
     */
    public void saveTo(@NonNull File file) throws IOException {

        Closer closer = Closer.create();
        try {
            FileOutputStream fos = closer.register(new FileOutputStream(file));
            fos.write(TAG_HEADER);
            writeInt(fos, CURRENT_VERSION);

            for (DependencyData data : getData()) {
                fos.write(TAG_START);
                writePath(fos, data.getMainFile());

                for (String path : data.getSecondaryFiles()) {
                    fos.write(TAG_SECONDARY_FILE);
                    writePath(fos, path);
                }

                for (String path : data.getOutputFiles()) {
                    fos.write(TAG_OUTPUT);
                    writePath(fos, path);
                }

                for (String path : data.getSecondaryOutputFiles()) {
                    fos.write(TAG_SECONDARY_OUTPUT);
                    writePath(fos, path);
                }

            }
        } catch (Throwable e) {
            throw closer.rethrow(e);
        } finally {
            closer.close();
        }
    }

    private static class ReusableBuffer {
        byte[] intBuffer = new byte[4];
        byte[] pathBuffer = null;
    }

    /**
     * Loads the dependency data from the given file.
     *
     * @param file the file to load the data from.
     * @return a map of file-> list of impacted dependency data.
     * @throws IOException
     */
    public Multimap<String, DependencyData> loadFrom(@NonNull File file) throws IOException {
        Multimap<String, DependencyData> inputMap = ArrayListMultimap.create();

        Closer closer = Closer.create();
        FileInputStream fis = closer.register(new FileInputStream(file));

        //  reusable buffer
        ReusableBuffer buffers = new ReusableBuffer();

        // read the header
        if (readByte(fis, buffers) != TAG_HEADER) {
            throw new IllegalStateException("Wrong first byte on " + file.getAbsolutePath());
        }

        int version = readInt(fis, buffers);
        if (version != CURRENT_VERSION) {
            throw new IOException("Unsupported file version: " + version);
        }

        try {
            // just read the first byte since it should be the TAG_START
            byte currentTag = readByte(fis, buffers);
            if (currentTag != TAG_START) {
                throw new IllegalStateException("Wrong first tag on " + file.getAbsolutePath());
            }

            DependencyData currentData = new DependencyData();

            while (currentTag != TAG_END) {
                // read the path
                String path = readPath(fis, buffers);

                switch (currentTag) {
                    case TAG_START:
                        currentData.setMainFile(path);
                        mMainFileMap.put(path, currentData);
                        inputMap.put(path, currentData);
                        break;
                    case TAG_SECONDARY_FILE:
                        currentData.addSecondaryFile(path);
                        inputMap.put(path, currentData);
                        break;
                    case TAG_OUTPUT:
                        currentData.addOutputFile(path);
                        break;
                    case TAG_SECONDARY_OUTPUT:
                        currentData.addSecondaryOutputFile(path);
                        break;
                }

                // read the next tag.
                currentTag = readByte(fis, buffers);

                if (currentTag == TAG_START) {
                    currentData = new DependencyData();
                }
            }

            return inputMap;
        } catch (Throwable e) {
            throw closer.rethrow(e);
        } finally {
            closer.close();
        }
    }

    private static void writeInt(@NonNull FileOutputStream fos, int value) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(value);
        fos.write(b.array());
    }

    private static void writePath(@NonNull FileOutputStream fos, String path) throws IOException {
        byte[] pathBytes = path.getBytes(Charsets.UTF_8);

        writeInt(fos, pathBytes.length);
        fos.write(pathBytes);
    }

    private static byte readByte(@NonNull FileInputStream fis, @NonNull ReusableBuffer buffers)
            throws IOException {
        int read = fis.read(buffers.intBuffer, 0, 1);
        if (read != 1) {
            return TAG_END;
        }

        return buffers.intBuffer[0];
    }

    private static int readInt(@NonNull FileInputStream fis, @NonNull ReusableBuffer buffers)
            throws IOException {
        int read = fis.read(buffers.intBuffer);

        // there must always be 4 bytes for the path length
        if (read != 4) {
            throw new IOException("Failed to read path length");
        }

        // get the int value.
        ByteBuffer b = ByteBuffer.wrap(buffers.intBuffer);
        return b.getInt();
    }

    private static String readPath(@NonNull FileInputStream fis, @NonNull ReusableBuffer buffers)
            throws IOException {
        int length = readInt(fis, buffers);

        if (buffers.pathBuffer == null || buffers.pathBuffer.length < length) {
            buffers.pathBuffer = new byte[length];
        }

        int read = fis.read(buffers.pathBuffer, 0, length);
        if (read != length) {
            throw new IOException("Failed to read path");
        }

        return new String(buffers.pathBuffer, 0, length, Charsets.UTF_8);
    }
}
