package com.tyron.builder.wrapper;

import java.io.File;
import java.net.URI;

public interface IDownload {
    void download(URI address, File destination) throws Exception;
}
