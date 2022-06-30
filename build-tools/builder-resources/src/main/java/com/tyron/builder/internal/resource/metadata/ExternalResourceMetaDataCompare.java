package com.tyron.builder.internal.resource.metadata;

import com.tyron.builder.internal.Factory;

import javax.annotation.Nullable;
import java.util.Date;

public abstract class ExternalResourceMetaDataCompare {
    public static boolean isDefinitelyUnchanged(@Nullable ExternalResourceMetaData local, Factory<ExternalResourceMetaData> remoteFactory) {
        if (local == null) {
            return false;
        }

        String localEtag = local.getEtag();

        Date localLastModified = local.getLastModified();
        if (localEtag == null && localLastModified == null) {
            return false;
        }

        long localContentLength = local.getContentLength();
        if (localEtag == null && localContentLength < 1) {
            return false;
        }

        // We have enough local data to make a comparison, get the remote metadata
        ExternalResourceMetaData remote = remoteFactory.create();
        if (remote == null) {
            return false;
        }

        String remoteEtag = remote.getEtag();
        if (localEtag != null && remoteEtag != null) {
            return localEtag.equals(remoteEtag);
        }

        Date remoteLastModified = remote.getLastModified();
        if (remoteLastModified == null) {
            return false;
        }

        long remoteContentLength = remote.getContentLength();
        //noinspection SimplifiableIfStatement
        if (remoteContentLength < 1) {
            return false;
        }

        return localContentLength == remoteContentLength && remoteLastModified.equals(localLastModified);
    }
}
