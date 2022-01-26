package com.tyron.builder.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.util.Objects;

public class Library {

    @SerializedName("sourceFile")
    private String sourceFile;

    /**
     * Non null if this library is a dependency from maven
     */
    @SerializedName("declaration")
    private String declaration;

    public File getSourceFile() {
        return new File(sourceFile);
    }

    public String getDeclaration() {
        return declaration;
    }

    public boolean isDependency() {
        return declaration != null;
    }

    public void setSourceFile(File sourceFile) {
        this.sourceFile = sourceFile.getAbsolutePath();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof Library) {
            Library that = ((Library) obj);
            return this.sourceFile.equals(that.sourceFile);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFile);
    }

    @NonNull
    @Override
    public String toString() {
        return "sourceFile = " + sourceFile;
    }

    public void setDeclaration(String declarationString) {
        declaration = declarationString;
    }
}
