package com.tyron.code.main;

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
import com.tyron.code.editor.BottomEditorFragment;
import com.tyron.code.editor.CodeEditorFragment;
import com.tyron.code.editor.language.LanguageManager;
import com.tyron.code.editor.log.LogViewModel;
import com.tyron.code.file.FileManagerFragment;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;
import com.tyron.code.service.CompilerService;
import com.tyron.code.service.ILogger;
import com.tyron.code.util.ApkInstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    public MainFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            Project project = new Project(new File(savedInstanceState.getString("current_project", "")));
            FileManager.getInstance().openProject(project);
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

        mAdapter = new PageAdapter(this);

        mPager = new ViewPager2(requireContext());
        mPager.setAdapter(mAdapter);
        mPager.setUserInputEnabled(false);
        mPager.setBackgroundColor(0xff2b2b2b);
        mContent.addView(mPager, new LinearLayout.LayoutParams(-1, -1, 1));

        mToolbar = mRoot.findViewById(R.id.toolbar);
        mToolbar.inflateMenu(R.menu.code_editor_menu);

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), onBackPressedCallback);
        logViewModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);

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

                FileManagerFragment fragment = (FileManagerFragment) getChildFragmentManager().findFragmentByTag("file_manager");
                if (fragment != null) {
                    fragment.disableBackListener();
                }
            }

            @Override
            public void onDrawerStateChanged(int p1) {

            }
        });

        mTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabUnselected(TabLayout.Tab p1) {
                CodeEditorFragment fragment = (CodeEditorFragment) getChildFragmentManager().findFragmentByTag("f" + mAdapter.getItemId(p1.getPosition()));
                if (fragment != null) {
                    fragment.save();
                }
            }

            @Override
            public void onTabReselected(TabLayout.Tab p1) {
                PopupMenu popup = new PopupMenu(requireActivity(), p1.view);
                popup.getMenu().add("Close");
                popup.getMenu().add("Close others");
                popup.getMenu().add("Close all");
                popup.setOnMenuItemClickListener(item -> true);
                popup.show();
            }

            @Override
            public void onTabSelected(TabLayout.Tab p1) {

            }
        });
        mTabLayout.setVisibility(View.GONE);
        new TabLayoutMediator(mTabLayout, mPager, (tab, pos) -> tab.setText(mAdapter.getItem(pos).getName())).attach();

        mToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.debug_create) {

                final EditText et = new EditText(requireContext());
                et.setHint("path");
                et.setHintTextColor(0xffeaeaea);

                @SuppressLint("RestrictedApi")
                AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.CodeEditorDialog)
                        .setTitle("Create a project")
                        .setNegativeButton("cancel", null)
                        .setPositiveButton("create", (i, which) -> {
                            File file = new File(et.getText().toString());
                            Project project = new Project(file);
                            project.create();
                            openProject(project);
                        })
                        .setView(et, 24, 0, 24, 0)
                        .create();

                dialog.show();
                return true;
            } else if (item.getItemId() == R.id.debug_refresh) {
                Project project = FileManager.getInstance().getCurrentProject();

                project.clear();
                openProject(project);

                ApplicationLoader.showToast("Project files have been refreshed.");
            } else if (item.getItemId() == R.id.action_run) {
                compile();
            }

            return false;
        });

        final BottomEditorFragment bottomEditorFragment = BottomEditorFragment.newInstance();
        mBehavior = BottomSheetBehavior.from(mBottomContainer);
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

        File root;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root = requireActivity().getExternalFilesDir(null);
        } else {
            root = Environment.getExternalStorageDirectory();
        }
        getChildFragmentManager().beginTransaction()
                .replace(R.id.nav_root, FileManagerFragment.newInstance(root), "file_manager")
                .commit();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

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
            mAdapter.submitFile(file);
            mPager.setCurrentItem(mAdapter.getPosition(file));
        }

        mRoot.closeDrawer(GravityCompat.START, true);
    }

    private void openProject(Project proj) {
        mProgressBar.setVisibility(View.VISIBLE);
        mToolbar.setTitle(proj.mRoot.getName());
        mToolbar.setSubtitle("Indexing");

        Executors.newSingleThreadExecutor().execute(() -> {
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

        requireActivity().startService(new Intent(requireContext(), CompilerService.class));
        requireActivity().bindService(new Intent(requireContext(), CompilerService.class),
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                        CompilerService.CompilerBinder binder = (CompilerService.CompilerBinder) iBinder;
                        binder.getCompilerService().setLogger(logger);
                        binder.getCompilerService().setOnResultListener((success, message) -> {
                            if (!success) {
                                mBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                            }
                            if (mToolbar != null) {
                                mToolbar.setSubtitle(null);
                            }
                            if (mProgressBar != null) {
                                mProgressBar.setVisibility(View.GONE);
                            }
                            if (success && getActivity() != null) {
                                File file = new File(FileManager.getInstance().getCurrentProject().getBuildDirectory(), "bin/signed.apk");
                                ApkInstaller.installApplication(requireActivity(), file.getAbsolutePath());
                            }
                        });
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName componentName) {

                    }
                }, Context.BIND_AUTO_CREATE);
    }

    private class PageAdapter extends FragmentStateAdapter {

        private final List<File> data = new ArrayList<>();

        public PageAdapter(Fragment parent) {
            super(parent);
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
            return data.get(position).getAbsolutePath().hashCode();
        }

        public int getPosition(File file) {
            if (containsItem(file.getAbsolutePath().hashCode())) {
                return data.indexOf(file);
            }
            return -1;
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
