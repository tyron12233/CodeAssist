package com.tyron.builder.gradle.options;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.concurrent.Immutable;

/** Determines if various options, triggered from the command line or environment, are set. */
@Immutable
public final class ProjectOptions {

    private final ImmutableMap<String, String> testRunnerArgs;
    private final ProviderFactory providerFactory;
    private final ImmutableMap<BooleanOption, OptionValue<BooleanOption, Boolean>>
            booleanOptionValues;
    private final ImmutableMap<OptionalBooleanOption, OptionValue<OptionalBooleanOption, Boolean>>
            optionalBooleanOptionValues;
    private final ImmutableMap<IntegerOption, OptionValue<IntegerOption, Integer>>
            integerOptionValues;
    private final ImmutableMap<ReplacedOption, OptionValue<ReplacedOption, String>>
            replacedOptionValues;
    private final ImmutableMap<StringOption, OptionValue<StringOption, String>> stringOptionValues;

    public ProjectOptions(
            @NotNull ImmutableMap<String, String> customTestRunnerArgs,
            @NotNull ProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
        testRunnerArgs = readTestRunnerArgs(customTestRunnerArgs);
        booleanOptionValues = createOptionValues(BooleanOption.values());
        optionalBooleanOptionValues = createOptionValues(OptionalBooleanOption.values());
        integerOptionValues = createOptionValues(IntegerOption.values());
        replacedOptionValues = createOptionValues(ReplacedOption.values());
        stringOptionValues = createOptionValues(StringOption.values());
        // Initialize AnalyticsSettings before we access its properties in isAnalyticsEnabled
        // function
//        AnalyticsSettings.initialize(
//                LoggerWrapper.getLogger(ProjectOptions.class),
//                null,
//                com.android.tools.analytics.Environment.getSYSTEM());
//        Environment.initialize(Environment.Companion.getSYSTEM());
    }

    @NotNull
    private <OptionT extends Option<ValueT>, ValueT>
            ImmutableMap<OptionT, OptionValue<OptionT, ValueT>> createOptionValues(
                    @NotNull OptionT[] options) {
        ImmutableMap.Builder<OptionT, OptionValue<OptionT, ValueT>> map = ImmutableMap.builder();
        for (OptionT option : options) {
            map.put(option, new OptionValue<>(option));
        }
        return map.build();
    }

    @NotNull
    private ImmutableMap<String, String> readTestRunnerArgs(Map<String, String> customArgs) {
        ImmutableMap.Builder<String, String> testRunnerArgsBuilder = ImmutableMap.builder();
        ImmutableSet.Builder<String> standardArgKeysBuilder = ImmutableSet.builder();

        // Standard test runner arguments are fully compatible with configuration caching
//        for (TestRunnerArguments arg : TestRunnerArguments.values()) {
//            standardArgKeysBuilder.add(arg.getShortKey());
//            String argValue =
//                    providerFactory
//                            .gradleProperty(arg.getFullKey())
//                            .getOrNull();
//            if (argValue != null) {
//                testRunnerArgsBuilder.put(arg.getShortKey(), argValue);
//            }
//        }
        testRunnerArgsBuilder.putAll(customArgs);
        return testRunnerArgsBuilder.build();
    }

    /** Obtain the gradle property value immediately at configuration time. */
    public boolean get(@NotNull BooleanOption option) {
        Boolean value = booleanOptionValues.get(option).getValue().getOrNull();
        if (value != null) {
            return value;
        } else {
            return option.getDefaultValue();
        }
    }

    /** Returns a provider which has the gradle property value to be obtained at execution time. */
    @NotNull
    public Provider<Boolean> getProvider(@NotNull BooleanOption option) {
        return providerFactory.provider(
                () ->
                        booleanOptionValues
                                .get(option)
                                .getValue()
                                .getOrElse(option.getDefaultValue()));
    }

    /** Obtain the gradle property value immediately at configuration time. */
    @Nullable
    public Boolean get(@NotNull OptionalBooleanOption option) {
        return optionalBooleanOptionValues.get(option).getValue().getOrNull();
    }

    /** Returns a provider which has the gradle property value to be obtained at execution time. */
    @NotNull
    public Provider<Boolean> getProvider(@NotNull OptionalBooleanOption option) {
        return optionalBooleanOptionValues.get(option).getValue();
    }

