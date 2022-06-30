package com.tyron.viewbinding.tool.processing;

import com.tyron.viewbinding.tool.store.Location;
import com.tyron.viewbinding.tool.util.StringUtils;

import java.util.List;

public class ScopedErrorReport {

    private final String mFilePath;

    private final List<Location> mLocations;

    /**
     * Only created by Scope
     */
    ScopedErrorReport(String filePath, List<Location> locations) {
        mFilePath = filePath;
        mLocations = locations;
    }

    public String getFilePath() {
        return mFilePath;
    }

    public List<Location> getLocations() {
        return mLocations;
    }

    public boolean isValid() {
        return StringUtils.isNotBlank(mFilePath);
    }

    public String toUserReadableString() {
        StringBuilder sb = new StringBuilder();
        if (mFilePath != null) {
            sb.append("File:");
            sb.append(mFilePath);
        }
        if (mLocations != null && mLocations.size() > 0) {
            if (mLocations.size() > 1) {
                sb.append("Locations:");
                for (Location location : mLocations) {
                    sb.append("\n    ").append(location.toUserReadableString());
                }
            } else {
                sb.append("\n    Location: ").append(mLocations.get(0).toUserReadableString());
            }
        }
        return sb.toString();
    }
}