package com.tyron.code.editor;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.tyron.code.R;
import com.tyron.code.editor.log.AppLogFragment;
import com.tyron.code.editor.log.LogViewModel;
import com.tyron.code.util.AndroidUtilities;

@SuppressWarnings("FieldCanBeLocal")
public class BottomEditorFragment extends Fragment {

    public static BottomEditorFragment newInstance() {
        return new BottomEditorFragment();
    }

    private View mRoot;

    private TabLayout mTabLayout;
    private LinearLayout mRowLayout;
    private float mTabLayoutHeight;
    private ViewPager mPager;
    private PageAdapter mAdapter;

    public BottomEditorFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = inflater.inflate(R.layout.bottom_editor_fragment, container, false);
        mRowLayout = mRoot.findViewById(R.id.row_layout);
        mPager = mRoot.findViewById(R.id.viewpager);
        mTabLayout = mRoot.findViewById(R.id.tablayout);
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAdapter = new PageAdapter(getChildFragmentManager());
        mPager.setAdapter(mAdapter);
        mTabLayout.setupWithViewPager(mPager);

        setOffset(0f);
    }

    public void setOffset(float offset) {

        if (offset >= 0.50f) {
            float invertedOffset = 0.5f - offset;
            setRowOffset(((invertedOffset + 0.5f) * 2f));
        }
    }

    private void setRowOffset(float offset) {
        mRowLayout.getLayoutParams()
                .height = Math.round(AndroidUtilities.dp(50) * offset);
        mRowLayout.requestLayout();
    }

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
