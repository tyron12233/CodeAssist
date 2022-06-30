package com.tyron.builder.internal.normalization.java;

import com.tyron.builder.internal.normalization.java.impl.ApiMemberWriter;

import org.objectweb.asm.ClassWriter;

public interface ApiMemberWriterFactory {
    ApiMemberWriter makeApiMemberWriter(ClassWriter classWriter);
}