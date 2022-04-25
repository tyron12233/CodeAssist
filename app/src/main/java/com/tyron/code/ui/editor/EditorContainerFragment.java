package com.tyron.code.ui.editor;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ViewSwitcher;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.transition.TransitionManager;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.transition.MaterialFade;
import com.google.android.material.transition.MaterialFadeThrough;
import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.util.DataContextUtils;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.listener.FileListener;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.ui.editor.adapter.PageAdapter;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorFragment;
import com.tyron.code.ui.editor.impl.text.rosemoe.ContentWrapper;
import com.tyron.code.ui.editor.impl.xml.LayoutTextEditorFragment;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.UniqueNameBuilder;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.util.EditorUtil;
import com.tyron.fileeditor.api.FileDocumentManager;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.fileeditor.api.FileEditorManager;
import com.tyron.fileeditor.api.TextEditor;

import org.apache.commons.vfs2.FileObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class EditorContainerFragment extends Fragment implements FileListener,
        ProjectManager.OnProjectOpenListener, SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String SAVE_ALL_KEY = "saveAllEditors";
    public static final String PREVIEW_KEY = "previewEditor";
    public static final String FORMAT_KEY = "formatEditor";

    private TabLayout mTabLayout;
    private FrameLayout mContainer;
    private BottomSheetBehavior<View> mBehavior;

    private MainViewModel mMainViewModel;

    private FileEditorManager mFileEditorManager;
    private SharedPreferences pref;
    private final List<FileEditor> mEditors = new ArrayList<>();

    private final OnBackPressedCallback mOnBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pref = ApplicationLoader.getDefaultPreferences();
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mOnBackPressedCallback);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt("bottom_sheet_state", mBehavior.getState());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        CoordinatorLayout root = (CoordinatorLayout) inflater
                .inflate(R.layout.editor_container_fragment, container, false);
        mContainer = root.findViewById(R.id.viewpager);
        ((FileEditorManagerImpl) FileEditorManagerImpl.getInstance())
                .attach(mMainViewModel, getChildFragmentManager());
        mTabLayout = root.findViewById(R.id.tablayout);
        mTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabUnselected(TabLayout.Tab p1) {
                FileEditor currentFileEditor = mMainViewModel.getCurrentFileEditor();
                if (currentFileEditor instanceof TextEditor) {
                    FileDocumentManager instance = FileDocumentManager.getInstance();
                    instance.saveContent(((TextEditor) currentFileEditor).getContent());
                }
            }

            @Override
            public void onTabReselected(TabLayout.Tab p1) {
                PopupMenu popup = new PopupMenu(requireActivity(), p1.view);

                DataContext dataContext = DataContextUtils.getDataContext(mTabLayout);
                dataContext.putData(CommonDataKeys.PROJECT,
                        ProjectManager.getInstance().getCurrentProject());
                dataContext.putData(CommonDataKeys.FRAGMENT, EditorContainerFragment.this);
                dataContext.putData(MainFragment.MAIN_VIEW_MODEL_KEY, mMainViewModel);
                dataContext.putData(CommonDataKeys.FILE_EDITOR_KEY,
                        mMainViewModel.getCurrentFileEditor());

                ActionManager.getInstance().fillMenu(
                        dataContext,
                        popup.getMenu(),
                        ActionPlaces.EDITOR_TAB,
                        true,
                        false
                );
                popup.show();
            }

            @Override
            public void onTabSelected(TabLayout.Tab p1) {
                updateTab(p1, p1.getPosition());
                mMainViewModel.setCurrentPosition(p1.getPosition(), true);

                ProgressManager.getInstance().runLater(() -> getParentFragmentManager()
                        .setFragmentResult(MainFragment.REFRESH_TOOLBAR_KEY, Bundle.EMPTY), 300);
            }
        });
        View persistentSheet = root.findViewById(R.id.persistent_sheet);
        mBehavior = BottomSheetBehavior.from(persistentSheet);
        mBehavior.setGestureInsetBottomIgnored(true);

        mBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View p1, int state) {
                mMainViewModel.setBottomSheetState(state);
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                if (isAdded()) {
                    Bundle bundle = new Bundle();
                    bundle.putFloat("offset", slideOffset);
                    getChildFragmentManager()
                            .setFragmentResult(BottomEditorFragment.OFFSET_KEY, bundle);
                }
            }
        });
        mBehavior.setHalfExpandedRatio(0.3f);
        mBehavior.setFitToContents(false);

        ProjectManager.getInstance().addOnProjectOpenListener(this);

        if (savedInstanceState != null) {
            restoreViewState(savedInstanceState);
        }
        return root;
    }

    private void updateTab(TabLayout.Tab tab, int pos) {
        List<FileEditor> fileEditors = mMainViewModel.getFiles().getValue();
        if (fileEditors == null) {
            fileEditors = Collections.emptyList();
        }
        List<File> files = fileEditors.stream().map(FileEditor::getFile).collect(Collectors.toList());
        FileEditor currentEditor =
                Objects.requireNonNull(fileEditors).get(pos);
        File current = currentEditor.getFile();

        String text = current != null ?
                EditorUtil.getUniqueTabTitle(current, files)
                : "Unknown";
        if (currentEditor.isModified()) {
            text = "*" + text;
        }

        tab.setText(text);
    }

    @Override
    public void onProjectOpen(Project project) {
        for (Module module : project.getModules()) {
            FileManager fileManager = module.getFileManager();
            fileManager.addSnapshotListener(this);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ApplicationLoader.getDefaultPreferences().registerOnSharedPreferenceChangeListener(this);

        mMainViewModel.getFiles().observe(getViewLifecycleOwner(), files -> {
            if (files.isEmpty()) {
                mContainer.removeAllViews();
            }
            List<FileEditor> oldList = new ArrayList<>(mEditors);
            mEditors.clear();
            mEditors.addAll(files);
            EditorTabUtil.updateTabLayout(mTabLayout, oldList, files);
        });

        mMainViewModel.getCurrentPosition().observe(getViewLifecycleOwner(), position -> {
            mContainer.removeAllViews();

            FileEditor currentFileEditor = mMainViewModel.getCurrentFileEditor();
            if (position == -1 || currentFileEditor == null) {
                return;
            }
            TransitionManager.beginDelayedTransition(mContainer, new MaterialFadeThrough());
            mContainer.addView(currentFileEditor.getView());
        });

        mMainViewModel.getBottomSheetState().observe(getViewLifecycleOwner(), state -> {
            mBehavior.setState(state);
            mOnBackPressedCallback.setEnabled(state == BottomSheetBehavior.STATE_EXPANDED);
        });

//        getParentFragmentManager().setFragmentResultListener(SAVE_ALL_KEY,
//        getViewLifecycleOwner(),
//                                                             (requestKey, result) -> saveAll());
//        getParentFragmentManager().setFragmentResultListener(PREVIEW_KEY, getViewLifecycleOwner(),
//                                                             ((requestKey, result) ->
//                                                             previewCurrent()));
//        getParentFragmentManager().setFragmentResultListener(FORMAT_KEY, getViewLifecycleOwner(),
//                                                             (((requestKey, result) ->
//                                                             formatCurrent())));
    }

    private void restoreViewState(@NonNull Bundle state) {
        int behaviorState = state.getInt("bottom_sheet_state", BottomSheetBehavior.STATE_COLLAPSED);
        mMainViewModel.setBottomSheetState(behaviorState);
        Bundle floatOffset = new Bundle();
        floatOffset.putFloat("offset", behaviorState == BottomSheetBehavior.STATE_EXPANDED ? 1 : 0f);
        getChildFragmentManager().setFragmentResult(BottomEditorFragment.OFFSET_KEY, floatOffset);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        ProjectManager projectManager = ProjectManager.getInstance();
        Project currentProject = projectManager.getCurrentProject();
        if (currentProject != null) {
            for (Module module : currentProject.getModules()) {
                FileManager fileManager = module.getFileManager();
                fileManager.removeSnapshotListener(this);
            }
        }
    }

//    private void formatCurrent() {
//        String tag = "f" + mAdapter.getItemId(mPager.getCurrentItem());
//        Fragment fragment = getChildFragmentManager().findFragmentByTag(tag);
//        if (fragment instanceof CodeEditorFragment) {
//            ((CodeEditorFragment) fragment).format();
//        }
//    }
//
//    private void previewCurrent() {
//        String tag = "f" + mAdapter.getItemId(mPager.getCurrentItem());
//        Fragment fragment = getChildFragmentManager().findFragmentByTag(tag);
//        if (fragment instanceof LayoutTextEditorFragment) {
//            ((LayoutTextEditorFragment) fragment).preview();
//        }
//    }
//
//    private void saveAll() {
//        for (int i = 0; i < mAdapter.getItemCount(); i++) {
//            String tag = "f" + mAdapter.getItemId(i);
//            Fragment fragment = getChildFragmentManager().findFragmentByTag(tag);
//            if (fragment instanceof Savable) {
//                ((Savable) fragment).save(true);
//            }
//        }
//    }

    @Override
    public void onSnapshotChanged(File file, CharSequence contents) {
        if (mTabLayout == null) {
            return;
        }
        List<FileEditor> editors = mMainViewModel.getFiles().getValue();
        if (editors == null) {
            return;
        }

        int found = -1;
        for (int i = 0; i < editors.size(); i++) {
            FileEditor editor = editors.get(i);
            if (file.equals(editor.getFile())) {
                found = i;
                break;
            }
        }

        if (found == -1) {
            return;
        }

        TabLayout.Tab tab = mTabLayout.getTabAt(found);
        if (tab == null) {
            return;
        }
        updateTab(tab, found);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        switch (key) {
            case SharedPreferenceKeys.EDITOR_TAB_UNIQUE_FILE_NAME:
//                if (mAdapter != null) {
//                    mAdapter.notifyDataSetChanged();
//                }
                break;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ApplicationLoader.getDefaultPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }
}
