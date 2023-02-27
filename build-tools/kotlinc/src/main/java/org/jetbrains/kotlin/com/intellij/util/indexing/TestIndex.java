package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.indexing.DataIndexer;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorStringDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.VoidDataExternalizer;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class TestIndex extends FileBasedIndexExtension<String, VirtualFile> {
    @NonNls
    public static final ID<String, VirtualFile> NAME = ID.create("testIndex");

    private final MyDataIndexer myDataIndexer = new MyDataIndexer();

    @NonNull
    @Override
    public ID<String, VirtualFile> getName() {
        return NAME;
    }

    @NonNull
    @Override
    public DataIndexer<String, VirtualFile, FileContent> getIndexer() {
        return myDataIndexer;
    }

    @NonNull
    @Override
    public KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @NonNull
    @Override
    public DataExternalizer<VirtualFile> getValueExternalizer() {
        return new DataExternalizer<VirtualFile>() {
            @Override
            public void save(@NotNull DataOutput dataOutput,
                             VirtualFile virtualFile) throws IOException {
                dataOutput.writeInt(((VirtualFileWithId) virtualFile).getId());
            }

            @Override
            public VirtualFile read(@NotNull DataInput dataInput) throws IOException {
                return VirtualFileManager.getInstance().findFileById(dataInput.readInt());
            }
        };
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @NonNull
    @Override
    public FileBasedIndex.InputFilter getInputFilter() {
        return new FileBasedIndex.FileTypeSpecificInputFilter() {
            @Override
            public void registerFileTypesUsedForIndexing(@NonNull Consumer<? super FileType> fileTypeSink) {

            }

            @Override
            public boolean acceptInput(@NonNull VirtualFile file) {
                return file.getName().endsWith(".class") || file.getName().endsWith(".java");
            }
        };
    }

    @Override
    public boolean dependsOnFileContent() {
        return false;
    }

    private static class MyDataIndexer implements DataIndexer<String, VirtualFile, FileContent> {

        @NonNull
        @Override
        public Map<String, VirtualFile> map(@NonNull FileContent inputData) {
            String fileName = inputData.getFileName();
            if (fileName.endsWith(".class")) {
                fileName = fileName.substring(0, fileName.length() - ".class".length());
            }
            return Collections.singletonMap(fileName, inputData.getFile());
        }
    }
}


