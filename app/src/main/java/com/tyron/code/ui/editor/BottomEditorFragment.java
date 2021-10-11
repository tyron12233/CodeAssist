package com.tyron.code.ui.editor;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;
import com.tyron.builder.log.LogViewModel;
import com.tyron.code.R;
import com.tyron.code.ui.editor.log.AppLogFragment;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;
import com.tyron.code.ui.editor.shortcuts.ShortcutsAdapter;
import com.tyron.code.ui.editor.shortcuts.action.CursorMoveAction;
import com.tyron.code.ui.editor.shortcuts.action.TextInsertAction;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.util.AndroidUtilities;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("FieldCanBeLocal")
public class BottomEditorFragment extends Fragment {

    public static BottomEditorFragment newInstance() {
        return new BottomEditorFragment();
    }

    private View mRoot;

    private TabLayout mTabLayout;
    private LinearLayout mRowLayout;
    private ViewPager mPager;
    private PageAdapter mAdapter;

    private RecyclerView mShortcutsRecyclerView;

    private MainViewModel mFilesViewModel;

    public BottomEditorFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = inflater.inflate(R.layout.bottom_editor_fragment, container, false);
        mRowLayout = mRoot.findViewById(R.id.row_layout);
        mShortcutsRecyclerView = mRoot.findViewById(R.id.recyclerview_shortcuts);
        mPager = mRoot.findViewById(R.id.viewpager);
        mTabLayout = mRoot.findViewById(R.id.tablayout);

        mFilesViewModel = new ViewModelProvider(requireActivity())
                .get(MainViewModel.class);
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAdapter = new PageAdapter(getChildFragmentManager());
        mPager.setAdapter(mAdapter);
        mTabLayout.setupWithViewPager(mPager);

        ShortcutsAdapter adapter = new ShortcutsAdapter(getShortcuts());
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false);
        mShortcutsRecyclerView.setLayoutManager(layoutManager);
        mShortcutsRecyclerView.setAdapter(adapter);

        adapter.setOnShortcutSelectedListener((item, pos) -> {
            File currentFile = mFilesViewModel.getCurrentFile();
            if (currentFile != null) {
                CodeEditorFragment fragment = (CodeEditorFragment) getParentFragmentManager()
                        .findFragmentByTag("f" + currentFile.getAbsolutePath().hashCode());
                if (fragment != null) {
                    fragment.performShortcut(item);
                }
            }
        });

        setOffset(0f);
    }

    public void setOffset(float offset) {
        if (mRowLayout == null) {
            return;
        }

        if (offset >= 0.50f) {
            float invertedOffset = 0.5f - offset;
            setRowOffset(((invertedOffset + 0.5f) * 2f));
        } else {
            if (mRowLayout.getHeight() != AndroidUtilities.dp(30)) {
                setRowOffset(1f);
            }
        }
    }

    private void setRowOffset(float offset) {
        mRowLayout.getLayoutParams()
                .height = Math.round(AndroidUtilities.dp(38) * offset);
        mRowLayout.requestLayout();
    }

    private List<ShortcutItem> getShortcuts() {
        List<String> strings = Arrays.asList("<", ">", ";", "{", "}", ":");
        List<ShortcutItem> items = strings.stream()
                .map(item -> {
                    ShortcutItem it = new ShortcutItem();
                    it.label = item;
                    it.kind = "textinsert";
                    it.actions = Arrays.asList(new TextInsertAction());
                    return it;
                }).collect(Collectors.toList());
        Collections.addAll(items,
                new ShortcutItem(Collections.singletonList(new CursorMoveAction(CursorMoveAction.Direction.UP, 1)),"↑", "cursormove"),
                new ShortcutItem(Collections.singletonList(new CursorMoveAction(CursorMoveAction.Direction.DOWN, 1)),"↓", "cursormove"),
                new ShortcutItem(Collections.singletonList(new CursorMoveAction(CursorMoveAction.Direction.LEFT, 1)),"←", "cursormove"),
                new ShortcutItem(Collections.singletonList(new CursorMoveAction(CursorMoveAction.Direction.RIGHT, 1)), "→", "cursormove")
        );

        return items;
    }

    @SuppressWarnings("deprecation")
    private static class PageAdapter extends FragmentStatePagerAdapter {
        
        public PageAdapter(FragmentManager parent) {
            super(parent);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return AppLogFragment.newInstance(LogViewModel.BUILD_LOG);
                default:
                case 1:
                    return AppLogFragment.newInstance(LogViewModel.APP_LOG);
                case 2:
                    return AppLogFragment.newInstance(LogViewModel.DEBUG);
            }
        }

        @Override
        public int getCount() {
            return 3;
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0:
                    return "Build logs";
                default:
                case 1:
                    return "App logs";
                case 2:
                    return "DEBUG";
            }
        }
    }
}
