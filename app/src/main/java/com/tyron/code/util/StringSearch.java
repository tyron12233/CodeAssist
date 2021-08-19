package com.tyron.code.util;
import java.util.regex.Pattern;
import com.tyron.code.parser.FileManager;
import java.io.BufferedReader;
import java.util.regex.Matcher;
import java.io.File;
import java.io.IOException;

public class StringSearch {
    
    public static boolean matchesPartialName(CharSequence candidate, CharSequence partialName) {
        if (candidate.length() < partialName.length()) return false;
        for (int i = 0; i < partialName.length(); i++) {
            if (candidate.charAt(i) != partialName.charAt(i)) return false;
        }
        return true;
    }
    
    public static String packageName(File file) {
        Pattern packagePattern = Pattern.compile("^package +(.*);");
        Pattern startOfClass = Pattern.compile("^[\\w ]*class +\\w+");
        try (BufferedReader lines = FileManager.lines(file)) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (startOfClass.matcher(line).find()) return "";
                Matcher matchPackage = packagePattern.matcher(line);
                if (matchPackage.matches()) {
                    String id = matchPackage.group(1);
                    return id;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // TODO fall back on parsing file
        return "";
    }
    
}
