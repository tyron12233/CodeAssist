package com.tyron.code.util;
import java.security.Permission;

/**
 * Prevents third party libraries shutting down the vm by 
 * throwing a security exception instead of exiting.
 */
public class NoExitSecurityManager extends SecurityManager {
    
    private SecurityManager baseSecurityManager;

    public NoExitSecurityManager(SecurityManager baseSecurityManager) {
        this.baseSecurityManager = baseSecurityManager;
    }

    @Override
    public void checkPermission(Permission permission) {
        if (permission.getName().startsWith("exitVM")) {
            throw new SecurityException("System exit not allowed");
        }
        if (baseSecurityManager != null) {
            baseSecurityManager.checkPermission(permission);
        }
    }
}
