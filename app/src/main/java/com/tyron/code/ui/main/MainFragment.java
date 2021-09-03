package com.tyron.code.ui.main;

import android.annotation.SuppressLint;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.completion.CompletionEngine;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;
import com.tyron.code.service.CompilerService;
import com.tyron.code.service.ILogger;
import com.tyron.code.ui.editor.BottomEditorFragment;
import com.tyron.code.ui.editor.CodeEditorFragment;
import com.tyron.code.ui.editor.language.LanguageManager;
import com.tyron.code.ui.editor.log.LogViewModel;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.wizard.WizardFragment;
import com.tyron.code.util.AndroidUtilities;
import com.tyron.code.util.ApkInstaller;
import com.tyron.resolver.DependencyDownloader;
import com.tyron.resolver.DependencyResolver;
import com.tyron.resolver.DependencyUtils;
import com.tyron.resolver.model.Dependency;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;

public class MainFragment extends Fragment {

    LogViewModel logViewModel;
    private DrawerLayout mRoot;
    private Toolbar mToolbar;
    private LinearProgressIndicator mProgressBar;
    private LinearLayout mContent;
    private FrameLayout mBottomContainer;
    private BottomSheetBehavior mBehavior;
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
    private TabLayout mTabLayout;
    private ViewPager2 mPager;
    private PageAdapter mAdapter;

    private MainViewModel mFilesViewModel;

    public MainFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            Project project = new Project(new File(savedInstanceState.getString("current_project", "")));
            openProject(project, false);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = (DrawerLayout) inflater.inflate(R.layout.main_fragment, container, false);

