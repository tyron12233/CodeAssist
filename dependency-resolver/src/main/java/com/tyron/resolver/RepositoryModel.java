package com.tyron.resolver;

/**
 * Representation of JSON repository declaration
 */
public class RepositoryModel {

    private String name;

    private String url;

    public RepositoryModel() {

    }

    public RepositoryModel(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
