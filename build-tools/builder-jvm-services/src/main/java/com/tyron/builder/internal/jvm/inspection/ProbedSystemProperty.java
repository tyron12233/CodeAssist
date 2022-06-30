package com.tyron.builder.internal.jvm.inspection;

enum ProbedSystemProperty {

    JAVA_HOME("java.home"),
    VERSION("java.version"),
    VENDOR("java.vendor"),
    ARCH("os.arch"),
    VM("java.vm.name"),
    VM_VERSION("java.vm.version"),
    RUNTIME("java.runtime.name"),
    RUNTIME_VERSION("java.runtime.version"),
    Z_ERROR("Internal"); // This line MUST be last!

    private final String key;

    ProbedSystemProperty(String key) {
        this.key = key;
    }

    String getSystemPropertyKey() {
        return key;
    }

}
