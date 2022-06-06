package com.tyron.eclipse.formatter;

import org.osgi.framework.FrameworkUtil;

public class BundleLauncher {

    public static void launch() {
        assert FrameworkUtil.getBundle(BundleLauncher.class) != null;
    }
}