        mContent = mRoot.findViewById(R.id.content);
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
        mToolbar.inflateMenu(R.menu.code_editor_menu);

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), onBackPressedCallback);
        logViewModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mFilesViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        mFilesViewModel.getFiles().observe(getViewLifecycleOwner(), mAdapter::submitList);
        mFilesViewModel.currentPosition.observe(getViewLifecycleOwner(), mPager::setCurrentItem);
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mRoot.addDrawerListener(new DrawerLayout.DrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View p1, float p) {

            }

            @Override
            public void onDrawerOpened(@NonNull View p1) {
                onBackPressedCallback.setEnabled(true);
            }

            @Override
            public void onDrawerClosed(@NonNull View p1) {
                onBackPressedCallback.setEnabled(mBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED);
            }

            @Override
            public void onDrawerStateChanged(int p1) {

            }
        });

        mTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabUnselected(TabLayout.Tab p1) {
                CodeEditorFragment fragment = (CodeEditorFragment) getChildFragmentManager()
                        .findFragmentByTag("f" + mAdapter.getItemId(p1.getPosition()));
                if (fragment != null) {
                    fragment.save();
                }
            }

            @Override
            public void onTabReselected(TabLayout.Tab p1) {
                PopupMenu popup = new PopupMenu(requireActivity(), p1.view);
                popup.getMenu().add(0, 0,  1, "Close");
                popup.getMenu().add(0, 1, 2, "Close others");
                popup.getMenu().add(0, 2, 3, "Close all");
                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 0: mFilesViewModel.removeFile(mFilesViewModel.getCurrentFile()); break;
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

            }
        });
        mTabLayout.setVisibility(View.GONE);
        new TabLayoutMediator(mTabLayout, mPager, (tab, pos) -> tab.setText(mAdapter.getItem(pos).getName())).attach();

        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                mFilesViewModel.updateCurrentPosition(position);
            }
        });

        mToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.debug_create) {

                WizardFragment fragment = new WizardFragment();
                getParentFragmentManager().beginTransaction()
                        .add(R.id.fragment_container, fragment, "wizard_fragment")
                        .commit();

                return true;
            } else if (item.getItemId() == R.id.action_open) {
                final EditText et = new EditText(requireContext());
                et.setHint("Project root directory");

                @SuppressLint("RestrictedApi")
                AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.CodeEditorDialog)
                        .setTitle("Create a project")
                        .setNegativeButton("cancel", null)
                        .setPositiveButton("create", (i, which) -> {
                            File file = new File(et.getText().toString());
                            Project project = new Project(file);
                            if (project.isValidProject()) {
                                openProject(project);
                            } else {
                                ApplicationLoader.showToast("The selected directory is not a valid project directory");
                            }
                        })
                        .setView(et, 24, 0, 24, 0)
                        .create();

                dialog.show();
            } else if (item.getItemId() == R.id.debug_refresh) {
                Project project = FileManager.getInstance().getCurrentProject();

                if (project != null) {
                    project.clear();
                    openProject(project, true);
                }
            } else if (item.getItemId() == R.id.action_run) {
                compile();
            } else if (item.getItemId() == R.id.action_format) {
                File file = mAdapter.getItem(mPager.getCurrentItem());
                if (file != null) {
                    CodeEditorFragment fragment = (CodeEditorFragment) getChildFragmentManager()
                            .findFragmentByTag("f" + file.getAbsolutePath().hashCode());
                    if (fragment != null) {
                        fragment.format();
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
                switch (state) {
                    case BottomSheetBehavior.STATE_COLLAPSED:
                        onBackPressedCallback.setEnabled(false);
                        break;
                    case BottomSheetBehavior.STATE_EXPANDED:
                        onBackPressedCallback.setEnabled(true);
                    case BottomSheetBehavior.STATE_DRAGGING:
                    case BottomSheetBehavior.STATE_HALF_EXPANDED:
                    case BottomSheetBehavior.STATE_HIDDEN:
                    case BottomSheetBehavior.STATE_SETTLING:
                        break;
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
                    .commit();
        }

        if (FileManager.getInstance().getCurrentProject() != null) {
            mToolbar.setTitle(FileManager.getInstance().getCurrentProject().mRoot.getName());
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        saveAll();

        Project current = FileManager.getInstance().getCurrentProject();
        if (current != null) {
            outState.putString("current_project", current.mRoot.getAbsolutePath());
        }
    }

    public void openFile(File file) {
        if (!LanguageManager.getInstance().supports(file)) {
            return;
        }

        int pos = mAdapter.getPosition(file);
        if (pos != -1) {
            mPager.setCurrentItem(pos);
        } else {
            mFilesViewModel.addFile(file);
            mFilesViewModel.updateCurrentPosition(mAdapter.getPosition(file));
        }

        mRoot.closeDrawer(GravityCompat.START, true);
    }

    public void openProject(Project project) {
        openProject(project, false);
    }

    public void openProject(Project proj, boolean downloadLibs) {

        if (!proj.isValidProject()) {
            ApplicationLoader.showToast("Invalid project directory");
            return;
        }

        Fragment fragment = getChildFragmentManager().findFragmentByTag("file_manager");
        if (fragment instanceof TreeFileManagerFragment) {
            ((TreeFileManagerFragment) fragment).refresh();
        }

        mProgressBar.setVisibility(View.VISIBLE);
        mToolbar.setTitle(proj.mRoot.getName());

        Executors.newSingleThreadExecutor().execute(() -> {

            if (downloadLibs) {
                requireActivity().runOnUiThread(() -> mToolbar.setSubtitle("Resolving dependencies"));

                // this is the existing libraries from app/libs
                Set<Dependency> libs = DependencyUtils.fromLibs(proj.getLibraryDirectory());

                // dependencies parsed from the build.gradle file
                Set<Dependency> dependencies = new HashSet<>();
                try {
                    dependencies.addAll(DependencyUtils.parseGradle(new File(proj.mRoot, "app/build.gradle")));
                } catch (Exception exception) {
                    //TODO: handle parse error
                    exception.printStackTrace();
                }

                DependencyResolver resolver = new DependencyResolver(dependencies, proj.getLibraryDirectory());
                resolver.addResolvedLibraries(libs);
                dependencies = resolver.resolveMain();
                logViewModel.d(LogViewModel.BUILD_LOG, "Resolved dependencies: " + dependencies);

                requireActivity().runOnUiThread(() -> mToolbar.setSubtitle("Downloading dependencies"));
                logViewModel.d(LogViewModel.BUILD_LOG, "Downloading dependencies");
                DependencyDownloader downloader = new DependencyDownloader(libs, proj.getLibraryDirectory());
                try {
                    downloader.download(dependencies);
                } catch (IOException e) {
                    logViewModel.e(LogViewModel.BUILD_LOG, e.getMessage());
                }
            }

            requireActivity().runOnUiThread(() -> mToolbar.setSubtitle("Indexing"));

            FileManager.getInstance().openProject(proj);
            CompletionEngine.getInstance().index(proj, () -> {
                if (mProgressBar != null) {
                    mProgressBar.setVisibility(View.GONE);
                }
                if (mToolbar != null) {
                    mToolbar.setSubtitle(null);
                }
            });
        });
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

    private void compile() {
        saveAll();

        ILogger logger = new ILogger() {
            @Override
            public void error(String message) {
                logViewModel.e(LogViewModel.BUILD_LOG, message);
            }

            @Override
            public void warning(String message) {
                logViewModel.w(LogViewModel.BUILD_LOG, message);
            }

            @Override
            public void debug(String message) {
                logViewModel.d(LogViewModel.BUILD_LOG, message);
            }
        };

        mProgressBar.setVisibility(View.VISIBLE);
        mToolbar.setSubtitle("Compiling");
        logViewModel.clear(LogViewModel.BUILD_LOG);

        requireActivity().startService(new Intent(requireContext(), CompilerService.class));
        requireActivity().bindService(new Intent(requireContext(), CompilerService.class),
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                        CompilerService.CompilerBinder binder = (CompilerService.CompilerBinder) iBinder;
                        binder.getCompilerService().setLogger(logger);
                        binder.getCompilerService().setOnResultListener((success, message) -> {
                            requireActivity().runOnUiThread(() -> {
                                if (mToolbar != null) {
                                    mToolbar.setSubtitle(null);
                                }
                                if (mProgressBar != null) {
                                    AndroidUtilities.hideKeyboard(mProgressBar);
                                    mProgressBar.setVisibility(View.GONE);
                                }
                                if (!success) {
                                    logger.error(message);
                                    mBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                                }
                                if (success && getActivity() != null) {
                                    File file = new File(FileManager.getInstance().getCurrentProject().getBuildDirectory(), "bin/signed.apk");
                                    ApkInstaller.installApplication(requireActivity(), file.getAbsolutePath());
                                }
                            });
                        });

                        binder.getCompilerService().compile();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName componentName) {
                        if (mToolbar != null) {
                            mToolbar.setSubtitle(null);
                        }
                        if (mProgressBar != null) {
                            AndroidUtilities.hideKeyboard(mProgressBar);
                            mProgressBar.setVisibility(View.GONE);
                        }
                    }
                }, Context.BIND_AUTO_CREATE);
    }

    private class PageAdapter extends FragmentStateAdapter {

        private final List<File> data = new ArrayList<>();

        public PageAdapter(FragmentManager fm, Lifecycle lifecycle) {
            super(fm, lifecycle);
        }

        public void submitList(List<File> files) {
            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return data.size();
                }

                @Override
                public int getNewListSize() {
                    return files.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return Objects.equals(data.get(oldItemPosition), files.get(newItemPosition));
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    return Objects.equals(data.get(oldItemPosition), files.get(newItemPosition));
                }
            });
            data.clear();
            data.addAll(files);
            result.dispatchUpdatesTo(this);

            mTabLayout.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
        }

        public void submitFile(File file) {
            mTabLayout.setVisibility(View.VISIBLE);
            data.add(file);
            notifyItemInserted(data.size());
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        @NonNull
        @Override
        public Fragment createFragment(int p1) {
            return CodeEditorFragment.newInstance(data.get(p1));
        }

        public File getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            if (position > data.size()) {
                return -1;
            }
            return data.get(position).getAbsolutePath().hashCode();
        }

        public int getPosition(File file) {
            if (containsItem(file.getAbsolutePath().hashCode())) {
                return data.indexOf(file);
            }
            return -1;
        }

        public void removeItem(int position) {
            if (position > data.size()) {
                return;
            }
            data.remove(position);
            notifyItemRemoved(position);
        }

        public List<File> getItems() {
            return data;
        }

        @Override
        public boolean containsItem(long itemId) {
            for (File file : data) {
                if (file.getAbsolutePath().hashCode() == itemId) {
                    return true;
                }
            }
            return false;
        }
    }
}
