package com.tyron.builder.compiler.manifest.xml;

public interface IAbstractFolder extends IAbstractResource {

    interface FilenameFilter {
        boolean accept(IAbstractFolder dir, String name);
    }

    boolean hasFile(String name);

    IAbstractFile getFile(String name);

    IAbstractFolder getFolder(String name);

    IAbstractResource[] listMembers();

    String[] list(FilenameFilter filter);
}
