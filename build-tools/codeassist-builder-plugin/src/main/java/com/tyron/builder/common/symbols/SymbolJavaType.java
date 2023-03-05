package com.tyron.builder.common.symbols;

import com.google.common.collect.ImmutableMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public enum SymbolJavaType {
    INT("int", "I"),
    INT_LIST("int[]", "[I"),
    ;

    private static final ImmutableMap<String, SymbolJavaType> types;

    static {
        ImmutableMap.Builder<String, SymbolJavaType> typesBuilder = ImmutableMap.builder();
        for (SymbolJavaType symbolJavaType : SymbolJavaType.values()) {
            typesBuilder.put(symbolJavaType.getTypeName(), symbolJavaType);
        }
        types = typesBuilder.build();
    }

    @NotNull
    private final String typeName;
    @NotNull private final String desc;

    SymbolJavaType(@NotNull String typeName, @NotNull String desc) {
        this.typeName = typeName;
        this.desc = desc;
    }

    @NotNull
    public final String getTypeName() {
        return typeName;
    }

    @NotNull
    public String getDesc() {
        return desc;
    }

    @Nullable
    public static SymbolJavaType getEnum(@NotNull String name) {
        return types.get(name);
    }
}