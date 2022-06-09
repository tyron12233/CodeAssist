package com.tyron.builder.jvm.toolchain.install.internal;

import org.apache.commons.io.IOUtils;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransport;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransportFactory;
import com.tyron.builder.api.resources.MissingResourceException;
import com.tyron.builder.internal.resource.ExternalResource;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ResourceExceptions;
import com.tyron.builder.internal.verifier.HttpRedirectVerifier;
import com.tyron.builder.internal.verifier.HttpRedirectVerifierFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;

public class AdoptOpenJdkDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdoptOpenJdkDownloader.class);

    private final RepositoryTransportFactory repositoryTransportFactory;

    public AdoptOpenJdkDownloader(RepositoryTransportFactory repositoryTransportFactory) {
        this.repositoryTransportFactory = repositoryTransportFactory;
    }

    public void download(URI source, File tmpFile) {
        final ExternalResource resource = createExternalResource(source, tmpFile.getName());
        try {
            downloadResource(source, tmpFile, resource);
        } catch (MissingResourceException e) {
            throw new MissingResourceException(source, "Unable to download toolchain. " +
                "This might indicate that the combination " +
                "(version, architecture, release/early access, ...) for the " +
                "requested JDK is not available.", e);
        }
    }

    private ExternalResource createExternalResource(URI source, String name) {
        final ExternalResourceName resourceName = new ExternalResourceName(source) {
            @Override
            public String getShortDisplayName() {
                return name;
            }
        };
        return getTransport(source).getRepository().withProgressLogging().resource(resourceName);
    }

    private void downloadResource(URI source, File targetFile, ExternalResource resource) {
        final File downloadFile = new File(targetFile.getAbsoluteFile() + ".part");
        try {
            resource.withContent(inputStream -> {
                LOGGER.info("Downloading {} to {}", resource.getDisplayName(), targetFile);
                copyIntoFile(source, inputStream, downloadFile);
            });
            try {
                moveFile(targetFile, downloadFile);
            } catch (IOException e) {
                throw new BuildException("Unable to move downloaded toolchain to target destination", e);
            }
        } finally {
            downloadFile.delete();
        }
    }

    private void moveFile(File targetFile, File downloadFile) throws IOException {
        try {
            Files.move(downloadFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(downloadFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyIntoFile(URI source, InputStream inputStream, File destination) {
        try (FileOutputStream outputStream = new FileOutputStream(destination)) {
            IOUtils.copyLarge(inputStream, outputStream);
        } catch (IOException e) {
            throw ResourceExceptions.getFailed(source, e);
        }
    }

    private RepositoryTransport getTransport(URI source) {
        final HttpRedirectVerifier redirectVerifier;
        try {
            redirectVerifier = HttpRedirectVerifierFactory.create(new URI(source.getScheme(), source.getAuthority(), null, null, null), false, () -> {
                throw new InvalidUserCodeException("Attempting to download a JDK from an insecure URI " + source + ". This is not supported, use a secure URI instead.");
            }, uri -> {
                throw new InvalidUserCodeException("Attempting to download a JDK from an insecure URI " + uri + ". This URI was reached as a redirect from " + source + ". This is not supported, make sure no insecure URIs appear in the redirect");
            });
        } catch (URISyntaxException e) {
            throw new InvalidUserCodeException("Cannot extract host information from specified URI " + source);
        }
        return repositoryTransportFactory.createTransport("https", "adoptopenjdk toolchains", Collections.emptyList(), redirectVerifier);
    }


}
