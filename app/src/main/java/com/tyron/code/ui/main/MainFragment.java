package com.tyron.code.ui.main;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.ViewUtils;
import androidx.core.view.GravityCompat;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.ViewKt;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.transition.MaterialSharedAxis;
import com.google.gson.Gson;
import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.util.DataContextUtils;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.file.event.RefreshRootEvent;
import com.tyron.code.util.UiUtilsKt;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.fileeditor.api.FileEditorSavedState;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.ProjectSettings;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.code.service.CompilerService;
import com.tyron.code.service.CompilerServiceConnection;
import com.tyron.code.service.IndexService;
import com.tyron.code.service.IndexServiceConnection;
import com.tyron.code.ui.editor.EditorContainerFragment;
import com.tyron.code.ui.file.FileViewModel;
import com.tyron.completion.java.provider.CompletionEngine;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import javax.tools.Diagnostic;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MainFragment extends Fragment implements ProjectManager.OnProjectOpenListener {

    public static final String REFRESH_TOOLBAR_KEY = "refreshToolbar";

    public static final Key<CompileCallback> COMPILE_CALLBACK_KEY = Key.create("compileCallback");
    public static final Key<IndexCallback> INDEX_CALLBACK_KEY = Key.create("indexCallbackKey");
    public static final Key<MainViewModel> MAIN_VIEW_MODEL_KEY = Key.create("mainViewModel");

    private Handler mHandler;

    public static MainFragment newInstance(@NonNull String projectPath) {
        Bundle bundle = new Bundle();
        bundle.putString("project_path", projectPath);

        MainFragment fragment = new MainFragment();
        fragment.setArguments(bundle);

        return fragment;
    }

    private LogViewModel mLogViewModel;
    private MainViewModel mMainViewModel;
    private FileViewModel mFileViewModel;

    private ProjectManager mProjectManager;
    private View mRoot;
    private Toolbar mToolbar;
    private LinearProgressIndicator mProgressBar;
    private BroadcastReceiver mLogReceiver;

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (mRoot instanceof DrawerLayout) {
                //noinspection ConstantConditions
                if (mMainViewModel.getDrawerState()
                        .getValue()) {
                    mMainViewModel.setDrawerState(false);
                }
            }
        }
    };
    private Project mProject;
    private CompilerServiceConnection mServiceConnection;
    private IndexServiceConnection mIndexServiceConnection;

    private final CompileCallback mCompileCallback = this::compile;
    private final IndexCallback mIndexCallback = this::openProject;


    public MainFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));

        requireActivity().getOnBackPressedDispatcher()
                .addCallback(this, onBackPressedCallback);

        String projectPath = requireArguments().getString("project_path");
        mProject = new Project(new File(projectPath));
        mProjectManager = ProjectManager.getInstance();
        mProjectManager.addOnProjectOpenListener(this);
        mLogViewModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        mFileViewModel = new ViewModelProvider(requireActivity()).get(FileViewModel.class);
        mIndexServiceConnection = new IndexServiceConnection(mMainViewModel, mLogViewModel);
        mServiceConnection = new CompilerServiceConnection(mMainViewModel, mLogViewModel);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        mRoot = inflater.inflate(R.layout.main_fragment, container, false);

        mProgressBar = mRoot.findViewById(R.id.progressbar);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.GONE);

        mToolbar = mRoot.findViewById(R.id.toolbar);
        mToolbar.setNavigationIcon(R.drawable.ic_baseline_menu_24);
        UiUtilsKt.addSystemWindowInsetToPadding(mToolbar, false, true, false, false);

        getChildFragmentManager().setFragmentResultListener(REFRESH_TOOLBAR_KEY,
                                                            getViewLifecycleOwner(),
                                                            (key, __) -> refreshToolbar());

        refreshToolbar();

        if (savedInstanceState != null) {
            restoreViewState(savedInstanceState);
        }
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (mRoot instanceof DrawerLayout) {
            DrawerLayout drawerLayout = (DrawerLayout) mRoot;
            mToolbar.setNavigationOnClickListener(v -> {
                if (mRoot instanceof DrawerLayout) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        mMainViewModel.setDrawerState(false);
                    } else if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        mMainViewModel.setDrawerState(true);
                    }
                }
            });
            drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
                @Override
                public void onDrawerOpened(@NonNull View p1) {
                    mMainViewModel.setDrawerState(true);
                    onBackPressedCallback.setEnabled(true);
                }

                @Override
                public void onDrawerClosed(@NonNull View p1) {
                    mMainViewModel.setDrawerState(false);
                    onBackPressedCallback.setEnabled(false);
                }
            });
        } else {
            mToolbar.setNavigationIcon(null);
        }

        File root;
        if (mProject != null) {
            root = mProject.getRootFile();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                root = requireActivity().getExternalFilesDir(null);
            } else {
                root = Environment.getExternalStorageDirectory();
            }
        }
        mFileViewModel.refreshNode(root);

        if (!mProject.equals(mProjectManager.getCurrentProject())) {
            mRoot.postDelayed(() -> openProject(mProject), 200);
        }

        // If the user has changed projects, clear the current opened files
        if (!mProject.equals(mProjectManager.getCurrentProject())) {
            mMainViewModel.setFiles(new ArrayList<>());
            mLogViewModel.clear(LogViewModel.BUILD_LOG);
        }
        mMainViewModel.isIndexing()
                .observe(getViewLifecycleOwner(), indexing -> {
                    mProgressBar.setVisibility(indexing ? View.VISIBLE : View.GONE);
                    CompletionEngine.setIndexing(indexing);
                    refreshToolbar();
                });
        mMainViewModel.getCurrentState()
                .observe(getViewLifecycleOwner(), mToolbar::setSubtitle);
        mMainViewModel.getToolbarTitle()
                .observe(getViewLifecycleOwner(), mToolbar::setTitle);
        if (mRoot instanceof DrawerLayout) {
            mMainViewModel.getDrawerState()
                    .observe(getViewLifecycleOwner(), isOpen -> {
                        if (isOpen) {
                            ((DrawerLayout) mRoot).open();
                        } else {
                            ((DrawerLayout) mRoot).close();
                        }
                    });
        }

        mHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Level level = record.getLevel();
                if (Level.WARNING.equals(level)) {
                    mLogViewModel.w(LogViewModel.IDE, record.getMessage());
                } else if (Level.SEVERE.equals(level)) {
                    mLogViewModel.e(LogViewModel.IDE, record.getMessage());
                } else {
                    mLogViewModel.d(LogViewModel.IDE, record.getMessage());
                }
            }

            @Override
            public void flush() {
                mLogViewModel.clear(LogViewModel.IDE);
            }

            @Override
            public void close() throws SecurityException {
                mLogViewModel.clear(LogViewModel.IDE);
            }
        };
        IdeLog.getLogger().addHandler(mHandler);

        // can be null on tablets
        View navRoot = view.findViewById(R.id.nav_root);

        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, insets) -> {
            if (navRoot != null) {
                ViewCompat.dispatchApplyWindowInsets(navRoot, insets);
            }
            ViewGroup viewGroup = (ViewGroup) mRoot;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                if (child == navRoot) {
                    continue;
                }

                ViewCompat.dispatchApplyWindowInsets(child, insets);
            }
            return ViewCompat.onApplyWindowInsets(v, insets);
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ProjectManager manager = ProjectManager.getInstance();
        Project project = manager.getCurrentProject();
        if (project != null) {
            for (Module module : project.getModules()) {
                module.getFileManager()
                        .shutdown();
            }
        }
        manager.removeOnProjectOpenListener(this);

        if (mLogReceiver != null) {
            requireActivity().unregisterReceiver(mLogReceiver);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (mHandler != null) {
            IdeLog.getLogger().removeHandler(mHandler);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        saveAll();
        mServiceConnection.setShouldShowNotification(true);
    }

    @Override
    public void onResume() {
        super.onResume();

        refreshToolbar();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        saveAll();
        if (mRoot instanceof DrawerLayout) {
            outState.putBoolean("start_drawer_state",
                                ((DrawerLayout) mRoot).isDrawerOpen(GravityCompat.START));
        }
        super.onSaveInstanceState(outState);
    }

    private void restoreViewState(@NonNull Bundle state) {
        if (mRoot instanceof DrawerLayout) {
            boolean b = state.getBoolean("start_drawer_state", false);
            mMainViewModel.setDrawerState(b);
        }
    }

    /**
     * Tries to open a file into the editor
     *
     * @param file file to open
     */
    public void openFile(FileEditor file) {
        mMainViewModel.openFile(file);
    }

    public void openProject(@NonNull Project project) {
        if (CompletionEngine.isIndexing()) {
            return;
        }
        if (getContext() == null) {
            return;
        }

        if (project.equals(ProjectManager.getInstance().getCurrentProject())) {
            saveAll(false);
            project.getSettings().refresh();
        }

        IndexServiceConnection.restoreFileEditors(project, mMainViewModel);

        mProject = project;
        mIndexServiceConnection.setProject(project);

        mMainViewModel.setToolbarTitle(project.getRootFile()
                                               .getName());
        mMainViewModel.setIndexing(true);
        CompletionEngine.setIndexing(true);

        RefreshRootEvent event = new RefreshRootEvent(project.getRootFile());
        ApplicationLoader.getInstance().getEventManager().dispatchEvent(event);

        Intent intent = new Intent(requireContext(), IndexService.class);
        requireActivity().startService(intent);
        requireActivity().bindService(intent, mIndexServiceConnection, Context.BIND_IMPORTANT);
    }

    private void saveAll() {
        saveAll(true);
    }

    private void saveAll(boolean async) {
        if (mProject == null) {
            return;
        }

        if (CompletionEngine.isIndexing()) {
            return;
        }

        Collection<Module> modules = mProject.getModules();
        modules.forEach(it -> it.getFileManager().saveContents());

        getChildFragmentManager().setFragmentResult(EditorContainerFragment.SAVE_ALL_KEY,
                                                    Bundle.EMPTY);

        ProjectSettings settings = mProject.getSettings();
        if (settings == null) {
            return;
        }

        List<FileEditor> items = mMainViewModel.getFiles()
                .getValue();
        if (items != null) {
            String itemString = new Gson().toJson(items.stream()
                                                          .map(FileEditorSavedState::new)
                                                          .collect(Collectors.toList()));
            SharedPreferences.Editor editor = settings.edit()
                    .putString(ProjectSettings.SAVED_EDITOR_FILES, itemString);
            if (async) {
                editor.apply();
            } else {
                editor.commit();
            }
        }
    }

    private void compile(BuildType type) {
        if (mServiceConnection.isCompiling() || CompletionEngine.isIndexing()) {
            return;
        }

        saveAll();
        mServiceConnection.setBuildType(type);

        mMainViewModel.setCurrentState(getString(R.string.compilation_state_compiling));
        mMainViewModel.setIndexing(true);
        mLogViewModel.clear(LogViewModel.BUILD_LOG);

        requireActivity().startService(new Intent(requireContext(), CompilerService.class));
        requireActivity().bindService(new Intent(requireContext(), CompilerService.class),
                                      mServiceConnection, Context.BIND_IMPORTANT);
    }

    @Override
    public void onProjectOpen(Project project) {
        Module module = project.getMainModule();
        if (module instanceof AndroidModule) {
            mLogReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String type = intent.getExtras()
                            .getString("type", "DEBUG");
                    String message = intent.getExtras()
                            .getString("message", "No message provided");
                    DiagnosticWrapper wrapped = ILogger.wrap(message);

                    switch (type) {
                        case "DEBUG":
                        case "INFO":
                            wrapped.setKind(Diagnostic.Kind.NOTE);
                            mLogViewModel.d(LogViewModel.APP_LOG, wrapped);
                            break;
                        case "ERROR":
                            wrapped.setKind(Diagnostic.Kind.ERROR);
                            mLogViewModel.e(LogViewModel.APP_LOG, wrapped);
                            break;
                        case "WARNING":
                            wrapped.setKind(Diagnostic.Kind.WARNING);
                            mLogViewModel.w(LogViewModel.APP_LOG, wrapped);
                            break;
                    }
                }
            };
            String packageName = ((AndroidModule) module).getPackageName();
            if (packageName != null) {
                requireActivity().registerReceiver(mLogReceiver,
                                                   new IntentFilter(packageName + ".LOG"));
            } else {
                mLogReceiver = null;
            }
        }

        ProgressManager.getInstance().runLater(() -> {
            if (getContext() == null) {
                return;
            }
            refreshToolbar();
        });
    }

    private void injectData(DataContext context) {
        Boolean indexing = mMainViewModel.isIndexing().getValue();
        // to please lint
        if (indexing == null) {
            indexing = true;
        }
        if (!indexing) {
            context.putData(CommonDataKeys.PROJECT, ProjectManager.getInstance().getCurrentProject());
        }
        context.putData(CommonDataKeys.ACTIVITY, getActivity());
        context.putData(MAIN_VIEW_MODEL_KEY, mMainViewModel);
        context.putData(COMPILE_CALLBACK_KEY, mCompileCallback);
        context.putData(INDEX_CALLBACK_KEY, mIndexCallback);
        context.putData(CommonDataKeys.FILE_EDITOR_KEY, mMainViewModel.getCurrentFileEditor());
    }

    public void refreshToolbar() {
        mToolbar.getMenu()
                .clear();

        DataContext context = DataContextUtils.getDataContext(mToolbar);
        injectData(context);

        Instant now = Instant.now();
        ActionManager.getInstance()
                .fillMenu(context, mToolbar.getMenu(), ActionPlaces.MAIN_TOOLBAR, false, true);
        Log.d("ActionManager", "fillMenu() took " +
                               Duration.between(now, Instant.now())
                                       .toMillis());
    }
}
