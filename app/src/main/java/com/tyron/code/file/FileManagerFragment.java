package com.tyron.code.file;
import androidx.fragment.app.Fragment;
import java.io.File;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.RecyclerView;
import com.tyron.code.R;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.activity.OnBackPressedCallback;

public class FileManagerFragment extends Fragment {
    
    public static FileManagerFragment newInstance(File file) {
        FileManagerFragment fragment = new FileManagerFragment();
        Bundle args = new Bundle();
        args.putString("path", file.getAbsolutePath());
        fragment.setArguments(args);
        return fragment;
    }
    
    OnBackPressedCallback callback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (!mCurrentFile.getParentFile().equals(mRoot)) {
                mAdapter.submitFile(mCurrentFile.getParentFile());
                check(mCurrentFile.getParentFile());
            }
        }
    };
    
    private File mRootFile;
    private File mCurrentFile;
    
    private LinearLayout mRoot;
    private RecyclerView mListView;
    private LinearLayoutManager mLayoutManager;
    private FileManagerAdapter mAdapter;
    
    public FileManagerFragment() {
        
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mRootFile = new File(getArguments().getString("path"));
        if (savedInstanceState != null) {
            mCurrentFile = new File(savedInstanceState.getString("currentFile"), mRootFile.getAbsolutePath());
        } else {
            mCurrentFile = mRootFile;
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = (LinearLayout) inflater.inflate(R.layout.file_manager_fragment, container, false);
        
        mLayoutManager = new LinearLayoutManager(requireContext());
        mAdapter = new FileManagerAdapter();
        
        mListView = mRoot.findViewById(R.id.listView);
        mListView.setLayoutManager(mLayoutManager);
        mListView.setAdapter(mAdapter);
        
        requireActivity().getOnBackPressedDispatcher().addCallback(this, callback);
        return mRoot;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        mAdapter.submitFile(mRootFile);
        mAdapter.setOnItemClickListener(new FileManagerAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(File file, int position) {
                if (position == 0) {
                    if (!mCurrentFile.getParentFile().equals(mRoot)) {
                        mAdapter.submitFile(mCurrentFile.getParentFile());
                        check(mCurrentFile.getParentFile());
                    }
                    return;
                }
                
                if (file.isFile()) {
                    
                } else if (file.isDirectory()) {
                    mAdapter.submitFile(file);
                    check(file);
                }
            }
        });
    }
    
    /**
     * Checks if the current file is equal to the root file if so,
     * it disables the OnBackPressedCallback
     */
    private void check(File currentFile) {
        mCurrentFile = currentFile;
        
        if (currentFile.equals(mRoot)) {
            callback.setEnabled(false);
        } else {
            callback.setEnabled(true);
        }
    }
}
