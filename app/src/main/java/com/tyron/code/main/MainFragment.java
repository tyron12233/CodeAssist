package com.tyron.code.main;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.google.android.material.tabs.TabLayout;
import androidx.viewpager2.widget.ViewPager2;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import com.tyron.code.editor.CodeEditorFragment;
import com.google.android.material.tabs.TabLayoutMediator;
import android.view.MenuInflater;
import android.view.Menu;
import com.tyron.code.R;
import android.widget.FrameLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.tyron.code.editor.BottomEditorFragment;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import android.view.MenuItem;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;
import android.widget.EditText;
import android.content.DialogInterface;
import com.tyron.code.model.Project;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.parser.FileManager;
import java.util.Collections;
import java.util.Collection;

public class MainFragment extends Fragment {
    
    public MainFragment() {
        
    }
    
    private LinearLayout mRoot;
    private LinearLayout mContent;
    private FrameLayout mBottomContainer;
    private BottomSheetBehavior mBehavior;
    private TabLayout mTabLayout;
    private ViewPager2 mPager;
    private PageAdapter mAdapter;
    
    OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (mBehavior != null) {
                mBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        }
    };
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = (LinearLayout) inflater.inflate(R.layout.main_fragment, container, false);

        mContent = mRoot.findViewById(R.id.content);
        mBottomContainer = mRoot.findViewById(R.id.persistent_sheet);
        
        setHasOptionsMenu(true);
        
        mTabLayout = new TabLayout(requireContext());
        mTabLayout.setBackgroundColor(0xff212121);
        mTabLayout.setSelectedTabIndicatorColor(0xffcc7832);
        mTabLayout.setTabTextColors(0xffffffff, 0xffcc7832);
        mTabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        mContent.addView(mTabLayout, new LinearLayout.LayoutParams(-1, -2));
        
        mAdapter = new PageAdapter(this);
        
        mPager = new ViewPager2(requireContext());
        mPager.setAdapter(mAdapter);
        mPager.setUserInputEnabled(false);
        mPager.setBackgroundColor(0xff2b2b2b);
        mContent.addView(mPager, new LinearLayout.LayoutParams(-1, -1, 1));
        
        requireActivity().getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
        
        return mRoot;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        mTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabUnselected(TabLayout.Tab p1) {
                CodeEditorFragment fragment = (CodeEditorFragment) getChildFragmentManager().findFragmentByTag("f" + mAdapter.getItemId(p1.getPosition()));
                fragment.save();
            }

            @Override
            public void onTabReselected(TabLayout.Tab p1) {

            }

            @Override
            public void onTabSelected(TabLayout.Tab p1) {
                
            }
        });
        new TabLayoutMediator(mTabLayout, mPager, new TabLayoutMediator.TabConfigurationStrategy() {
            @Override
            public void onConfigureTab(TabLayout.Tab tab, int pos) {
                tab.setText(mAdapter.getItem(pos).getName());
            }
        }).attach();
        
        mAdapter.submitList(List.of(new File(requireContext().getFilesDir(), "Test.java"), new File(requireContext().getFilesDir(), "Test1.java")));
        
        final BottomEditorFragment bottomEditorFragment = BottomEditorFragment.newInstance();
        mBehavior = BottomSheetBehavior.from(mBottomContainer);
        mBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(View p1, int state) {
                    switch (state) {
                        case BottomSheetBehavior.STATE_COLLAPSED:                          
                            onBackPressedCallback.setEnabled(false);
                            break;
                        case BottomSheetBehavior.STATE_EXPANDED:                      
                            onBackPressedCallback.setEnabled(true);
                    }
                }

                @Override
                public void onSlide(View bottomSheet, float slideOffset) {
                    bottomEditorFragment.setOffset(slideOffset);
                }              
            });

        // Display the persistent fragment
        getChildFragmentManager().beginTransaction()
            .replace(R.id.persistent_sheet, bottomEditorFragment)
            .commit();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        
        menu.clear();
        inflater.inflate(R.menu.code_editor_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.debug_create) {
            
            final EditText et = new EditText(requireContext());
            et.setHint("path");
            
            AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Create a project")
                    .setNegativeButton("create", null)
                    .setPositiveButton("cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface i, int which) {
                            File file = new File(et.getText().toString());
                            Project project = new Project(file);
                            
                            if (project.create()) {
                                
                                
                            } else {
                              //  ApplicationLoader.showToast("Unable to creste project");
                            }
                            FileManager.getInstance().openProject(project);                         
                            mAdapter.submitList(project.javaFiles.values());
                            
                        }
                    })
                    .setView(et)
                    .create();
                    
            dialog.show();
            return true;
        }
        
        return false;
    }
    
    private void compile() {
        
    }
    
    private static class PageAdapter extends FragmentStateAdapter{
        
        private List<File> data = new ArrayList<>();
        
        public PageAdapter(Fragment parent) {
            super(parent);
        }
        
        public void submitList(Collection<File> files) {
            data.clear();
            data.addAll(files);
            notifyDataSetChanged();
        }
        
        @Override
        public int getItemCount() {
            return data.size();
        }

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
