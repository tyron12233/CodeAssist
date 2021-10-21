package com.tyron.code.ui.main;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.transition.MaterialSharedAxis;
import com.tyron.ProjectManager;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.Project;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.service.CompilerService;
import com.tyron.code.service.IndexService;
import com.tyron.code.ui.editor.BottomEditorFragment;
import com.tyron.code.ui.editor.CodeEditorFragment;
import com.tyron.code.ui.editor.language.LanguageManager;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.main.adapter.PageAdapter;
import com.tyron.code.ui.settings.SettingsActivity;
import com.tyron.code.util.AndroidUtilities;
import com.tyron.code.util.ApkInstaller;
import com.tyron.code.util.ProjectUtils;
import com.tyron.completion.provider.CompletionEngine;

import java.io.File;
import java.util.List;

public class MainFragment extends Fragment {

    public static MainFragment newInstance(@NonNull String projectPath) {
        Bundle bundle = new Bundle();
        bundle.putString("project_path", projectPath);

        MainFragment fragment = new MainFragment();
        fragment.setArguments(bundle);

        return fragment;
    }

    private ProjectManager mProjectManager;
    private LogViewModel logViewModel;
    private DrawerLayout mRoot;
    private Toolbar mToolbar;
    private LinearProgressIndicator mProgressBar;
    private FrameLayout mBottomContainer;
    private BottomSheetBehavior<View> mBehavior;
    private TabLayout mTabLayout;
    private ViewPager2 mPager;
    private PageAdapter mAdapter;
    private MainViewModel mFilesViewModel;
    OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (mRoot.isOpen()) {
                mRoot.closeDrawer(GravityCompat.START, true);
                return;
            }
            if (mBehavior != null) {
                mBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        }
    };
    private Project mProject;
    private BuildType mBuildType;
    private CompilerService.CompilerBinder mBinder;
    private IndexService.IndexBinder mIndexBinder;
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            ILogger logger = ILogger.wrap(logViewModel);

            mBinder = (CompilerService.CompilerBinder) iBinder;
            mBinder.getCompilerService().setLogger(logger);
            mBinder.getCompilerService().setShouldShowNotification(false);
            mBinder.getCompilerService().setOnResultListener((success, message) -> requireActivity().runOnUiThread(() -> {
                mFilesViewModel.setCurrentState(null);

                if (mProgressBar != null) {
                    AndroidUtilities.hideKeyboard(mProgressBar);
                }
                mFilesViewModel.setIndexing(false);

                if (!success) {
                    logger.error(message);

                    if (mBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {
                        mBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                    }
                }

                if (getActivity() != null) {
                    if (success) {
                        logger.debug(message);
                        logViewModel.clear(LogViewModel.APP_LOG);

                        File file = new File(mProjectManager.getCurrentProject().getBuildDirectory(), "bin/signed.apk");
                        mProgressBar.postDelayed(() -> ApkInstaller.installApplication(requireContext(), file.getAbsolutePath()), 400);
                    }

                    mBinder = null;
                    requireActivity().unbindService(this);
                }
            }));

            if (mBuildType != null) {
                mBinder.getCompilerService().compile(mProjectManager.getCurrentProject(), mBuildType);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBinder = null;
            mFilesViewModel.setCurrentState(null);
            mFilesViewModel.setIndexing(false);
            if (mProgressBar != null) {
                AndroidUtilities.hideKeyboard(mProgressBar);
            }
        }
    };
    private final ServiceConnection mIndexServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mIndexBinder = (IndexService.IndexBinder) iBinder;

            ProjectManager.TaskListener taskListener = new ProjectManager.TaskListener() {
                @Override
                public void onTaskStarted(String message) {
                    if (getActivity() == null) {
                        return;
                    }
                    requireActivity().runOnUiThread(() -> mFilesViewModel.setCurrentState(message));
                }

                @Override
                public void onComplete(boolean success, String message) {
                    if (getActivity() == null) {
                        return;
                    }

                    requireActivity().runOnUiThread(() -> {
                        mFilesViewModel.setIndexing(false);
                        mFilesViewModel.setCurrentState(null);
                        if (!success) {
                            if (mBehavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                                mBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                            }
                        } else {
                            if (mProjectManager.getCurrentProject() != null) {
                                mToolbar.setTitle(mProject.mRoot.getName());
                            }
                        }
                        int pos = mPager.getCurrentItem();
                        Fragment fragment = getChildFragmentManager()
                                .findFragmentByTag("f" + mAdapter.getItemId(pos));
                        if (fragment instanceof CodeEditorFragment) {
                            ((CodeEditorFragment) fragment).analyze();
                        }
                    });
                    requireActivity().runOnUiThread(() -> {
                        Fragment fragment = getChildFragmentManager().findFragmentByTag("file_manager");
                        if (fragment instanceof TreeFileManagerFragment) {
                            ((TreeFileManagerFragment) fragment).setRoot(mProject.mRoot);
                        }
                    });
                }
            };

            mIndexBinder.index(mProject, taskListener, ILogger.wrap(logViewModel));
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mIndexBinder = null;
            mFilesViewModel.setIndexing(false);
            mFilesViewModel.setCurrentState(null);
        }
    };
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
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = (DrawerLayout) inflater.inflate(R.layout.main_fragment, container, false);

        LinearLayout mContent = mRoot.findViewById(R.id.content);
        mBottomContainer = mRoot.findViewById(R.id.persistent_sheet);

        mTabLayout = new TabLayout(requireContext());
        mTabLayout.setBackgroundColor(0xff212121);
        mTabLayout.setSelectedTabIndicatorColor(0xffcc7832);
        mTabLayout.setTabTextColors(0xffffffff, 0xffcc7832);
        mTabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        mContent.addView(mTabLayout, new LinearLayout.LayoutParams(-1, -2));

        mProgressBar = mRoot.findViewById(R.id.progressbar);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.GONE);

        mAdapter = new PageAdapter(getChildFragmentManager(), getLifecycle());

        mPager = new ViewPager2(requireContext());
        mPager.setAdapter(mAdapter);
        mPager.setUserInputEnabled(false);
        mPager.setBackgroundColor(0xff2b2b2b);
        mContent.addView(mPager, new LinearLayout.LayoutParams(-1, -1, 1));

        mToolbar = mRoot.findViewById(R.id.toolbar);
        mToolbar.setNavigationIcon(R.drawable.ic_baseline_menu_24);
        mToolbar.inflateMenu(R.menu.code_editor_menu);

        logViewModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mFilesViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        mFilesViewModel.getFiles().observe(getViewLifecycleOwner(),
                files -> {
                    mAdapter.submitList(files);
                    mTabLayout.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
                });
        mFilesViewModel.currentPosition.observe(getViewLifecycleOwner(), mPager::setCurrentItem);
        mFilesViewModel.isIndexing().observe(getViewLifecycleOwner(), indexing -> mProgressBar.setVisibility(indexing ? View.VISIBLE : View.GONE));
        mFilesViewModel.getCurrentState().observe(getViewLifecycleOwner(), mToolbar::setSubtitle);
        mProjectManager = ProjectManager.getInstance();

        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mToolbar.setNavigationOnClickListener(v -> {
            if (mRoot.isDrawerOpen(GravityCompat.START)) {
                mRoot.closeDrawer(GravityCompat.START, true);
            } else if (!mRoot.isDrawerOpen(GravityCompat.START)) {
                mRoot.openDrawer(GravityCompat.START);
            }
        });
        mRoot.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View p1, float p) {
                File currentFile = mFilesViewModel.getCurrentFile();
                if (currentFile != null) {
                    Fragment fragment = getChildFragmentManager().findFragmentByTag("f" + currentFile.getAbsolutePath().hashCode());
                    if (fragment instanceof CodeEditorFragment) {
                        ((CodeEditorFragment) fragment).hideEditorWindows();
                    }
                }
            }

            @Override
            public void onDrawerOpened(@NonNull View p1) {
                onBackPressedCallback.setEnabled(true);
            }

            @Override
            public void onDrawerClosed(@NonNull View p1) {
                onBackPressedCallback.setEnabled(mBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        mTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabUnselected(TabLayout.Tab p1) {
                Fragment fragment = getChildFragmentManager()
                        .findFragmentByTag("f" + mAdapter.getItemId(p1.getPosition()));
                if (fragment instanceof CodeEditorFragment) {
                    ((CodeEditorFragment) fragment).save();
                }
            }

            @Override
            public void onTabReselected(TabLayout.Tab p1) {
                PopupMenu popup = new PopupMenu(requireActivity(), p1.view);
                popup.getMenu().add(0, 0, 1, "Close");
                popup.getMenu().add(0, 1, 2, "Close others");
                popup.getMenu().add(0, 2, 3, "Close all");
                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 0:
                            mFilesViewModel.removeFile(mFilesViewModel.getCurrentFile());
                            break;
                        case 1:
                            File currentFile = mFilesViewModel.getCurrentFile();
                            List<File> files = mFilesViewModel.getFiles().getValue();
                            if (files != null) {
                                files.clear();
                                files.add(currentFile);
                                mFilesViewModel.setFiles(files);
                            }
                            break;
                        case 2:
                            mFilesViewModel.clear();
                    }

                    return true;
                });
                popup.show();
            }

            @Override
            public void onTabSelected(TabLayout.Tab p1) {
                Fragment fragment = getChildFragmentManager()
                        .findFragmentByTag("f" + mAdapter.getItemId(p1.getPosition()));
                if (fragment instanceof CodeEditorFragment) {
                    ((CodeEditorFragment) fragment).analyze();
                }
            }
        });
        mTabLayout.setVisibility(View.GONE);
        new TabLayoutMediator(mTabLayout, mPager, (tab, pos) -> {
            File file = mAdapter.getItem(pos);
            if (file != null) {
                tab.setText(file.getName());
            } else {
                tab.setText("Unknown");
            }
        }).attach();

        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                mFilesViewModel.updateCurrentPosition(position);
                File current = mFilesViewModel.getCurrentFile();
                mToolbar.getMenu().findItem(R.id.menu_preview_layout)
                        .setVisible(current != null && ProjectUtils.isResourceXMLFile(current));
            }
        });
        mToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.debug_refresh) {
                if (mBinder == null && mIndexBinder == null) {
                    Project project = ProjectManager.getInstance()
                            .getCurrentProject();

                    if (project != null) {
                        project.clear();
                        openProject(project, true);
                    }
                }
            } else if(item.getItemId() == R.id.action_build_debug) {
                compile(BuildType.DEBUG);
            } else if (item.getItemId() == R.id.action_build_release) {
                compile(BuildType.RELEASE);
            } else if (item.getItemId() == R.id.action_format) {
                File file = mAdapter.getItem(mPager.getCurrentItem());
                if (file != null) {
                    CodeEditorFragment fragment = (CodeEditorFragment) getChildFragmentManager()
                            .findFragmentByTag("f" + file.getAbsolutePath().hashCode());
                    if (fragment != null) {
                        fragment.format();
                    }
                }
            } else if (item.getItemId() == R.id.menu_settings) {
                Intent intent = new Intent();
                intent.setClass(requireActivity(), SettingsActivity.class);
                startActivity(intent);
                return true;
            } else if (item.getItemId() == R.id.menu_preview_layout) {
                File currentFile = mFilesViewModel.getCurrentFile();
                if (currentFile != null) {
                    Fragment fragment = getChildFragmentManager().findFragmentByTag("f" + currentFile.getAbsolutePath().hashCode());
                    if (fragment instanceof CodeEditorFragment) {
                        ((CodeEditorFragment) fragment).preview();
                    }
                }
            }

            return false;
        });

        final BottomEditorFragment bottomEditorFragment = BottomEditorFragment.newInstance();
        mBehavior = BottomSheetBehavior.from(mBottomContainer);
        mBehavior.setFitToContents(false);
        mBehavior.setHalfExpandedRatio(0.3f);
        mBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View p1, int state) {
                if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                    onBackPressedCallback.setEnabled(false);
                } else if (state == BottomSheetBehavior.STATE_EXPANDED) {
                    onBackPressedCallback.setEnabled(true);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                bottomEditorFragment.setOffset(slideOffset);
            }
        });

        // Display the persistent fragment
        getChildFragmentManager().beginTransaction()
                .replace(R.id.persistent_sheet, bottomEditorFragment)
                .commit();

        if (getChildFragmentManager().findFragmentByTag("file_manager") == null) {
            File root;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                root = requireActivity().getExternalFilesDir(null);
            } else {
                root = Environment.getExternalStorageDirectory();
            }
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.nav_root, TreeFileManagerFragment.newInstance(root), "file_manager")
                    .commitNow();
        }

        openProject(mProject);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mBinder != null) {
            mBinder.getCompilerService().setShouldShowNotification(true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        saveAll();
    }

    /**
     * Tries to open a file and show the given line and column
     */
    public void openFile(File file, int lineNumber, int column) {
        if (!LanguageManager.getInstance().supports(file)) {
            return;
        }

        int delay = 0;

        int pos = mAdapter.getPosition(file);
        if (pos != -1) {
            mPager.setCurrentItem(pos);
        } else {
            mFilesViewModel.addFile(file);
            mFilesViewModel.updateCurrentPosition(mAdapter.getPosition(file));
            delay = 200;
        }

        mPager.postDelayed(() -> {
            Fragment fragment = getChildFragmentManager().findFragmentByTag("f" + file.getAbsolutePath().hashCode());
            if (fragment instanceof CodeEditorFragment) {
                ((CodeEditorFragment) fragment).setCursorPosition(lineNumber, column);
            }
        }, delay);

        mRoot.closeDrawer(GravityCompat.START, true);
        mBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
    }

    /**
     * Tries to open a file into the editor
     *
     * @param file file to open
     */
    public void openFile(File file) {
        openFile(file, 0, 0);
    }

    public void openProject(Project project) {
        openProject(project, false);
    }

    public void openProject(Project proj, boolean downloadLibs) {

        if (!proj.isValidProject()) {
            ApplicationLoader.showToast("Invalid project directory: " + proj.mRoot);
            return;
        }

        mFilesViewModel.setIndexing(true);
        CompletionEngine.setIndexing(true);

        requireActivity().startService(new Intent(requireContext(), IndexService.class));
        requireActivity().bindService(new Intent(requireContext(), IndexService.class),
                mIndexServiceConnection, Context.BIND_IMPORTANT);
    }

    /**
     * Saves the current opened editor
     */
    private void saveCurrent() {
        int position = mPager.getCurrentItem();
        String tag = "f" + mAdapter.getItemId(position);
        CodeEditorFragment fragment = (CodeEditorFragment) getChildFragmentManager().findFragmentByTag(tag);
        if (fragment != null) {
            fragment.save();
        }
    }

    private void saveAll() {
        for (File file : mAdapter.getItems()) {
            CodeEditorFragment fragment = (CodeEditorFragment) getChildFragmentManager().findFragmentByTag(
                    "f" + file.getAbsolutePath().hashCode()
            );
            if (fragment != null) {
                fragment.save();
            }
        }
    }

    private void compile(BuildType type) {
        if (mBinder != null || mIndexBinder != null) {
            return;
        }

        mBuildType = type;
        saveAll();

        mFilesViewModel.setCurrentState("Compiling");
        mFilesViewModel.setIndexing(true);
        logViewModel.clear(LogViewModel.BUILD_LOG);

        requireActivity().startService(new Intent(requireContext(), CompilerService.class));
        requireActivity().bindService(new Intent(requireContext(), CompilerService.class),
                mServiceConnection, Context.BIND_IMPORTANT);
    }
}
