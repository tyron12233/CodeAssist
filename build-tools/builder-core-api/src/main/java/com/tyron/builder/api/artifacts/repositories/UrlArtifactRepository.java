package com.tyron.builder.api.artifacts.repositories;

import java.net.URI;

/**
 * A repository that supports resolving artifacts from a URL.
 *
 * @since 6.0
 */
public interface UrlArtifactRepository {

    /**
     * The base URL of this repository.
     *
     * @return The URL.
     */
    URI getUrl();

    /**
     * Sets the base URL of this repository.
     *
     * @param url The base URL.
     */
    void setUrl(URI url);

    /**
     * Sets the base URL of this repository.
     *
     * @param url The base URL.
     */
    void setUrl(Object url);

    /**
     * Specifies whether it is acceptable to communicate with a repository over an insecure HTTP connection.
     * <p>
     * For security purposes this intentionally requires a user to opt-in to using insecure protocols on case by case basis.
     * <p>
     * Gradle intentionally does not offer a global system/gradle property that allows a universal disable of this check.
     * <p>
     * <b>Allowing communication over insecure protocols allows for a man-in-the-middle to impersonate the intended server,
     * and gives an attacker the ability to
     * <a href="https://max.computer/blog/how-to-take-over-the-computer-of-any-java-or-clojure-or-scala-developer/">serve malicious executable code onto the system.</a>
     * </b>
     * <p>
     * See also:
     * <a href="https://medium.com/bugbountywriteup/want-to-take-over-the-java-ecosystem-all-you-need-is-a-mitm-1fc329d898fb">Want to take over the Java ecosystem? All you need is a MITM!</a>
     */
    boolean isAllowInsecureProtocol();

    /**
     * Specifies whether it is acceptable to communicate with a repository over an insecure HTTP connection.
     *
     * @see #isAllowInsecureProtocol()
     */
    void setAllowInsecureProtocol(boolean allowInsecureProtocol);
}
