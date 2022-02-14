package com.tyron.completion.xml.util;

import android.view.View;

import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.insert.LayoutTagInsertHandler;
import com.tyron.completion.xml.model.XmlCachedCompletion;

import org.apache.bcel.classfile.JavaClass;
import org.openjdk.javax.lang.model.element.TypeElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class AndroidXmlTagUtils {

    public static void addTagItems(XmlRepository repository,
                             String prefix,
                             CompletionList.Builder builder) {
        for (Map.Entry<String, JavaClass> entry : repository.getJavaViewClasses()
                .entrySet()) {
            CompletionItem item = new CompletionItem();
            String commitPrefix = "<";
            if (prefix.startsWith("</")) {
                commitPrefix = "</";
            }
            boolean useFqn = prefix.contains(".");
            if (!entry.getKey()
                    .startsWith("android.widget")) {
                useFqn = true;
            }
            String simpleName = StyleUtils.getSimpleName(entry.getKey());
            item.label = simpleName;
            item.detail = entry.getValue()
                    .getPackageName();
            item.iconKind = DrawableKind.Class;
            item.commitText = commitPrefix +
                              (useFqn ? entry.getValue()
                                      .getClassName() : StyleUtils.getSimpleName(entry.getValue()
                                                                                         .getClassName()));
            item.cursorOffset = item.commitText.length();
            item.setInsertHandler(new LayoutTagInsertHandler(entry.getValue(), item));
            item.setSortText("");
            item.addFilterText(entry.getKey());
            item.addFilterText("<" + entry.getKey());
            item.addFilterText("</" + entry.getKey());
            item.addFilterText(simpleName);
            item.addFilterText("<" + simpleName);
            item.addFilterText("</" + simpleName);
            builder.addItem(item);
        }
    }
}
