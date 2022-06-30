package com.tyron.builder.internal.watch.vfs.impl;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.watch.vfs.WatchableFileSystemDetector;
import com.tyron.common.TestUtil;

import net.rubygrapefruit.platform.file.FileSystemInfo;
import net.rubygrapefruit.platform.file.FileSystems;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Stream;

public class DefaultWatchableFileSystemDetector implements WatchableFileSystemDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultWatchableFileSystemDetector.class);

    // !IMPORTANT! If changed, make sure to update the documentation in gradle_daemon.adoc
    private static final ImmutableSet<String> SUPPORTED_FILE_SYSTEM_TYPES = ImmutableSet.of(
            // APFS on macOS
            "apfs",
            // HFS and HFS+ on macOS
            "hfs",
            "ext3",
            "ext4",
            "btrfs",
            "xfs",
            // NTFS on macOS
            "ntfs",
            // NTFS on Windows
            "NTFS",
            // FAT32 on macOS
            "msdos",
            // exFAT on macOS
            "exfat",
            // VirtualBox FS
            "vboxsf"
    );

    private final FileSystems fileSystems;

    public DefaultWatchableFileSystemDetector(FileSystems fileSystems) {
        this.fileSystems = fileSystems;
    }

    @Override
    public Stream<File> detectUnsupportedFileSystems() {
        if (TestUtil.isDalvik()) {
            return Stream.empty();
        }
        return fileSystems.getFileSystems().stream()
                .filter(fileSystem -> {
                    LOGGER.debug("Detected {}: {} from {} (remote: {})",
                            fileSystem.getFileSystemType(),
                            fileSystem.getMountPoint(),
                            fileSystem.getDeviceName(),
                            fileSystem.isRemote()
                    );
                    // We don't support network file systems
                    if (fileSystem.isRemote()) {
                        return true;
                    }
                    return !SUPPORTED_FILE_SYSTEM_TYPES.contains(fileSystem.getFileSystemType());
                })
                .map(FileSystemInfo::getMountPoint);
    }
}
