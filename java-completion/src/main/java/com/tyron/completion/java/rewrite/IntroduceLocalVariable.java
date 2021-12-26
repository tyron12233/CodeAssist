package com.tyron.completion.java.rewrite;

import com.google.common.collect.ImmutableMap;
import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.java.ParseTask;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

import java.nio.file.Path;
import java.util.Map;

public class IntroduceLocalVariable implements Rewrite {

    private final Path file;
    private final TypeMirror type;
    private final long position;

    public IntroduceLocalVariable(Path file, TypeMirror type, long position) {
        this.file = file;
        this.type = type;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        Range range = new Range(position, position);
        TextEdit edit = new TextEdit(range,
                EditHelper.printType(type) + " " + guessNameFromType(type) + " = ");
        return ImmutableMap.of(file, new TextEdit[]{edit});
    }

    private String guessNameFromType(TypeMirror type) {
        if (type instanceof DeclaredType) {
            DeclaredType declared = (DeclaredType) type;
            Element element = declared.asElement();
            String name = element.getSimpleName().toString();
            // anonymous class, guess from class name
            if (name.length() == 0) {
                name = declared.toString();
                name = name.substring("<anonymous ".length(), name.length() - 1);
                name = ActionUtil.getSimpleName(name);
            }
            return "" + Character.toLowerCase(name.charAt(0)) + name.subSequence(1, name.length());
        }
        return "variable";
    }
}
