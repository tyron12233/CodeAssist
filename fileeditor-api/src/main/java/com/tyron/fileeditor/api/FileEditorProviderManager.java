package com.tyron.fileeditor.api;

import androidx.annotation.NonNull;

import java.io.File;

public interface FileEditorProviderManager {

    FileEditorProvider[] getProviders(@NonNull File file);

    FileEditorProvider getProvider(@NonNull String typeId);

    void registerProvider(FileEditorProvider provider);
}
