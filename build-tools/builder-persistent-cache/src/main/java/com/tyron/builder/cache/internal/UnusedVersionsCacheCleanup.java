package com.tyron.builder.cache.internal;

import static org.apache.commons.io.filefilter.FileFilterUtils.asFileFilter;
import static org.apache.commons.io.filefilter.FileFilterUtils.directoryFileFilter;
import static java.util.Collections.singleton;

import com.google.common.collect.Sets;
import com.tyron.builder.cache.CleanableStore;
import com.tyron.builder.cache.CleanupProgressMonitor;
import com.tyron.builder.util.GradleVersion;

import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnusedVersionsCacheCleanup extends AbstractCacheCleanup {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnusedVersionsCacheCleanup.class);

    private final Pattern cacheNamePattern;
    private final CacheVersionMapping cacheVersionMapping;
    private final UsedGradleVersions usedGradleVersions;

    private Set<CacheVersion> usedVersions;

    public static UnusedVersionsCacheCleanup create(String cacheName,
                                                    CacheVersionMapping cacheVersionMapping,
                                                    UsedGradleVersions usedGradleVersions) {
        Pattern cacheNamePattern = Pattern.compile('^' +
                                                   Pattern.quote(cacheName) +
                                                   "-((?:\\d+" +
                                                   Pattern.quote(CacheVersion.COMPONENT_SEPARATOR) +
                                                   ")*\\d+)$");
        return new UnusedVersionsCacheCleanup(cacheNamePattern, cacheVersionMapping,
                usedGradleVersions);
    }

    private UnusedVersionsCacheCleanup(final Pattern cacheNamePattern,
                                       CacheVersionMapping cacheVersionMapping,
                                       UsedGradleVersions usedGradleVersions) {
        super(new FilesFinder() {
            @Override
            public Iterable<File> find(File baseDir, FileFilter filter) {
                FileFilter combinedFilter = FileFilterUtils
                        .and(directoryFileFilter(), new RegexFileFilter(cacheNamePattern),
                                asFileFilter(filter),
                                asFileFilter(new NonReservedFileFilter(singleton(baseDir))));
                File[] result = baseDir.getParentFile().listFiles(combinedFilter);
                return result == null ? Collections.<File>emptySet() : Arrays.asList(result);
            }
        });
        this.cacheNamePattern = cacheNamePattern;
        this.cacheVersionMapping = cacheVersionMapping;
        this.usedGradleVersions = usedGradleVersions;
    }

    @Override
    public void clean(CleanableStore cleanableStore, CleanupProgressMonitor progressMonitor) {
        determineUsedVersions();
        super.clean(cleanableStore, progressMonitor);
    }

    private void determineUsedVersions() {
        usedVersions = Sets.newTreeSet();
        for (GradleVersion gradleVersion : getUsedGradleVersionsSmallerThanCurrent()) {
            usedVersions.addAll(cacheVersionMapping.getVersionUsedBy(gradleVersion).asSet());
        }
    }

    private SortedSet<GradleVersion> getUsedGradleVersionsSmallerThanCurrent() {
        return usedGradleVersions.getUsedGradleVersions().headSet(GradleVersion.current());
    }

    @Override
    protected boolean shouldDelete(File cacheDir) {
        Matcher matcher = cacheNamePattern.matcher(cacheDir.getName());
        if (matcher.matches()) {
            CacheVersion version = CacheVersion.parse(matcher.group(1));
            return version.compareTo(cacheVersionMapping.getLatestVersion()) < 0 &&
                   !usedVersions.contains(version);
        }
        return false;
    }

    @Override
    protected void handleDeletion(File cacheDir) {
        LOGGER.debug("Deleting unused versioned cache directory at {}", cacheDir);
    }

    @Override
    protected int deleteEmptyParentDirectories(File baseDir, File dir) {
        // do not delete parent dirs
        return 0;
    }
}
