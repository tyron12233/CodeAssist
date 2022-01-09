package com.tyron.code.ui.main;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.transition.MaterialSharedAxis;
import com.google.gson.Gson;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ui.library.LibraryManagerFragment;
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
import com.tyron.code.ui.settings.SettingsActivity;
import com.tyron.completion.java.provider.CompletionEngine;

import org.openjdk.javax.tools.Diagnostic;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MainFragment extends Fragment implements ProjectManager.OnProjectOpenListener {

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
                if (mMainViewModel.getDrawerState().getValue()) {
                    mMainViewModel.setDrawerState(false);
                }
            }
        }
    };
    private Project mProject;
    private CompilerServiceConnection mServiceConnection;
    private IndexServiceConnection mIndexServiceConnection;

    public MainFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));

        requireActivity().getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);

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
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mRoot = inflater.inflate(R.layout.main_fragment, container, false);

        mProgressBar = mRoot.findViewById(R.id.progressbar);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.GONE);

        mToolbar = mRoot.findViewById(R.id.toolbar);
        mToolbar.setNavigationIcon(R.drawable.ic_baseline_menu_24);
        mToolbar.inflateMenu(R.menu.code_editor_menu);

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

        mToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.debug_refresh) {
                saveAll();
                if (!mServiceConnection.isCompiling()) {
                    Project project = ProjectManager.getInstance().getCurrentProject();
                    if (project != null) {
                        openProject(project);
                    }
                }
            } else if (item.getItemId() == R.id.action_build_debug) {
                compile(BuildType.DEBUG);
            } else if (item.getItemId() == R.id.action_build_release) {
                compile(BuildType.RELEASE);
            } else if (item.getItemId() == R.id.action_build_aab) {
                compile(BuildType.AAB);
            } else if (item.getItemId() == R.id.action_format) {
                getChildFragmentManager().setFragmentResult(EditorContainerFragment.FORMAT_KEY,
                        Bundle.EMPTY);
            } else if (item.getItemId() == R.id.menu_settings) {
                Intent intent = new Intent();
                intent.setClass(requireActivity(), SettingsActivity.class);
                startActivity(intent);
                return true;
            } else if (item.getItemId() == R.id.menu_preview_layout) {
                getChildFragmentManager().setFragmentResult(EditorContainerFragment.PREVIEW_KEY,
                        Bundle.EMPTY);
            } else if (item.getItemId() == R.id.library_manager) {
                FragmentManager fm = getParentFragmentManager();
                if (fm.findFragmentByTag(LibraryManagerFragment.TAG) == null) {
                    String path = mProject.getMainModule().getRootFile().getAbsolutePath();
                    Fragment fragment = LibraryManagerFragment.newInstance(path);
                    getParentFragmentManager().beginTransaction()
                            .add(R.id.fragment_container, fragment, LibraryManagerFragment.TAG)
                            .addToBackStack(LibraryManagerFragment.TAG).commit();
                }
            }

            return false;
        });

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
        }
        mMainViewModel.isIndexing().observe(getViewLifecycleOwner(), indexing -> {
            mProgressBar.setVisibility(indexing ? View.VISIBLE : View.GONE);
            CompletionEngine.setIndexing(indexing);
        });
        mMainViewModel.getCurrentState().observe(getViewLifecycleOwner(), mToolbar::setSubtitle);
        mMainViewModel.getToolbarTitle().observe(getViewLifecycleOwner(), mToolbar::setTitle);
        if (mRoot instanceof DrawerLayout) {
            mMainViewModel.getDrawerState().observe(getViewLifecycleOwner(), isOpen -> {
                if (isOpen) {
                    ((DrawerLayout) mRoot).open();
                } else {
                    ((DrawerLayout) mRoot).close();
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ProjectManager.getInstance().removeOnProjectOpenListener(this);

        if (mLogReceiver != null) {
            requireActivity().unregisterReceiver(mLogReceiver);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mServiceConnection.setShouldShowNotification(true);
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
    public void openFile(File file) {
        mMainViewModel.openFile(file);
    }

    public void openProject(Project project) {
        if (CompletionEngine.isIndexing()) {
            return;
        }
        mProject = project;
        mIndexServiceConnection.setProject(project);

        mMainViewModel.setToolbarTitle(project.getRootFile().getName());
        mMainViewModel.setIndexing(true);
        CompletionEngine.setIndexing(true);

        mFileViewModel.refreshNode(project.getRootFile());

        Intent intent = new Intent(requireContext(), IndexService.class);
        requireActivity().startService(intent);
        requireActivity().bindService(intent, mIndexServiceConnection, Context.BIND_IMPORTANT);
    }

    private void saveAll() {
        if (mProject == null) {
            return;
        }

        if (CompletionEngine.isIndexing()) {
            return;
        }

        getChildFragmentManager().setFragmentResult(EditorContainerFragment.SAVE_ALL_KEY,
                Bundle.EMPTY);

        ProjectSettings settings = mProject.getSettings();
        if (settings == null) {
            return;
        }

        List<File> items = mMainViewModel.getFiles().getValue();
        if (items != null) {
            String itemString =
                    new Gson().toJson(items.stream().map(File::getAbsolutePath).collect(Collectors.toList()));
            settings.edit().putString(ProjectSettings.SAVED_EDITOR_FILES, itemString).apply();
        }
    }

    private void compile(BuildType type) {
        if (mServiceConnection.isCompiling() || CompletionEngine.isIndexing()) {
            return;
        }

        mServiceConnection.setBuildType(type);
        saveAll();

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
                    String type = intent.getExtras().getString("type", "DEBUG");
                    String message = intent.getExtras().getString("message", "No message provided");
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
            requireActivity().registerReceiver(mLogReceiver,
                    new IntentFilter(((AndroidModule) module).getPackageName() + ".LOG"));
        }
    }
}
