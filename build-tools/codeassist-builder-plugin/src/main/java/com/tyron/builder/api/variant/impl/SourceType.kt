package com.tyron.builder.api.variant.impl

enum class SourceType(val folder: String) {
    AIDL("aidl"),
    ASSETS("assets"),
    KOTLIN("kotlin"),
    JAVA("java"),
    JNI_LIBS("jniLibs"),
    ML_MODELS("mlModels"),
    RENDERSCRIPT("renderscript"),
    RES("res"),
    SHADERS("shaders"),
    JAVA_RESOURCES("resources"),
}