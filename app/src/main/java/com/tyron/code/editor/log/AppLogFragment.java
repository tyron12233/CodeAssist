package com.tyron.code.editor.log;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import android.view.View;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import io.github.rosemoe.editor.widget.CodeEditor;
import android.widget.FrameLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.Observer;

import com.tyron.code.R;

import io.github.rosemoe.editor.widget.schemes.SchemeDarcula;

public class AppLogFragment extends Fragment {
    
    public static AppLogFragment newInstance(int id) {
        AppLogFragment fragment = new AppLogFragment();
        Bundle bundle = new Bundle();
        bundle.putInt("id", id);
        fragment.setArguments(bundle);
        return fragment;
    }
    
    private int id;
    private CodeEditor mLogView;
    
    public AppLogFragment() {
        
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = getArguments().getInt("id");
    }
  
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(requireContext());
        
        mLogView = new CodeEditor(requireContext());
        mLogView.setTypefaceText(ResourcesCompat.getCachedFont(requireContext(), R.font.jetbrains_mono_regular));
        root.addView(mLogView, new FrameLayout.LayoutParams(-1, -1));
        
        return root;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        mLogView.setKeyboardDisabled(true);
        mLogView.setOverScrollEnabled(false);
        mLogView.setTextSize(10);
        mLogView.setColorScheme(new SchemeDarcula());
        
        LogViewModel model = new ViewModelProvider(requireActivity())
                .get(LogViewModel.class);
        model.getLogs(id).observe(this, (log) -> {
            mLogView.setText(log);
            mLogView.setSelection(mLogView.getLineCount() - 1, 0, true);
        });
    }
}
