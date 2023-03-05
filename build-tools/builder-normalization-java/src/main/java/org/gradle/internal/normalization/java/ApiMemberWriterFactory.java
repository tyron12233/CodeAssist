package org.gradle.internal.normalization.java;

import org.gradle.internal.normalization.java.impl.ApiMemberWriter;

import org.objectweb.asm.ClassWriter;

public interface ApiMemberWriterFactory {
    ApiMemberWriter makeApiMemberWriter(ClassWriter classWriter);
}