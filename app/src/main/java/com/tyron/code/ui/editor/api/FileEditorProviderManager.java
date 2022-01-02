package com.tyron.code.ui.editor.api;

import androidx.annotation.NonNull;

import com.tyron.builder.project.Project;

import java.io.File;

public interface FileEditorProviderManager {

    FileEditorProvider[] getProviders(@NonNull Project project, @NonNull File file);

    FileEditorProvider getProvider(@NonNull String typeId);
}
