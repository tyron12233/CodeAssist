package org.gradle.authentication.http;

import org.gradle.authentication.Authentication;

/**
 * Authentication scheme for basic access authentication over HTTP. When using this scheme, credentials are sent preemptively.
 */
public interface BasicAuthentication extends Authentication {
}
