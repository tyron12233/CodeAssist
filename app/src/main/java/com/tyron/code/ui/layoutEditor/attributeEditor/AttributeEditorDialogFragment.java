package com.tyron.code.ui.layoutEditor.attributeEditor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.code.R;

import java.util.ArrayList;
import java.util.List;

import kotlin.Pair;

public class AttributeEditorDialogFragment extends DialogFragment {

    public static final String KEY_ATTRIBUTE_CHANGED = "ATTRIBUTE_CHANGED";

    public static AttributeEditorDialogFragment newInstance(ArrayList<kotlin.Pair<String, String>> attributes) {
        Bundle args = new Bundle();
        args.putSerializable("attributes", attributes);
        AttributeEditorDialogFragment fragment = new AttributeEditorDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private AttributeEditorAdapter mAdapter;
    private ArrayList<kotlin.Pair<String, String>> mAttributes;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection unchecked
        mAttributes = (ArrayList<kotlin.Pair<String, String>>)
                requireArguments().getSerializable("attributes");
        if (mAttributes == null) {
            mAttributes = new ArrayList<>();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.attribute_editor_dialog_fragment, container, false);

        mAdapter = new AttributeEditorAdapter();
        RecyclerView recyclerView = root.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(mAdapter);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mAdapter.setItemClickListener((pos, attribute) -> {
            EditText editText = new EditText(requireContext());
            editText.setText(attribute.getSecond());
            new MaterialAlertDialogBuilder(requireContext())
                    .setView(editText)
                    .setPositiveButton("apply", (d, w) -> {
                        List<Pair<String, String>> attributes = mAdapter.getAttributes();
                        attributes.set(pos,
                                new Pair<>(attribute.getFirst(), editText.getText().toString()));
                        mAdapter.submitList(attributes);

                        Bundle bundle = new Bundle();
                        bundle.putString("key", attribute.getFirst());
                        bundle.putString("value", editText.getText().toString());
                        getParentFragmentManager().setFragmentResult(KEY_ATTRIBUTE_CHANGED,
                                bundle);
                    })
                    .show();
        });
        mAdapter.submitList(mAttributes);
    }
}
