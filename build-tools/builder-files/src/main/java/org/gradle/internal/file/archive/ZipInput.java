package org.gradle.internal.file.archive;

import java.io.Closeable;

public interface ZipInput extends Iterable<ZipEntry>, Closeable {

}
