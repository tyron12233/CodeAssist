package org.gradle.internal.resource.transport.http.android;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import cz.msebera.android.httpclient.conn.ssl.SSLConnectionSocketFactory;
import cz.msebera.android.httpclient.protocol.HttpContext;

/**
 * The implementation of the superclass checks android version
 * using Build.VERSION. Accessing android APIs is not allowed when running inside dalvikvm.
 * To work around this issue, these checks are removed since its guaranteed that we'll be running
 * on API 21+
 */
public class SSLConnectionSocketFactoryFixed extends SSLConnectionSocketFactory {

    private final SSLSocketFactory socketFactory;
    private final String[] supportedProtocols;
    private final String[] supportedCipherSuites;
    private final HostnameVerifier hostnameVerifier;

    public SSLConnectionSocketFactoryFixed(SSLContext sslContext,
                                           String[] supportedProtocols,
                                           String[] supportedCipherSuites,
                                           HostnameVerifier hostnameVerifier) {
        super(sslContext, supportedProtocols, supportedCipherSuites, hostnameVerifier);

        this.socketFactory = sslContext.getSocketFactory();
        this.supportedProtocols = supportedProtocols;
        this.supportedCipherSuites = supportedCipherSuites;
        this.hostnameVerifier = hostnameVerifier;
    }


    @Override
    public Socket createLayeredSocket(Socket socket,
                                      String target,
                                      int port,
                                      HttpContext context) throws IOException {
        final SSLSocket sslsock = (SSLSocket) this.socketFactory.createSocket(
                socket,
                target,
                port,
                true);
        if (supportedProtocols != null) {
            sslsock.setEnabledProtocols(supportedProtocols);
        } else {
            // If supported protocols are not explicitly set, remove all SSL protocol versions
            final String[] allProtocols = sslsock.getEnabledProtocols();
            final List<String> enabledProtocols = new ArrayList<String>(allProtocols.length);
            for (final String protocol: allProtocols) {
                if (!protocol.startsWith("SSL")) {
                    enabledProtocols.add(protocol);
                }
            }
            if (!enabledProtocols.isEmpty()) {
                sslsock.setEnabledProtocols(enabledProtocols.toArray(new String[0]));
            }
        }

        if (supportedCipherSuites != null) {
            sslsock.setEnabledCipherSuites(supportedCipherSuites);
        } else {
            // If cipher suites are not explicitly set, remove all insecure ones
            final String[] allCipherSuites = sslsock.getEnabledCipherSuites();
            final List<String> enabledCipherSuites = new ArrayList<String>(allCipherSuites.length);
            for (final String cipherSuite : allCipherSuites) {
                if (!isWeakCipherSuite(cipherSuite)) {
                    enabledCipherSuites.add(cipherSuite);
                }
            }
            if (!enabledCipherSuites.isEmpty()) {
                sslsock.setEnabledCipherSuites(enabledCipherSuites.toArray(new String[0]));
            }
        }

        prepareSocket(sslsock);

        try {
            Method method = sslsock.getClass().getMethod("setHostname", String.class);
            method.invoke(sslsock, target);
        } catch (Exception ex) {
            System.out.println("SNI configuration failed");
        }

        sslsock.startHandshake();
        verifyHostname(sslsock, target);
        return sslsock;
    }

    private void verifyHostname(final SSLSocket sslsock, final String hostname) throws IOException {
        try {
            SSLSession session = sslsock.getSession();
            if (session == null) {
                // In our experience this only happens under IBM 1.4.x when
                // spurious (unrelated) certificates show up in the server'
                // chain.  Hopefully this will unearth the real problem:
                final InputStream in = sslsock.getInputStream();
                in.available();
                // If ssl.getInputStream().available() didn't cause an
                // exception, maybe at least now the session is available?
                session = sslsock.getSession();
                if (session == null) {
                    // If it's still null, probably a startHandshake() will
                    // unearth the real problem.
                    sslsock.startHandshake();
                    session = sslsock.getSession();
                }
            }
            if (session == null) {
                throw new SSLHandshakeException("SSL session not available");
            }

            if (!this.hostnameVerifier.verify(hostname, session)) {
                final Certificate[] certs = session.getPeerCertificates();
                throw new SSLPeerUnverifiedException("Certificate for <" + hostname + "> doesn't match any " +
                                                     "of the subject alternative names: ");
            }
            // verifyHostName() didn't blowup - good!
        } catch (final IOException iox) {
            // close the socket before re-throwing the exception
            try { sslsock.close(); } catch (final Exception x) { /*ignore*/ }
            throw iox;
        }
    }

    private static final String WEAK_KEY_EXCHANGES
            = "^(TLS|SSL)_(NULL|ECDH_anon|DH_anon|DH_anon_EXPORT|DHE_RSA_EXPORT|DHE_DSS_EXPORT|"
              + "DSS_EXPORT|DH_DSS_EXPORT|DH_RSA_EXPORT|RSA_EXPORT|KRB5_EXPORT)_(.*)";
    private static final String WEAK_CIPHERS
            = "^(TLS|SSL)_(.*)_WITH_(NULL|DES_CBC|DES40_CBC|DES_CBC_40|3DES_EDE_CBC|RC4_128|RC4_40|RC2_CBC_40)_(.*)";

    private static final List<Pattern> WEAK_CIPHER_SUITE_PATTERNS = Collections.unmodifiableList(
            Arrays.asList(
                    Pattern.compile(WEAK_KEY_EXCHANGES, Pattern.CASE_INSENSITIVE),
                    Pattern.compile(WEAK_CIPHERS, Pattern.CASE_INSENSITIVE)));

    static boolean isWeakCipherSuite(final String cipherSuite) {
        for (final Pattern pattern : WEAK_CIPHER_SUITE_PATTERNS) {
            if (pattern.matcher(cipherSuite).matches()) {
                return true;
            }
        }
        return false;
    }
}
