package com.tyron.builder.internal.classpath;

import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;
import static java.lang.String.format;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.file.FileException;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.file.archive.ZipEntry;
import com.tyron.builder.internal.file.archive.ZipInput;
import com.tyron.builder.internal.file.archive.impl.FileZipInput;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.util.internal.GFileUtils;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

class InstrumentingClasspathFileTransformer implements ClasspathFileTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger(InstrumentingClasspathFileTransformer.class);
    private static final int CACHE_FORMAT = 5;

    private final FileLockManager fileLockManager;
    private final ClasspathWalker classpathWalker;
    private final ClasspathBuilder classpathBuilder;
    private final CachedClasspathTransformer.Transform transform;
    private final HashCode configHash;

    public InstrumentingClasspathFileTransformer(
        FileLockManager fileLockManager,
        ClasspathWalker classpathWalker,
        ClasspathBuilder classpathBuilder,
        CachedClasspathTransformer.Transform transform
    ) {
        this.fileLockManager = fileLockManager;
        this.classpathWalker = classpathWalker;
        this.classpathBuilder = classpathBuilder;
        this.transform = transform;
        this.configHash = configHashFor(transform);
    }

    private HashCode configHashFor(CachedClasspathTransformer.Transform transform) {
        Hasher hasher = Hashes.newHasher();
        hasher.putInt(CACHE_FORMAT);
        transform.applyConfigurationTo(hasher);
        return hasher.hash();
    }

    @Override
    public File transform(File source, FileSystemLocationSnapshot sourceSnapshot, File cacheDir) {
        String destDirName = hashOf(sourceSnapshot);
        File destDir = new File(cacheDir, destDirName);
        String destFileName = sourceSnapshot.getType() == FileType.Directory ? source.getName() + ".jar" : source.getName();
        File receipt = new File(destDir, destFileName + ".receipt");
        File transformed = new File(destDir, destFileName);

        // Avoid file locking overhead by checking for the receipt first.
        if (receipt.isFile()) {
            return transformed;
        }

        final File lockFile = new File(destDir, destFileName + ".lock");
        final FileLock fileLock = exclusiveLockFor(lockFile);
        try {
            if (receipt.isFile()) {
                // Lock was acquired after a concurrent writer had already finished.
                return transformed;
            }
            transform(source, transformed);
            try {
                receipt.createNewFile();
            } catch (IOException e) {
                throw new UncheckedIOException(
                    format("Failed to create receipt for instrumented classpath file '%s/%s'.", destDirName, destFileName),
                    e
                );
            }
            return transformed;
        } finally {
            fileLock.close();
        }
    }

    private FileLock exclusiveLockFor(File file) {
        return fileLockManager.lock(
            file,
            mode(FileLockManager.LockMode.Exclusive).useCrossVersionImplementation(),
            "instrumented jar cache"
        );
    }

    private String hashOf(FileSystemLocationSnapshot sourceSnapshot) {
        Hasher hasher = Hashes.newHasher();
        Hashes.putHash(hasher, configHash);
        // TODO - apply runtime classpath normalization?
        Hashes.putHash(hasher, sourceSnapshot.getHash());
        return hasher.hash().toString();
    }

    private void transform(File source, File dest) {
        if (isSignedJar(source)) {
            LOGGER.debug("Signed archive '{}'. Skipping instrumentation.", source.getName());
            GFileUtils.copyFile(source, dest);
        } else {
            instrument(source, dest);
        }
    }

    private void instrument(File source, File dest) {
        classpathBuilder.jar(dest, builder -> {
            try {
                visitEntries(source, builder);
            } catch (FileException e) {
                // Badly formed archive, so discard the contents and produce an empty JAR
                LOGGER.debug("Malformed archive '{}'. Discarding contents.", source.getName(), e);
            }
        });
    }

    private void visitEntries(File source, ClasspathBuilder.EntryBuilder builder) throws IOException, FileException {
        classpathWalker.visit(source, entry -> {
            try {
                if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(entry.getContent());
                    ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    Pair<RelativePath, ClassVisitor> chain = transform.apply(entry, classWriter);
                    reader.accept(chain.right, 0);
                    byte[] bytes = classWriter.toByteArray();
                    builder.put(chain.left.getPathString(), bytes);
                } else {
                    builder.put(entry.getName(), entry.getContent());
                }
            } catch (Throwable e) {
                throw new IOException("Failed to process the entry '" + entry.getName() + "' from '" + source + "'", e);
            }
        });
    }

    private boolean isSignedJar(File source) {
        if (!source.isFile()) {
            return false;
        }
        try (ZipInput entries = FileZipInput.create(source)) {
            for (ZipEntry entry : entries) {
                String entryName = entry.getName();
                if (entryName.startsWith("META-INF/") && entryName.endsWith(".SF")) {
                    return true;
                }
            }
        } catch (FileException e) {
            // Ignore malformed archive
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return false;
    }
}
