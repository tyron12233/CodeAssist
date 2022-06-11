package com.tyron.groovy;

import static java.util.stream.Collectors.joining;

import android.util.Log;

import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.inspector.ClassInspector;
import com.android.tools.r8.inspector.Inspector;
import com.android.tools.r8.origin.Origin;
import com.google.common.base.Joiner;

import org.codehaus.groovy.control.CompilerConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;
import dalvik.system.InMemoryDexClassLoader;
import dalvik.system.PathClassLoader;
import groovy.lang.GrooidClassLoader;
import groovy.lang.Reference;
import groovy.lang.Script;
import groovyjarjarasm.asm.ClassReader;

public class ScriptFactory {

    private final ClassLoader classLoader;

    public ScriptFactory(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @SuppressWarnings("UnusedReturnValue")
    public Script createScript(String scriptText) throws ScriptCompilationException {
        try {
            return createScriptInternal(scriptText);
        } catch (CompilationFailedException e) {
            throw new ScriptCompilationException(e);
        }
    }

    public Class<?> defineClass(byte[] data) {
        try {
            return defineClassInternal(data);
        } catch (CompilationFailedException e) {
            throw new ScriptCompilationException(e);
        }
    }

    public String generateDexFile(Path output, byte[] data) {
        try {
            D8Command.Builder builder = D8Command.builder();
            builder.setDisableDesugaring(true);
            builder.setMinApiLevel(26);
            builder.setOutput(output, OutputMode.DexIndexed);
            builder.addClassProgramData(data, Origin.root());
            D8.run(builder.build());

            return Files.list(output).map(it -> it.toFile().getAbsolutePath())
                    .collect(joining(File.pathSeparator));
        } catch (CompilationFailedException | IOException e) {
            throw new ScriptCompilationException(e);
        }
    }

    public ClassLoader defineClassLoader(byte[]... data) {
        try {
            D8Command.Builder builder = D8Command.builder();
            builder.setDisableDesugaring(true);
            builder.setMinApiLevel(26);

            for (byte[] datum : data) {
                builder.addClassProgramData(datum, Origin.root());
            }
            List<ByteBuffer> bytes = new ArrayList<>();

            builder.setProgramConsumer(new DexIndexedConsumer() {
                @Override
                public void finished(DiagnosticsHandler diagnosticsHandler) {

                }

                @Override
                public void accept(int fileIndex,
                                   ByteDataView data,
                                   Set<String> descriptors,
                                   DiagnosticsHandler handler) {
                    bytes.add(ByteBuffer.wrap(data.copyByteData()));
                }
            });

            D8.run(builder.build());

            ByteBuffer[] byteBuffers = bytes.toArray(new ByteBuffer[0]);
            return new InMemoryDexClassLoader(
                    byteBuffers,
                    classLoader
            );
        } catch (CompilationFailedException e) {
            throw new ScriptCompilationException(e);
        }
    }

    private Class<?> defineClassInternal(byte[] data) throws CompilationFailedException {
        ClassReader reader = new ClassReader(data);
        String name = reader.getClassName();

        D8Command.Builder builder = D8Command.builder();
        builder.setDisableDesugaring(true);
        builder.setMinApiLevel(26);
        builder.addClassProgramData(data, Origin.root());

        Reference<byte[]> ref = new Reference<>();
        builder.setProgramConsumer(new DexIndexedConsumer() {
            @Override
            public void finished(DiagnosticsHandler diagnosticsHandler) {

            }

            @Override
            public void accept(int fileIndex,
                               ByteDataView data,
                               Set<String> descriptors,
                               DiagnosticsHandler handler) {
                ref.set(data.getBuffer());
            }
        });
        D8.run(builder.build());
        ByteBuffer buffer = ByteBuffer.wrap(ref.get());
        InMemoryDexClassLoader classLoader = new InMemoryDexClassLoader(buffer, this.classLoader);
        try {
            return classLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new ScriptCompilationException(e);
        }
    }

    private Script createScriptInternal(String scriptText) throws CompilationFailedException {
        D8Command.Builder builder = D8Command.builder();
        builder.setDisableDesugaring(true);
        builder.setMinApiLevel(26);

        Set<String> classNames = new LinkedHashSet<>();
        CompilerConfiguration config = new CompilerConfiguration();
        config.setBytecodePostprocessor((name, original) -> {
            builder.addClassProgramData(original, Origin.unknown());
            classNames.add(name);
            return original;
        });

        GrooidClassLoader gcl = new GrooidClassLoader(classLoader, config);
        gcl.parseClass(scriptText);

        ByteBuffer[] byteBuffer = getTransformedDexByteBufferArray(builder);
        Map<String, Class<?>> classes = defineDynamic(classNames, byteBuffer);
        for (Class<?> scriptClass : classes.values()) {
            if (Script.class.isAssignableFrom(scriptClass)) {
                try {
                    return (Script) scriptClass.getConstructor().newInstance();
                } catch (IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
                    throw new ScriptCompilationException(e);
                }
            }
        }

        throw new ScriptCompilationException("No script class found.");
    }


    private ByteBuffer[] getTransformedDexByteBufferArray(D8Command.Builder commandBuilder) throws CompilationFailedException {
        final ByteBuffer[] byteBuffer = new ByteBuffer[1];
        commandBuilder.setProgramConsumer(new DexIndexedConsumer() {
            @Override
            public void finished(DiagnosticsHandler diagnosticsHandler) {

            }

            @Override
            public void accept(int fileIndex, ByteDataView data, Set<String> descriptors, DiagnosticsHandler handler) {
                byteBuffer[0] = ByteBuffer.wrap(data.getBuffer());
            }
        });
        D8.run(commandBuilder.build());
        return byteBuffer;
    }

    private Map<String, Class<?>> defineDynamic(Set<String> classNames, ByteBuffer[] byteBuffer) {
        InMemoryDexClassLoader classLoader = new InMemoryDexClassLoader(byteBuffer, this.classLoader);
        Map<String, Class<?>> result = new LinkedHashMap<>();
        try {
            for (String className : classNames) {
                result.put(className, classLoader.loadClass(className));
            }
        } catch (ReflectiveOperationException e) {
            Log.e("DynamicLoading", "Unable to load class", e);
        }
        return result;
    }

    public static File dexJar(File inputJar, File outputDir) {
        D8Command.Builder builder = D8Command.builder();
        builder.setMinApiLevel(26);
        builder.setDisableDesugaring(true);
        builder.setMode(CompilationMode.DEBUG);
        builder.addProgramFiles(inputJar.toPath());
        builder.setOutput(outputDir.toPath(), OutputMode.DexIndexed);
        try {
            D8.run(builder.build());
        } catch (CompilationFailedException e) {
            throw new ScriptCompilationException(e);
        }
        return new File(outputDir, "classes.dex");
    }

    public static Class<?> loadClass(List<File> classPath, ClassLoader parent, String name) throws ClassNotFoundException {
        String join = Joiner.on(File.pathSeparator).join(classPath);
        PathClassLoader classLoader = new PathClassLoader(join, parent);
        return classLoader.loadClass(name);
    }
}