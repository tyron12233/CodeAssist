package com.tyron.builder.internal.resource.transport.http;

import com.tyron.builder.util.GUtil;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class AndroidSslUtils {

    private static KeyStore getKeyStore(String fileName) {
        KeyStore keyStore = null;
        try {
            Object applicationContext = getApplicationContext();
            Object assetManager = getAssetManager(applicationContext);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            Method open = assetManager.getClass().getDeclaredMethod("open", String.class);

            InputStream caInput = (InputStream) open.invoke(assetManager, fileName);

            Certificate ca;
            try {
                ca = cf.generateCertificate(caInput);
            } finally {
                caInput.close();
            }

            String keyStoreType = KeyStore.getDefaultType();
            keyStore = KeyStore.getInstance(keyStoreType);
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", ca);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return keyStore;
    }

    private static Object getApplicationContext() {
        return GUtil.uncheckedCall(() -> {
            Class<?> applicationLoader = Class.forName("com.tyron.code.ApplicationLoader");
            Field applicationContext = applicationLoader.getDeclaredField("applicationContext");
            return applicationContext.get(null);
        });
    }

    private static Object getAssetManager(Object context) {
        return GUtil.uncheckedCall(() -> {
            Method getAssets = context.getClass().getMethod("getAssets");
            return getAssets.invoke(context);
        });
    }
}
