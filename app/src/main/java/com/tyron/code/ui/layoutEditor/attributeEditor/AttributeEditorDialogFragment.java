package com.tyron.code.ui.layoutEditor.attributeEditor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.R;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.xml.util.StyleUtils;
import com.tyron.completion.xml.XmlIndexProvider;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlin.Pair;

public class AttributeEditorDialogFragment extends BottomSheetDialogFragment {

    public static final String KEY_ATTRIBUTE_CHANGED = "ATTRIBUTE_CHANGED";
    public static final String KEY_ATTRIBUTE_REMOVED = "ATTRIBUTE_REMOVED";

    public static AttributeEditorDialogFragment newInstance(String tag, String parentTag, ArrayList<Pair<String, String>> availableAttributes, ArrayList<Pair<String, String>> attributes) {
        Bundle args = new Bundle();
        args.putSerializable("attributes", attributes);
        args.putSerializable("availableAttributes", availableAttributes);
        args.putString("parentTag", parentTag);
        args.putString("tag", tag);
        AttributeEditorDialogFragment fragment = new AttributeEditorDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private AttributeEditorAdapter mAdapter;
    private ArrayList<Pair<String, String>> mAvailableAttributes;
    private ArrayList<Pair<String, String>> mAttributes;
    private String mTag;
    private String mParentTag;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection unchecked
        mAttributes = (ArrayList<Pair<String, String>>) requireArguments().getSerializable(
                "attributes");
        if (mAttributes == null) {
            mAttributes = new ArrayList<>();
        }

        //noinspection unchecked
        mAvailableAttributes =
                (ArrayList<Pair<String, String>>) requireArguments().getSerializable(
                        "availableAttributes");
        if (mAvailableAttributes == null) {
            mAvailableAttributes = new ArrayList<>();
        }

        mTag = requireArguments().getString("tag", "");
        mParentTag = requireArguments().getString("parentTag", "");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
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
        if(getDialog() != null){
            getDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        mAdapter.setItemClickListener(this::onAttributeItemClick);
        mAdapter.submitList(mAttributes);

        LinearLayout linearAdd = view.findViewById(R.id.linear_add);
        linearAdd.setOnClickListener(v -> {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
            builder.setTitle("Available Attributes");

            final ArrayList<CharSequence> items = new ArrayList<>();
            final ArrayList<Pair<String, String>> filteredAttributes = new ArrayList<>();

            Loop1: for (int i = 0; i < mAvailableAttributes.size(); i++) {
                for(Pair<String, String> pair : mAttributes){
                    if(pair.getFirst().equals(mAvailableAttributes.get(i).getFirst()))
                        continue Loop1;
                }
                filteredAttributes.add(mAvailableAttributes.get(i));
                items.add(mAvailableAttributes.get(i).getFirst());
            }
            boolean[] selectedAttrs = new boolean[filteredAttributes.size()];
            builder.setMultiChoiceItems(items.toArray(new CharSequence[0]), selectedAttrs, (d, which, isSelected) -> {
               selectedAttrs[which] = isSelected;
            });
            builder.setPositiveButton("Add", ((dialogInterface, which) -> {
                for(int i = 0; i < selectedAttrs.length; i++){
                    if(selectedAttrs[i]){
                        mAttributes.add(filteredAttributes.get(i));
                    }
                }
                mAdapter.submitList(mAttributes);
            }));
            builder.show();
        });

        getParentFragmentManager().setFragmentResultListener(KEY_ATTRIBUTE_REMOVED, getViewLifecycleOwner(), ((requestKey, result) -> {
            String key = result.getString("key");
            int index = -1;
            for (Pair<String, String> pair : mAttributes) {
                if (pair.getFirst().equals(key)) {
                    index = mAttributes.indexOf(pair);
                }
            }
            if (index != -1) {
                mAttributes.remove(index);
                mAdapter.submitList(mAttributes);
            }
        }));
    }

    private void onAttributeItemClick(int pos, Pair<String, String> attribute) {
        View v = LayoutInflater.from(requireContext()).inflate(R.layout.attribute_editor_input, null);
        MaterialAutoCompleteTextView editText = v.findViewById(R.id.value);
        XmlRepository xmlRepository = getXmlRepository();

        String attributeName = attribute.getFirst();
        String attributeNamespace = "";
        if (attributeName.contains(":")) {
            attributeNamespace = attributeName.substring(0, attributeName.indexOf(':'));
            attributeName = attributeName.substring(attributeName.indexOf(':') + 1);
        }
        if (xmlRepository != null) {
            List<String> values = new ArrayList<>();
            Map<String, DeclareStyleable> declareStyleables =
                    xmlRepository.getDeclareStyleables();
            Set<DeclareStyleable> styles = new HashSet<>(StyleUtils.getStyles(declareStyleables,
                    mTag, mParentTag));

            for (DeclareStyleable style : styles) {
                for (AttributeInfo attributeInfo : style.getAttributeInfos()) {
                    if (!attributeNamespace.equals(attributeInfo.getNamespace())) {
                        continue;
                    }
                    if (!attributeName.equals(attributeInfo.getName())) {
                        continue;
                    }

                    if (attributeInfo.getFormats() == null || attributeInfo.getFormats().isEmpty()) {
                        AttributeInfo extraAttribute =
                                xmlRepository.getExtraAttribute(attributeName);
                        if (extraAttribute != null) {
                            attributeInfo = extraAttribute;
                        }
                    }
                    values.addAll(attributeInfo.getValues());
                }
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_list_item_1, values);
            editText.setThreshold(1);
            editText.showDropDown();
            editText.setAdapter(adapter);

        }

        editText.setText(attribute.getSecond(), false);
        new MaterialAlertDialogBuilder(requireContext()).setTitle(attribute.getFirst()).setView(v).setPositiveButton("apply", (d, w) -> {
            mAttributes.set(pos, new Pair<>(attribute.getFirst(), editText.getText().toString()));
            mAdapter.submitList(mAttributes);

            Bundle bundle = new Bundle();
            bundle.putString("key", attribute.getFirst());
            bundle.putString("value", editText.getText().toString());
            getParentFragmentManager().setFragmentResult(KEY_ATTRIBUTE_CHANGED, bundle);
        }).show();
    }

    @Nullable
    private XmlRepository getXmlRepository() {
        ProjectManager projectManager = ProjectManager.getInstance();
        Project currentProject = projectManager.getCurrentProject();
        if (currentProject == null) {
            return null;
        }
        Module mainModule = currentProject.getMainModule();
        if (!(mainModule instanceof AndroidModule)) {
            return null;
        }

        XmlIndexProvider index = CompilerService.getInstance().getIndex(XmlIndexProvider.KEY);
        XmlRepository xmlRepository = index.get(currentProject, mainModule);
        try {
            xmlRepository.initialize((AndroidModule) mainModule);
        } catch (IOException e) {
            // ignored
        }
        return xmlRepository;
    }
}
