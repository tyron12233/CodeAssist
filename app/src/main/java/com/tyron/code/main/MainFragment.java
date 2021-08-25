package com.tyron.code.main;

import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.google.android.material.tabs.TabLayout;
import androidx.drawerlayout.widget.DrawerLayout;
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
import androidx.appcompat.widget.Toolbar;
import com.tyron.code.file.FileManagerFragment;
import android.view.Gravity;
import com.tyron.code.compiler.JavaCompiler;
import androidx.lifecycle.ViewModelProvider;
import com.tyron.code.editor.log.LogViewModel;
import java.util.stream.Collectors;
import android.os.AsyncTask;
import com.tyron.code.completion.CompletionEngine;
import android.app.ProgressDialog;
import com.tyron.code.JavaCompilerService;
import com.tyron.code.CompileTask;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TaskEvent;
import androidx.core.widget.PopupMenuCompat;
import androidx.appcompat.widget.PopupMenu;

public class MainFragment extends Fragment {
    
    public MainFragment() {
        
    }
    
    private DrawerLayout mRoot;
    private Toolbar mToolbar;
    private LinearLayout mContent;
    private FrameLayout mBottomContainer;
    private BottomSheetBehavior mBehavior;
    private TabLayout mTabLayout;
    private ViewPager2 mPager;
    private PageAdapter mAdapter;
    
    OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
			if (mRoot.isOpen()) {
				mRoot.closeDrawer(Gravity.START, true);
				return;
			}
            if (mBehavior != null) {
                mBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        }
    };

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		if (savedInstanceState != null) {
			Project project = new Project(new File(savedInstanceState.getString("current_project")));
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
        
        mAdapter = new PageAdapter(this);
        
        mPager = new ViewPager2(requireContext());
        mPager.setAdapter(mAdapter);
        mPager.setUserInputEnabled(false);
        mPager.setBackgroundColor(0xff2b2b2b);
        mContent.addView(mPager, new LinearLayout.LayoutParams(-1, -1, 1));
        
        mToolbar = mRoot.findViewById(R.id.toolbar);
        mToolbar.inflateMenu(R.menu.code_editor_menu);
        
        requireActivity().getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
        
        return mRoot;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
		mRoot.addDrawerListener(new DrawerLayout.DrawerListener() {
			@Override
			public void onDrawerSlide(View p1, float p) {

			}

			@Override
			public void onDrawerOpened(View p1) {
				onBackPressedCallback.setEnabled(true);
			}

			@Override
			public void onDrawerClosed(View p1) {
				if (mBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
					onBackPressedCallback.setEnabled(true);
				} else {
					onBackPressedCallback.setEnabled(false);
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
                fragment.save();
            }

            @Override
            public void onTabReselected(TabLayout.Tab p1) {
				PopupMenu popup = new PopupMenu(requireActivity(), p1.view);
				popup.getMenu().add("Close");
				popup.getMenu().add("Close others");
				popup.getMenu().add("Close all");
				popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						return true;
					}
				});
				popup.show();
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
        
        mToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.debug_create) {

                    final EditText et = new EditText(requireContext());
                    et.setHint("path");
					et.setHintTextColor(0xffe0e0e0);

                    AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.CodeEditorDialog)
                        .setTitle("Create a project")
                        .setNegativeButton("cancel", null)
                        .setPositiveButton("create", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface i, int which) {
                                File file = new File(et.getText().toString());
                                Project project = new Project(file);

                                if (project.create()) {


                                } else {
                                    //  ApplicationLoader.showToast("Unable to creste project");
                                }
                                FileManager.getInstance().openProject(project);                         
                                /*final List<File> files = project.javaFiles.values().stream().limit(10).collect(Collectors.toList());
								mAdapter.submitList(files);
							    final Object lock = new Object();
								final ProgressDialog d = new ProgressDialog(requireContext());
								d.show();
								d.setCancelable(false);
								AsyncTask.execute(() -> {
									for (File f : files) {
										//synchronized(lock) {
										requireActivity().runOnUiThread(() -> d.setMessage("Indexing " + f.getName()));
										try {
										JavaCompilerService c = CompletionEngine.getInstance().getCompiler();
										CompileTask task = c.compile(f.toPath());
										task.close();
										} catch (Throwable e) {
											continue;
										}
										//}
									}
									
									requireActivity().runOnUiThread(() -> {
										d.dismiss();
									});
								});*/
                            }
                        })
                        .setView(et, 16, 0, 16, 0)
                        .create();

                    dialog.show();
                    return true;
                } else if (item.getItemId() == R.id.debug_refresh) {
					Project project = FileManager.getInstance().getCurrentProject();
					if (project != null) {
						FileManager.getInstance().openProject(project);
					}
					
					ApplicationLoader.showToast("Project files have been refreshed.");
				} else if (item.getItemId() == R.id.action_run) {
					compile();
				}

                return false;
            }
        });
        
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
            
        getChildFragmentManager().beginTransaction()
                .replace(R.id.nav_root, FileManagerFragment.newInstance(new File("/sdcard")), "file_manager")
                .commit();
    }

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		
		Project current = FileManager.getInstance().getCurrentProject();
		if (current != null) {
			outState.putString("current_project", current.mRoot.getAbsolutePath());
		}
	}

    public void openFile(File file) {
		if (!file.getName().endsWith(".java")) {
			return;
		}
		
		int pos = mAdapter.getPosition(file);
		if (pos != -1) {
			mPager.setCurrentItem(pos);
		} else {
			mAdapter.submitFile(file);
			mPager.setCurrentItem(mAdapter.getPosition(file));
		}
		
		mRoot.closeDrawer(Gravity.START, true);
	}
	
	private void compile() {
		JavaCompiler compiler = new JavaCompiler(new ViewModelProvider(requireActivity()).get(LogViewModel.class));
		compiler.compile(new JavaCompiler.OnCompleteListener() {
			@Override
			public void onComplete(boolean success) {
				ApplicationLoader.showToast(success ? "Compilation success" : "Compilation failed");
				if (!success) {
					mBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
				}
			}
		});
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
		
		public void submitFile(File file) {
			data.add(file);
			notifyItemInserted(data.size());
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
		
		public int getPosition(File file) {
			if (containsItem(file.getAbsolutePath().hashCode())) {
				return data.indexOf(file);
			}
			return -1;
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
