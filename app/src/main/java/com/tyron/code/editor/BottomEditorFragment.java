package com.tyron.code.editor;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.ViewGroup.MarginLayoutParams;
import com.tyron.code.R;
import com.google.android.material.tabs.TabLayout;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.fragment.app.FragmentManager;
import com.tyron.code.editor.log.AppLogFragment;
import androidx.viewpager.widget.ViewPager;
import com.tyron.code.editor.log.LogViewModel;
import androidx.viewpager2.widget.ViewPager2;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.tabs.TabLayout.Tab;

public class BottomEditorFragment extends Fragment {

    public static BottomEditorFragment newInstance() {
        return new BottomEditorFragment();
    }

    private View mRoot;

    private TabLayout mTabLayout;
    private float mTabLayoutHeight;
    private ViewPager2 mPager;
    private PageAdapter mAdapter;

    public BottomEditorFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = inflater.inflate(R.layout.bottom_editor_fragment, container, false);

        mPager = mRoot.findViewById(R.id.viewpager);

        mTabLayout = mRoot.findViewById(R.id.tablayout);
        return mRoot;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAdapter = new PageAdapter(this);
        mPager.setAdapter(mAdapter);
        mPager.setUserInputEnabled(false);
        new TabLayoutMediator(mTabLayout, mPager, new TabLayoutMediator.TabConfigurationStrategy() {
                @Override
                public void onConfigureTab(TabLayout.Tab tab, int pos) {
                    switch (pos) {
                        case 0:
                            tab.setText("Build logs");
                            break;
                        case 1:
                            tab.setText("App logs");
                    }
                }           
        }).attach();
        mTabLayoutHeight = mTabLayout.getMeasuredHeight();
        setOffset(0f);
    }

    public void setOffset(float offset) {
        mTabLayout.getLayoutParams()
            .height = (int) (100f * offset);
        mTabLayout.requestLayout();
    }

    private class PageAdapter extends FragmentStateAdapter {
        
        public PageAdapter(Fragment parent) {
            super(parent);
        }
        
        @Override
        public Fragment createFragment(int p1) {
            switch (p1) {
                case 0:
                    return AppLogFragment.newInstance(LogViewModel.BUILD_LOG);
                default:
                case 1:
                    return AppLogFragment.newInstance(LogViewModel.APP_LOG);
            }
        }
        
        @Override
        public int getItemCount() {
            return 2;
        }

        
    }
}
