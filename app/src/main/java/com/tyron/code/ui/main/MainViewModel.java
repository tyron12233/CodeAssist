package com.tyron.code.ui.main;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.util.CustomMutableLiveData;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.resolve.impl.ResolveScopeManagerImpl;
import com.tyron.fileeditor.api.FileEditor;

import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.mock.MockProject;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.project.CodeAssistProject;
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.ResolveScopeManager;
import org.jetbrains.kotlin.org.picocontainer.PicoContainer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainViewModel extends ViewModel {

    /**
     * The files currently opened in the editor
     */
    private MutableLiveData<List<FileEditor>> mFiles;

    /**
     * Whether the current completion engine is indexing
     */
    private MutableLiveData<Boolean> mIndexing;

    /**
     * The text shown on the subtitle of the toolbar
     */
    private MutableLiveData<String> mCurrentState;

    private final MutableLiveData<String> mToolbarTitle = new MutableLiveData<>();

    /**
     * The current position of the CodeEditor
     */
    private final CustomMutableLiveData<Integer> currentPosition = new CustomMutableLiveData<>(-1);

    private final MutableLiveData<Integer> mBottomSheetState =
            new MutableLiveData<>(BottomSheetBehavior.STATE_COLLAPSED);

    private final MutableLiveData<Boolean> mDrawerState = new MutableLiveData<>(false);


    private final JavaCoreProjectEnvironment projectEnvironment;

    public MainViewModel(String projectPath) {
        Disposable disposable = Disposer.newDisposable();
        CoreApplicationEnvironment app =
                ApplicationLoader.getInstance().getCoreApplicationEnvironment();
        projectEnvironment = new JavaCoreProjectEnvironment(disposable, app) {
            @NonNull
            @Override
            protected CodeAssistProject createProject(@NonNull PicoContainer parent,
                                                @NonNull Disposable parentDisposable) {
                VirtualFile fileByPath = app.getLocalFileSystem().findFileByPath(projectPath);
                return new CodeAssistProject(parent, parentDisposable);
            }

            @Override
            protected @NonNull ResolveScopeManager createResolveScopeManager(
                    @NonNull PsiManager psiManager) {
                return new ResolveScopeManagerImpl(getProject());
            }
        };

        projectEnvironment.addJarToClassPath(CompletionModule.getAndroidJar());
        projectEnvironment.addJarToClassPath(CompletionModule.getLambdaStubs());
    }

    public MutableLiveData<String> getCurrentState() {
        if (mCurrentState == null) {
            mCurrentState = new MutableLiveData<>(null);
        }
        return mCurrentState;
    }

    public void setCurrentState(@Nullable String message) {
        mCurrentState.setValue(message);
    }

    public LiveData<Boolean> getDrawerState() {
        return mDrawerState;
    }

    public void setDrawerState(boolean isOpen) {
        mDrawerState.setValue(isOpen);
    }

    public LiveData<String> getToolbarTitle() {
        return mToolbarTitle;
    }

    public void setToolbarTitle(String title) {
        mToolbarTitle.setValue(title);
    }

    public LiveData<Integer> getBottomSheetState() {
        return mBottomSheetState;
    }


    public void setBottomSheetState(@BottomSheetBehavior.State int bottomSheetState) {
        mBottomSheetState.setValue(bottomSheetState);
    }

    public MutableLiveData<Boolean> isIndexing() {
        if (mIndexing == null) {
            mIndexing = new MutableLiveData<>(false);
        }
        return mIndexing;
    }

    public void setIndexing(boolean indexing) {
        mIndexing.setValue(indexing);
    }

    public LiveData<List<FileEditor>> getFiles() {
        if (mFiles == null) {
            mFiles = new MutableLiveData<>(new ArrayList<>());
        }
        return mFiles;
    }

    public void setFiles(@NonNull List<FileEditor> files) {
        if (mFiles == null) {
            mFiles = new MutableLiveData<>(new ArrayList<>());
        }
        mFiles.setValue(files);
    }

    public LiveData<Integer> getCurrentPosition() {
        return currentPosition;
    }

    public void setCurrentPosition(int pos) {
        setCurrentPosition(pos, true);
    }

    public void setCurrentPosition(int pos, boolean update) {
        currentPosition.setValue(pos, update);
    }

    @Nullable
    public FileEditor getCurrentFileEditor() {
        List<FileEditor> files = getFiles().getValue();
        if (files == null) {
            return null;
        }

        Integer currentPos = currentPosition.getValue();
        if (currentPos == null || currentPos == -1) {
            return null;
        }

        if (files.size() - 1 < currentPos) {
            return null;
        }

        return files.get(currentPos);
    }

    public void clear() {
        mFiles.setValue(new ArrayList<>());
    }


    /**
     * Opens this file to the editor
     *
     * @param file The fle to be opened
     * @return whether the operation was successful
     */
    public boolean openFile(FileEditor file) {
        setDrawerState(false);

        int index = -1;
        List<FileEditor> value = getFiles().getValue();
        if (value != null) {
            for (int i = 0; i < value.size(); i++) {
                FileEditor editor = value.get(i);
                if (file.getFile().equals(editor.getFile())) {
                    index = i;
                }
            }
        }
        if (index != -1) {
            setCurrentPosition(index);
            return true;
        }
        addFile(file);
        return true;
    }

    public void addFile(FileEditor file) {
        List<FileEditor> files = getFiles().getValue();
        if (files == null) {
            files = new ArrayList<>();
        }
        if (!files.contains(file)) {
            files.add(file);
            mFiles.setValue(files);
        }
        setCurrentPosition(files.indexOf(file));
    }

    public void removeFile(@NonNull File file) {
        List<FileEditor> files = getFiles().getValue();
        if (files == null) {
            return;
        }
        FileEditor find = null;
        for (FileEditor fileEditor : files) {
            if (file.equals(fileEditor.getFile())) {
                find = fileEditor;
            }
        }
        if (find != null) {
            files.remove(find);
            mFiles.setValue(files);
        }
    }

    /**
     * Remove all the files except the given file
     */
    public void removeOthers(File file) {
        List<FileEditor> files = getFiles().getValue();
        if (files != null) {
            FileEditor find = null;
            for (FileEditor fileEditor : files) {
                if (file.equals(fileEditor.getFile())) {
                    find = fileEditor;
                }
            }

            if (find != null) {
                files.clear();
                files.add(find);
                setFiles(files);
            }
        }
    }

    public void initializeProject(Module module) {
        setIndexing(true);
    }
}
