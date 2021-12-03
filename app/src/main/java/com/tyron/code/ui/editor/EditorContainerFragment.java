package com.tyron.code.ui.editor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.tyron.code.R;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.ui.main.adapter.PageAdapter;

import java.io.File;
import java.util.List;
import java.util.Objects;

public class EditorContainerFragment extends Fragment {

    public static final String SAVE_ALL_KEY = "saveAllEditors";
    public static final String PREVIEW_KEY = "previewEditor";

    private TabLayout mTabLayout;
    private ViewPager2 mPager;
    private PageAdapter mAdapter;
    private BottomSheetBehavior<View> mBehavior;

    private MainViewModel mMainViewModel;

    private final OnBackPressedCallback mOnBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.editor_container_fragment, container, false);

        mAdapter = new PageAdapter(getChildFragmentManager(), getViewLifecycleOwner().getLifecycle());
        mPager = root.findViewById(R.id.viewpager);
        mPager.setAdapter(mAdapter);
        mPager.setUserInputEnabled(false);
        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                mMainViewModel.updateCurrentPosition(position);
            }
        });

        mTabLayout = root.findViewById(R.id.tablayout);
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
                            mMainViewModel.removeFile(mMainViewModel.getCurrentFile());
                            break;
                        case 1:
                            File currentFile = mMainViewModel.getCurrentFile();
                            List<File> files = mMainViewModel.getFiles().getValue();
                            if (files != null) {
                                files.clear();
                                files.add(currentFile);
                                mMainViewModel.setFiles(files);
                            }
                            break;
                        case 2:
                            mMainViewModel.clear();
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
        new TabLayoutMediator(mTabLayout, mPager, (tab, pos) -> {
            File current = Objects.requireNonNull(mMainViewModel.getFiles().getValue()).get(pos);
            tab.setText(current != null ? current.getName() : "Unknown");
        }).attach();

        mBehavior = BottomSheetBehavior.from(root.findViewById(R.id.persistent_sheet));
        mBehavior.setFitToContents(true);
        mBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View p1, int state) {
                if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                    mOnBackPressedCallback.setEnabled(false);
                } else if (state == BottomSheetBehavior.STATE_EXPANDED) {
                    mOnBackPressedCallback.setEnabled(true);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                Bundle bundle = new Bundle();
                bundle.putFloat("offset", slideOffset);
                getChildFragmentManager().setFragmentResult(BottomEditorFragment.OFFSET_KEY,
                        bundle);
            }
        });
        mBehavior.setHalfExpandedRatio(0.3f);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mMainViewModel.getFiles().observe(getViewLifecycleOwner(), files -> {
            mAdapter.submitList(files);
            mTabLayout.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
        });
        mMainViewModel.getCurrentPosition().observe(getViewLifecycleOwner(), pos -> {
            mPager.setCurrentItem(pos);
        });

        getParentFragmentManager().setFragmentResultListener(SAVE_ALL_KEY,
                getViewLifecycleOwner(), (requestKey, result) -> saveAll());
        getParentFragmentManager().setFragmentResultListener(PREVIEW_KEY,
                getViewLifecycleOwner(), ((requestKey, result) -> previewCurrent()));
    }

    private void previewCurrent() {
        String tag = "f" + mAdapter.getItemId(mPager.getCurrentItem());
        Fragment fragment = getChildFragmentManager().findFragmentByTag(tag);
        if (fragment instanceof CodeEditorFragment) {
            ((CodeEditorFragment) fragment).preview();
        }
    }

    private void saveAll() {
        for (int i = 0; i < mAdapter.getItemCount(); i++) {
            String tag = "f" + mAdapter.getItemId(i);
            Fragment fragment = getChildFragmentManager().findFragmentByTag(tag);
            if (fragment instanceof Savable) {
                ((Savable) fragment).save();
            }
        }
    }
}
