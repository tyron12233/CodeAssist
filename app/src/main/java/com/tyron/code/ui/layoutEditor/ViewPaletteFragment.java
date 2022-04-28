package com.tyron.code.ui.layoutEditor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;

public class ViewPaletteFragment extends Fragment {

    private LayoutEditorViewModel mViewModel;

    private ViewPaletteAdapter mAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mViewModel = new ViewModelProvider(requireParentFragment()).get(LayoutEditorViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.view_palette_fragment, container, false);

        mAdapter = new ViewPaletteAdapter();
        RecyclerView listView = root.findViewById(R.id.palette_recyclerview);
        listView.setLayoutManager(new LinearLayoutManager(requireContext()));
        listView.setAdapter(mAdapter);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mViewModel.getPalettes().observe(getViewLifecycleOwner(), mAdapter::submitList);
    }
}
