package org.gradle.jvm.toolchain.internal;

import java.util.Set;
import java.util.function.Supplier;

public interface InstallationSupplier extends Supplier<Set<InstallationLocation>> {

}