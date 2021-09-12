package com.tyron.code.compiler.manifest.xml;

public interface IAbstractResource {

    String getName();

    String getLocation();

    boolean exists();

    IAbstractFolder getParentFolder();

    boolean delete();
}
