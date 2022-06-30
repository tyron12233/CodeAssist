package com.tyron.builder.wrapper;

import java.net.URI;

public interface DownloadProgressListener {
    /**
     * Reports the current progress of the download
     *
     * @param address       distribution url
     * @param contentLength the content length of the distribution, or -1 if the content length is not known.
     * @param downloaded    the total amount of currently downloaded bytes
     */
    void downloadStatusChanged(URI address, long contentLength, long downloaded);
}
