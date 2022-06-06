package com.tyron.builder.internal.authentication;

import com.google.common.collect.Sets;
import com.tyron.builder.api.credentials.Credentials;
import com.tyron.builder.authentication.Authentication;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

public abstract class AbstractAuthentication implements AuthenticationInternal {
    private final String name;
    private final Class<? extends Credentials> supportedCredentialType;
    private final Class<? extends Authentication> type;

    private Credentials credentials;

    private final Set<HostAndPort> hosts;

    public AbstractAuthentication(String name, Class<? extends Authentication> type) {
        this(name, type, null);
    }

    public AbstractAuthentication(String name, Class<? extends Authentication> type, Class<? extends Credentials> supportedCredential) {
        this.name = name;
        this.supportedCredentialType = supportedCredential;
        this.type = type;
        this.hosts = Sets.newHashSet();
    }

    @Override
    public Credentials getCredentials() {
        return credentials;
    }

    @Override
    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean supports(final Credentials credentials) {
        return supportedCredentialType.isAssignableFrom(credentials.getClass());
    }

    @Override
    public Class<? extends Authentication> getType() {
        return type;
    }

    @Override
    public String toString() {
        return String.format("'%s'(%s)", getName(), getType().getSimpleName());
    }


    @Override
    public Collection<HostAndPort> getHostsForAuthentication() {
        return hosts;
    }


    @Override
    public void addHost(String host, int port) {
        hosts.add(new DefaultHostAndPort(host, port));
    }

    private static class DefaultHostAndPort implements HostAndPort {
        private final String host;
        private final int port;

        DefaultHostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DefaultHostAndPort that = (DefaultHostAndPort) o;
            return getPort() == that.getPort() &&
                    Objects.equals(getHost(), that.getHost());
        }

        @Override
        public int hashCode() {
            return Objects.hash(getHost(), getPort());
        }
    }
}
