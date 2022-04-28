package com.tyron.code.ui.editor.impl;

import androidx.annotation.NonNull;

import com.tyron.code.ui.editor.impl.image.ImageEditorProvider;
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeEditorProvider;
import com.tyron.code.ui.editor.impl.xml.LayoutTextEditorProvider;
import com.tyron.fileeditor.api.FileEditorProvider;
import com.tyron.fileeditor.api.FileEditorProviderManager;

import java.io.File;
import java.util.ArrayList;

public class FileEditorProviderManagerImpl implements FileEditorProviderManager {

    private static FileEditorProviderManager sInstance = null;

    public static FileEditorProviderManager getInstance() {
        if (sInstance == null) {
            sInstance = new FileEditorProviderManagerImpl();
        }
        return sInstance;
    }

    private final ArrayList<FileEditorProvider> mProviders;
    private final ArrayList<FileEditorProvider> mSharedProviderList;

    public FileEditorProviderManagerImpl() {
        mProviders = new ArrayList<>();
        mSharedProviderList = new ArrayList<>();

        registerBuiltInProviders();
    }

    private void registerBuiltInProviders() {
        registerProvider(new RosemoeEditorProvider());
        registerProvider(new LayoutTextEditorProvider());
        registerProvider(new ImageEditorProvider());
    }

    @Override
    public FileEditorProvider[] getProviders(@NonNull File file) {
        mSharedProviderList.clear();
        for(int i = mProviders.size() - 1; i >= 0; i--){
            FileEditorProvider provider = mProviders.get(i);
            if(provider.accept(file)){
                mSharedProviderList.add(provider);
            }
        }
        return mSharedProviderList.toArray(new FileEditorProvider[0]);
    }

    @Override
    public FileEditorProvider getProvider(@NonNull String typeId) {
        return null;
    }

    public void registerProvider(FileEditorProvider provider) {
        String editorTypeId = provider.getEditorTypeId();
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            FileEditorProvider _provider = mProviders.get(i);
            if (editorTypeId.equals(_provider.getEditorTypeId())) {
                throw new IllegalArgumentException("Attempt to register non unique editor id: " + editorTypeId);
            }
        }
        mProviders.add(provider);
    }

    private void unregisterProvider(FileEditorProvider provider) {
        mProviders.remove(provider);
    }
}
