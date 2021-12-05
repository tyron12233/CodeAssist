package com.tyron.code.ui.layoutEditor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;
import com.tyron.code.ui.layoutEditor.model.ViewPalette;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LayoutEditorFragment extends Fragment {

    /**
     * Creates a new LayoutEditorFragment instance for a layout xml file.
     * Make sure that the file exists and is a valid layout file and that
     * {@code ProjectManager#getCurrentProject} is not null
     */
    public static LayoutEditorFragment newInstance(File file) {
        Bundle args = new Bundle();
        args.putSerializable("file", file);
        LayoutEditorFragment fragment = new LayoutEditorFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private ViewPaletteAdapter mAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.layout_editor_fragment, container, false);

        mAdapter = new ViewPaletteAdapter();
        RecyclerView paletteRecyclerView = root.findViewById(R.id.palette_recyclerview);
        paletteRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        paletteRecyclerView.setAdapter(mAdapter);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mAdapter.submitList(populatePalettes());
    }

    private List<ViewPalette> populatePalettes() {
        List<ViewPalette> palettes = new ArrayList<>();
        palettes.add(createPalette("android.widget.LinearLayout", R.drawable.crash_ic_close));
        palettes.add(createPalette("android.widget.TextView", R.drawable.crash_ic_bug_report));
        return palettes;
    }

    private ViewPalette createPalette(String className, @DrawableRes int icon) {
        String name = className.substring(className.lastIndexOf('.'));
        return ViewPalette.builder()
                .setClassName(className)
                .setName(name)
                .setIcon(icon)
                .build();
    }
}
