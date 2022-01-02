package com.tyron.code.ui.editor.impl.text;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tyron.builder.project.Project;

import java.io.File;

public class TextEditorFragment extends Fragment {

    public static TextEditorFragment newInstance(Project project, File file) {
        return new TextEditorFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = new FrameLayout(requireContext());
        view.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        view.setBackgroundColor(0xfffe6262);
        return view;
    }

    boolean isValid() {
        return true;
    }

    boolean isModified() {
        return false;
    }
}
