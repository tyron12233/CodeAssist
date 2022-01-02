package com.tyron.code.ui.editor.impl;

import androidx.annotation.NonNull;

import com.tyron.builder.project.Project;
import com.tyron.code.ui.editor.api.FileEditorProvider;
import com.tyron.code.ui.editor.api.FileEditorProviderManager;

import java.io.File;
import java.util.ArrayList;

public class FileEditorProviderManagerImpl implements FileEditorProviderManager {

    private final ArrayList<FileEditorProvider> mProviders;
    private final ArrayList<FileEditorProvider> mSharedProviderList;

    public FileEditorProviderManagerImpl(FileEditorProvider[] providers) {
        mProviders = new ArrayList<>();
        mSharedProviderList = new ArrayList<>();

        for (FileEditorProvider provider : providers) {
            registerProvider(provider);
        }
    }

    @Override
    public FileEditorProvider[] getProviders(@NonNull Project project, @NonNull File file) {
        mSharedProviderList.clear();
        for(int i = mProviders.size() - 1; i >= 0; i--){
            FileEditorProvider provider = mProviders.get(i);
            if(provider.accept(project, file)){
                mSharedProviderList.add(provider);
            }
        }
        return mSharedProviderList.toArray(new FileEditorProvider[0]);
    }

    @Override
    public FileEditorProvider getProvider(@NonNull String typeId) {
        return null;
    }

    private void registerProvider(FileEditorProvider provider) {
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
