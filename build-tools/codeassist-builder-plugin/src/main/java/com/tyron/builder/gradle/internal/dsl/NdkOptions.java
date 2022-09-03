package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.dsl.Ndk;
import com.android.utils.HelpfulEnumConverter;
import com.google.common.base.Verify;
import com.google.common.collect.Lists;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * DSL object for per-variant NDK settings, such as the ABI filter.
 *
 * @see Ndk for the public interface.
 */
public class NdkOptions implements CoreNdkOptions, Serializable, Ndk {
    private static final long serialVersionUID = 1L;
    public static final HelpfulEnumConverter<DebugSymbolLevel> DEBUG_SYMBOL_LEVEL_CONVERTER =
            new HelpfulEnumConverter<>(DebugSymbolLevel.class);

    private String moduleName;
    private String cFlags;
    private List<String> ldLibs;
    private final Set<String> abiFilters = new HashSet<>(0);
    private String stl;
    private Integer jobs;
    private DebugSymbolLevel debugSymbolLevel;

    public NdkOptions() {
    }

    public void _initWith(@NonNull CoreNdkOptions ndkConfig) {
        moduleName = ndkConfig.getModuleName();
        cFlags = ndkConfig.getcFlags();
        setLdLibs(ndkConfig.getLdLibs());
        setAbiFilters(ndkConfig.getAbiFilters());
    }

    @Override
    @Input @Optional
    public String getModuleName() {
        return moduleName;
    }

    @Override
    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    @Override
    @Input @Optional
    public String getcFlags() {
        return cFlags;
    }

    public void setcFlags(String cFlags) {
        this.cFlags = cFlags;
    }

    @Override
    public void setCFlags(@Nullable String cFlags) {
        this.cFlags = cFlags;
    }

    @Nullable
    @Override
    public String getCFlags() {
        return cFlags;
    }

    @Override
    @Input @Optional
    public List<String> getLdLibs() {
        return ldLibs;
    }

    @NonNull
    public NdkOptions ldLibs(String lib) {
        if (ldLibs == null) {
            ldLibs = Lists.newArrayList();
        }
        ldLibs.add(lib);
        return this;
    }

    @NonNull
    public NdkOptions ldLibs(String... libs) {
        if (ldLibs == null) {
            ldLibs = Lists.newArrayListWithCapacity(libs.length);
        }
        Collections.addAll(ldLibs, libs);
        return this;
    }

    @NonNull
    public NdkOptions setLdLibs(Collection<String> libs) {
        if (libs != null) {
            if (ldLibs == null) {
                ldLibs = Lists.newArrayListWithCapacity(libs.size());
            } else {
                ldLibs.clear();
            }
            ldLibs.addAll(libs);
        } else {
            ldLibs = null;
        }
        return this;
    }

    @NonNull
    @Override
    @Input
    public Set<String> getAbiFilters() {
        return abiFilters;
    }


    @NonNull
    public NdkOptions abiFilter(String filter) {
        abiFilters.add(filter);
        return this;
    }

    @NonNull
    public NdkOptions abiFilters(String... filters) {
        Collections.addAll(abiFilters, filters);
        return this;
    }

    @NonNull
    public NdkOptions setAbiFilters(Collection<String> filters) {
        abiFilters.clear();
        if (filters != null) {
            abiFilters.addAll(filters);
        }
        return this;
    }

    @Override
    @Nullable
    public String getStl() {
        return stl;
    }

    @Override
    public void setStl(String stl) {
        this.stl = stl;
    }

    @Nullable
    @Override
    public Integer getJobs() {
        return jobs;
    }

    @Override
    public void setJobs(Integer jobs) {
        this.jobs = jobs;
    }

    @Override
    @Nullable
    public String getDebugSymbolLevel() {
        if (debugSymbolLevel == null) {
            return null;
        }
        return Verify.verifyNotNull(
                DEBUG_SYMBOL_LEVEL_CONVERTER.reverse().convert(debugSymbolLevel),
                "No string representation for enum.");
    }

    @Override
    public void setDebugSymbolLevel(@Nullable String debugSymbolLevel) {
        this.debugSymbolLevel = DEBUG_SYMBOL_LEVEL_CONVERTER.convert(debugSymbolLevel);
    }

    public enum DebugSymbolLevel {
        /** Package native debug info *and* native symbol table */
        FULL,
        /** Package native symbol table but not native debug info */
        SYMBOL_TABLE,
        /** Don't package native debug info or native symbol table */
        NONE
    }
}