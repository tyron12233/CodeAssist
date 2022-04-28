package com.tyron.code.ui.editor.impl.image;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.tyron.code.R;

import java.io.File;

public class ImageEditorFragment extends Fragment {

    public static ImageEditorFragment newInstance(File file) {
        ImageEditorFragment fragment = new ImageEditorFragment();
        Bundle bundle = new Bundle();
        bundle.putString("file", file.getAbsolutePath());
        fragment.setArguments(bundle);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ImageView imageView = new ImageView(requireContext());
        if (getArguments() != null) {
            String file = requireArguments().getString("file", "");
            File imageFile = new File(file);
            if (imageFile.exists()) {
                Glide.with(imageView)
                        .load(imageFile)
                        .into(imageView);
            }
        }
        return imageView;
    }
}
