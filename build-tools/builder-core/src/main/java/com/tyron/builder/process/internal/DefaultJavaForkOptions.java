package com.tyron.builder.process.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.process.CommandLineArgumentProvider;
import com.tyron.builder.process.JavaDebugOptions;
import com.tyron.builder.process.JavaForkOptions;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.tyron.builder.process.internal.util.MergeOptionsUtil.containsAll;
import static com.tyron.builder.process.internal.util.MergeOptionsUtil.getHeapSizeMb;
import static com.tyron.builder.process.internal.util.MergeOptionsUtil.normalized;

public class DefaultJavaForkOptions extends DefaultProcessForkOptions implements JavaForkOptionsInternal {
    private final JvmOptions options;
    private List<CommandLineArgumentProvider> jvmArgumentProviders;

    @Inject
    public DefaultJavaForkOptions(PathToFileResolver resolver, FileCollectionFactory fileCollectionFactory, JavaDebugOptions debugOptions) {
        super(resolver);
        options = new JvmOptions(fileCollectionFactory, debugOptions);
    }

    @Override
    public List<String> getAllJvmArgs() {
        if (hasJvmArgumentProviders(this)) {
            JvmOptions copy = options.createCopy();
            for (CommandLineArgumentProvider jvmArgumentProvider : jvmArgumentProviders) {
                copy.jvmArgs(jvmArgumentProvider.asArguments());
            }
            return copy.getAllJvmArgs();
        } else {
            return options.getAllJvmArgs();
        }
    }

    @Override
    public void setAllJvmArgs(List<String> arguments) {
        options.setAllJvmArgs(arguments);
        if (hasJvmArgumentProviders(this)) {
            jvmArgumentProviders.clear();
        }
    }

    @Override
    public void setAllJvmArgs(Iterable<?> arguments) {
        options.setAllJvmArgs(arguments);
        if (hasJvmArgumentProviders(this)) {
            jvmArgumentProviders.clear();
        }
    }

    @Override
    public List<String> getJvmArgs() {
        return options.getJvmArgs();
    }

    @Override
    public void setJvmArgs(List<String> arguments) {
        options.setJvmArgs(arguments);
    }

    @Override
    public void setJvmArgs(Iterable<?> arguments) {
        options.setJvmArgs(arguments);
    }

    @Override
    public JavaForkOptions jvmArgs(Iterable<?> arguments) {
        options.jvmArgs(arguments);
        return this;
    }

    @Override
    public JavaForkOptions jvmArgs(Object... arguments) {
        jvmArgs(Arrays.asList(arguments));
        return this;
    }

    @Override
    public List<CommandLineArgumentProvider> getJvmArgumentProviders() {
        if (jvmArgumentProviders == null) {
            jvmArgumentProviders = new ArrayList<CommandLineArgumentProvider>();
        }
        return jvmArgumentProviders;
    }

    @Override
    public Map<String, Object> getSystemProperties() {
        return options.getMutableSystemProperties();
    }

    @Override
    public void setSystemProperties(Map<String, ?> properties) {
        options.setSystemProperties(properties);
    }

    @Override
    public JavaForkOptions systemProperties(Map<String, ?> properties) {
        options.systemProperties(properties);
        return this;
    }

    @Override
    public JavaForkOptions systemProperty(String name, Object value) {
        options.systemProperty(name, value);
        return this;
    }

    @Override
    public FileCollection getBootstrapClasspath() {
        return options.getBootstrapClasspath();
    }

    @Override
    public void setBootstrapClasspath(FileCollection classpath) {
        options.setBootstrapClasspath(classpath);
    }

    @Override
    public JavaForkOptions bootstrapClasspath(Object... classpath) {
        options.bootstrapClasspath(classpath);
        return this;
    }

    @Override
    public String getMinHeapSize() {
        return options.getMinHeapSize();
    }

    @Override
    public void setMinHeapSize(String heapSize) {
        options.setMinHeapSize(heapSize);
    }

    @Override
    public String getMaxHeapSize() {
        return options.getMaxHeapSize();
    }

    @Override
    public void setMaxHeapSize(String heapSize) {
        options.setMaxHeapSize(heapSize);
    }

    @Override
    public String getDefaultCharacterEncoding() {
        return options.getDefaultCharacterEncoding();
    }

    @Override
    public void setDefaultCharacterEncoding(String defaultCharacterEncoding) {
        options.setDefaultCharacterEncoding(defaultCharacterEncoding);
    }

    @Override
    public boolean getEnableAssertions() {
        return options.getEnableAssertions();
    }

    @Override
    public void setEnableAssertions(boolean enabled) {
        options.setEnableAssertions(enabled);
    }

    @Override
    public boolean getDebug() {
        return options.getDebug();
    }

    @Override
    public void setDebug(boolean enabled) {
        options.setDebug(enabled);
    }

    public JavaDebugOptions getDebugOptions() {
        return options.getDebugOptions();
    }

    @Override
    public void debugOptions(Action<JavaDebugOptions> action) {
        action.execute(options.getDebugOptions());
    }

    @Override
    public JavaForkOptions copyTo(JavaForkOptions target) {
        super.copyTo(target);
        options.copyTo(target);
        if (jvmArgumentProviders != null) {
            for (CommandLineArgumentProvider jvmArgumentProvider : jvmArgumentProviders) {
                target.jvmArgs(jvmArgumentProvider.asArguments());
            }
        }
        return this;
    }

    @Override
    public boolean isCompatibleWith(JavaForkOptions options) {
        if (hasJvmArgumentProviders(this) || hasJvmArgumentProviders(options)) {
            throw new UnsupportedOperationException("Cannot compare options with jvmArgumentProviders.");
        }
        return getDebug() == options.getDebug()
                && getEnableAssertions() == options.getEnableAssertions()
                && normalized(getExecutable()).equals(normalized(options.getExecutable()))
                && getWorkingDir().equals(options.getWorkingDir())
                && normalized(getDefaultCharacterEncoding()).equals(normalized(options.getDefaultCharacterEncoding()))
                && getHeapSizeMb(getMinHeapSize()) >= getHeapSizeMb(options.getMinHeapSize())
                && getHeapSizeMb(getMaxHeapSize()) >= getHeapSizeMb(options.getMaxHeapSize())
                && normalized(getJvmArgs()).containsAll(normalized(options.getJvmArgs()))
                && containsAll(getSystemProperties(), options.getSystemProperties())
                && containsAll(getEnvironment(), options.getEnvironment())
                && getBootstrapClasspath().getFiles().containsAll(options.getBootstrapClasspath().getFiles());
    }

    private static boolean hasJvmArgumentProviders(JavaForkOptions forkOptions) {
        return forkOptions instanceof DefaultJavaForkOptions
            && hasJvmArgumentProviders((DefaultJavaForkOptions) forkOptions);
    }

    private static boolean hasJvmArgumentProviders(DefaultJavaForkOptions forkOptions) {
        return forkOptions.jvmArgumentProviders != null && !forkOptions.jvmArgumentProviders.isEmpty();
    }

}
