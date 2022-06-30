package com.tyron.builder.api.artifacts.verification;

/**
 * The different dependency verification modes. By default, Gradle
 * will use the strict mode, which means that it will verify dependencies
 * and fail <i>as soon as possible</i>, to avoid as much compromising of
 * the builds as possible.
 *
 * There are, however, two additional modes which can be used: the lenient
 * one will collect all errors but only log them to the CLI. This is useful
 * when updating the file and you want to collect as many errors as possible
 * before failing.
 *
 * The last one is "off", meaning that even if verification metadata is
 * present, Gradle will not perform any verification. This can typically
 * be used whenever verification should only happen on CI.
 *
 * @since 6.2
 */
public enum DependencyVerificationMode {
    STRICT, // the default, fail as soon as possible
    LENIENT, // do not fail, but report all verification failures on console
    OFF // verification is disabled
}
