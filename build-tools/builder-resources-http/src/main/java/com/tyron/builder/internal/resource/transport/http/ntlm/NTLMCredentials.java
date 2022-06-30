package com.tyron.builder.internal.resource.transport.http.ntlm;

import com.tyron.builder.api.credentials.PasswordCredentials;

import com.google.common.base.Preconditions;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class NTLMCredentials {
    private static final String DEFAULT_DOMAIN = "";
    private static final String DEFAULT_WORKSTATION = "";
    private final String domain;
    private final String username;
    private final String password;
    private final String workstation;

    public NTLMCredentials(PasswordCredentials credentials) {
        String domain;
        String username = Preconditions.checkNotNull(credentials.getUsername(), "Username must not be null!");
        int slashPos = username.indexOf('\\');
        slashPos = slashPos >= 0 ? slashPos : username.indexOf('/');
        if (slashPos >= 0) {
            domain = username.substring(0, slashPos);
            username = username.substring(slashPos + 1);
        } else {
            domain = System.getProperty("http.auth.ntlm.domain", DEFAULT_DOMAIN);
        }
        this.domain = domain == null ? null : domain.toUpperCase();
        this.username = username;
        this.password = credentials.getPassword();
        this.workstation = determineWorkstationName();
    }

    private String determineWorkstationName() {
        // This is a hidden property that may be useful to track down issues. Remove when NTLM Auth is solid.
        String sysPropWorkstation = System.getProperty("http.auth.ntlm.workstation");
        if (sysPropWorkstation != null) {
            return sysPropWorkstation;
        }

        try {
            return removeDotSuffix(getHostName()).toUpperCase();
        } catch (UnknownHostException e) {
            return DEFAULT_WORKSTATION;
        }
    }

    protected String getHostName() throws UnknownHostException {
        return InetAddress.getLocalHost().getHostName();
    }

    private String removeDotSuffix(String val) {
        int dotPos = val.indexOf('.');
        return dotPos == -1 ? val : val.substring(0, dotPos);
    }


    public String getDomain() {
        return domain;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getWorkstation() {
        return workstation;
    }

    @Override
    public String toString() {
        return String.format("NTLM Credentials [user: %s, domain: %s, workstation: %s]", username, domain, workstation);
    }
}
