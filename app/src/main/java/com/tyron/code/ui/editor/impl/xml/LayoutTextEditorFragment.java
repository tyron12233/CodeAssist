package com.tyron.code.ui.editor.impl.xml;

import android.os.Bundle;
import android.util.Pair;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.BundleKt;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentResultListener;

import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.code.R;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorFragment;
import com.tyron.code.ui.layoutEditor.LayoutEditorFragment;
import com.tyron.code.util.ProjectUtils;

import java.io.File;

/**
 * A {@link CodeEditorFragment} that supports editing layout files
 */
public class LayoutTextEditorFragment extends CodeEditorFragment {

    public static LayoutTextEditorFragment newInstance(File file) {
        Bundle args = new Bundle();
        args.putString("path", file.getAbsolutePath());
        LayoutTextEditorFragment fragment = new LayoutTextEditorFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FragmentManager fragmentManager = getChildFragmentManager();
        FragmentResultListener listener = ((requestKey, result) -> {
            String xml = result.getString("text", getEditor().getText()
                    .toString());
            xml = XmlPrettyPrinter.prettyPrint(xml, XmlFormatPreferences.defaults(),
                                               XmlFormatStyle.LAYOUT, "\n");
            Bundle bundle = new Bundle();
            bundle.putBoolean("loaded", true);
            bundle.putBoolean("bg", true);
            getEditor().setText(xml, bundle);
        });
        fragmentManager.setFragmentResultListener(LayoutEditorFragment.KEY_SAVE,
                                                  getViewLifecycleOwner(), listener);
    }

    public void preview() {

        File currentFile = getEditor().getCurrentFile();
        if (ProjectUtils.isLayoutXMLFile(currentFile)) {
            getChildFragmentManager().beginTransaction().add(R.id.layout_editor_container,
                    LayoutEditorFragment.newInstance(currentFile)).addToBackStack(null).commit();
        } else {
            // TODO: handle unknown files
//            JavaCompilerProvider service =
//                    CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
//            JavaCompilerService compiler = service.getCompiler(ProjectManager.getInstance().getCurrentProject(),
//                    (JavaModule) ProjectManager.getInstance().getCurrentProject().getMainModule());
//            ParseTask parse = compiler.parse(mCurrentFile.toPath(), mEditor.getText().toString());
//            CompilationUnitConverter compilationUnitConverter = new CompilationUnitConverter(parse, mEditor.getText().toString(), new CompilationUnitConverter.LineColumnCallback() {
//                @Override
//                public int getLine(int pos) {
//                    return mEditor.getText().getIndexer().getCharLine(pos);
//                }
//
//                @Override
//                public int getColumn(int pos) {
//                    return mEditor.getText().getIndexer().getCharColumn(pos);
//                }
//            });
//            CompilationUnit compilationUnit = compilationUnitConverter.startScan();
//            compilationUnit.register(new AstObserverAdapter() {
//                @Override
//                public void listChange(NodeList<?> observedNode, ListChangeType type, int index,
//                                       Node node) {
//                    if (type == ListChangeType.ADDITION) {
//                        Optional<com.github.javaparser.Range> optionalRange = node.getRange();
//                        com.github.javaparser.Range range = optionalRange.get();
//                        mEditor.getText().insert(range.begin.line, range.begin.column - 1, node.toString());
//                    }
//                    System.out.println(observedNode + ", type: " + type + ", added: " + node.getRange());
//                }
//
//                @Override
//                public void propertyChange(Node observedNode, ObservableProperty property,
//                                           Object oldValue, Object newValue) {
//                    Optional<com.github.javaparser.Range> optionalRange = observedNode.getRange();
//                    if (oldValue instanceof NodeWithRange) {
//                        optionalRange = ((NodeWithRange<?>) oldValue).getRange();
//                    } else {
//                        optionalRange = observedNode.getRange();
//                    }
//                    if (!optionalRange.isPresent()) {
//                        return;
//                    }
//                    com.github.javaparser.Range range = optionalRange.get();
//                    mEditor.getText().replace(range.begin.line, range.begin.column, range.end.line, range.end.column, "");
//                    mEditor.getText().insert(range.begin.line, range.begin.column, newValue.toString());
//                    mEditor.hideAutoCompleteWindow();
//                }
//            }, Node.ObserverRegistrationMode.THIS_NODE_AND_EXISTING_DESCENDANTS);
//            System.out.println(compilationUnit);
        }
    }
}