    /** Obtain the gradle property value immediately at configuration time. */
    @Nullable
    public Integer get(@NotNull IntegerOption option) {
        Integer value = integerOptionValues.get(option).getValue().getOrNull();
        if (value != null) {
            return value;
        } else {
            return option.getDefaultValue();
        }
    }

    /** Returns a provider which has the gradle property value to be obtained at execution time. */
    @NotNull
    public Provider<Integer> getProvider(@NotNull IntegerOption option) {
        return providerFactory.provider(
                () -> {
                    Integer value = integerOptionValues.get(option).getValue().getOrNull();
                    if (value != null) {
                        return value;
                    } else {
                        return option.getDefaultValue();
                    }
                });
    }

    /** Obtain the gradle property value immediately at configuration time. */
    @Nullable
    public String get(@NotNull StringOption option) {
        String value = stringOptionValues.get(option).getValue().getOrNull();
        if (value != null) {
            return value;
        } else {
            return option.getDefaultValue();
        }
    }

    /** Returns a provider which has the gradle property value to be obtained at execution time. */
    @NotNull
    public Provider<String> getProvider(@NotNull StringOption option) {
        return providerFactory.provider(
                () -> {
                    String value = stringOptionValues.get(option).getValue().getOrNull();
                    if (value != null) {
                        return value;
                    } else {
                        return option.getDefaultValue();
                    }
                });
    }

    @NotNull
    public Map<String, String> getExtraInstrumentationTestRunnerArgs() {
        return testRunnerArgs;
    }

    public boolean isAnalyticsEnabled() {
        return false;
//        return AnalyticsSettings.getOptedIn()
//                || get(BooleanOption.ENABLE_PROFILE_JSON)
//                || get(StringOption.PROFILE_OUTPUT_DIR) != null;
    }

    public <OptionT extends Option<ValueT>, ValueT>
            ImmutableMap<OptionT, ValueT> getExplicitlySetOptions(
                    ImmutableMap<OptionT, OptionValue<OptionT, ValueT>> optionValues) {
        ImmutableMap.Builder<OptionT, ValueT> mapBuilder = ImmutableMap.builder();
        for (Map.Entry<OptionT, OptionValue<OptionT, ValueT>> entry : optionValues.entrySet()) {
            ValueT value = entry.getValue().getValue().getOrNull();
            if (value != null) {
                mapBuilder.put(entry.getKey(), value);
            }
        }
        return mapBuilder.build();
    }

    public ImmutableMap<BooleanOption, Boolean> getExplicitlySetBooleanOptions() {
        return getExplicitlySetOptions(booleanOptionValues);
    }

    public ImmutableMap<OptionalBooleanOption, Boolean> getExplicitlySetOptionalBooleanOptions() {
        return getExplicitlySetOptions(optionalBooleanOptionValues);
    }

    public ImmutableMap<IntegerOption, Integer> getExplicitlySetIntegerOptions() {
        return getExplicitlySetOptions(integerOptionValues);
    }

    public ImmutableMap<StringOption, String> getExplicitlySetStringOptions() {
        return getExplicitlySetOptions(stringOptionValues);
    }

    private ImmutableMap<ReplacedOption, String> getExplicitlySetReplacedOptions() {
        return getExplicitlySetOptions(replacedOptionValues);
    }

    public ImmutableMap<Option<?>, Object> getAllOptions() {
        return new ImmutableMap.Builder()
                .putAll(getExplicitlySetReplacedOptions())
                .putAll(getExplicitlySetBooleanOptions())
                .putAll(getExplicitlySetOptionalBooleanOptions())
                .putAll(getExplicitlySetIntegerOptions())
                .putAll(getExplicitlySetStringOptions())
                .build();
    }

    private class OptionValue<OptionT extends Option<ValueT>, ValueT> {
        @Nullable
        private Provider<ValueT> value;
        @NotNull private OptionT option;

        OptionValue(@NotNull OptionT option) {
            this.option = option;
        }

        @NotNull
        private Provider<ValueT> getValue() {
            if (value == null) {
                value = setValue();
            }
            return value;
        }

        @NotNull
        private Provider<ValueT> setValue() {
            Provider<String> rawValue = providerFactory.gradleProperty(option.getPropertyName());
            return providerFactory.provider(
                    () -> {
                        String str = rawValue.getOrNull();
                        if (str == null) {
                            return null;
                        }
                        return option.parse(str);
                    });
        }
    }
}