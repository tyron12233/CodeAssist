package org.gradle.model.internal.asm;

import org.objectweb.asm.MethodVisitor;

public interface BytecodeFragment {
    void emit(MethodVisitor visitor);
}
