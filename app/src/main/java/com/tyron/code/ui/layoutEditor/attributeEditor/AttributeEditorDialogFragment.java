package com.tyron.code.ui.layoutEditor.attributeEditor;

import android.app.Dialog;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

public class AttributeEditorDialogFragment extends DialogFragment {

    public static final String KEY_ATTRIBUTE_CHANGED = "ATTRIBUTE_CHANGED";

    public static AttributeEditorDialogFragment newInstance(ArrayList<Pair<String, String>> attributes) {
        Bundle args = new Bundle();
        args.putSerializable("attributes", attributes);
        AttributeEditorDialogFragment fragment = new AttributeEditorDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private AttributeEditorAdapter mAdapter;
    private ArrayList<Pair<String, String>> mAttributes;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection unchecked
        mAttributes = (ArrayList<Pair<String, String>>)
                requireArguments().getSerializable("attributes");
        if (mAttributes == null) {
            mAttributes = new ArrayList<>();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(requireContext());

        mAdapter = new AttributeEditorAdapter();
        RecyclerView recyclerView = new RecyclerView(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(mAdapter);
        root.addView(recyclerView);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mAdapter.setItemClickListener(attribute -> {
            EditText editText = new EditText(requireContext());
            editText.setText(attribute.second);
            new MaterialAlertDialogBuilder(requireContext())
                    .setView(editText)
                    .setPositiveButton("apply", (d, w) -> {
                        Bundle bundle = new Bundle();
                        bundle.putString("key", attribute.first);
                        bundle.putString("value", editText.getText().toString());
                        getParentFragmentManager().setFragmentResult(KEY_ATTRIBUTE_CHANGED,
                                bundle);
                    })
                    .show();
        });
        mAdapter.submitList(mAttributes);
    }
}
