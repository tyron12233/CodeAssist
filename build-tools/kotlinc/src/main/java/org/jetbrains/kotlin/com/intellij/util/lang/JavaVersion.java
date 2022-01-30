//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.jetbrains.kotlin.com.intellij.util.lang;

import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;

public final class JavaVersion implements Comparable<JavaVersion> {
    public final int feature;
    public final int minor;
    public final int update;
    public final int build;
    public final boolean ea;
    private static JavaVersion current;

    private JavaVersion(int feature, int minor, int update, int build, boolean ea) {
        this.feature = feature;
        this.minor = minor;
        this.update = update;
        this.build = build;
        this.ea = ea;
    }

    public int compareTo( JavaVersion o) {

        int diff = this.feature - o.feature;
        if (diff != 0) {
            return diff;
        } else {
            diff = this.minor - o.minor;
            if (diff != 0) {
                return diff;
            } else {
                diff = this.update - o.update;
                if (diff != 0) {
                    return diff;
                } else {
                    diff = this.build - o.build;
                    return diff != 0 ? diff : (this.ea ? 0 : 1) - (o.ea ? 0 : 1);
                }
            }
        }
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof JavaVersion)) {
            return false;
        } else {
            JavaVersion other = (JavaVersion)o;
            return this.feature == other.feature && this.minor == other.minor && this.update == other.update && this.build == other.build && this.ea == other.ea;
        }
    }

    public int hashCode() {
        int hash = this.feature;
        hash = 31 * hash + this.minor;
        hash = 31 * hash + this.update;
        hash = 31 * hash + this.build;
        hash = 31 * hash + (this.ea ? 1231 : 1237);
        return hash;
    }

    public String toString() {
        return this.formatVersionTo(false, false);
    }

    private String formatVersionTo(boolean upToFeature, boolean upToUpdate) {
        StringBuilder sb = new StringBuilder();
        if (this.feature > 8) {
            sb.append(this.feature);
            if (!upToFeature) {
                if (this.minor > 0 || this.update > 0) {
                    sb.append('.').append(this.minor);
                }

                if (this.update > 0) {
                    sb.append('.').append(this.update);
                }

                if (!upToUpdate) {
                    if (this.ea) {
                        sb.append("-ea");
                    }

                    if (this.build > 0) {
                        sb.append('+').append(this.build);
                    }
                }
            }
        } else {
            sb.append("1.").append(this.feature);
            if (!upToFeature) {
                if (this.minor > 0 || this.update > 0 || this.ea || this.build > 0) {
                    sb.append('.').append(this.minor);
                }

                if (this.update > 0) {
                    sb.append('_').append(this.update);
                }

                if (!upToUpdate) {
                    if (this.ea) {
                        sb.append("-ea");
                    }

                    if (this.build > 0) {
                        sb.append("-b").append(this.build);
                    }
                }
            }
        }

        return sb.toString();
    }


    public static JavaVersion compose(int feature, int minor, int update, int build, boolean ea) throws IllegalArgumentException {
        if (feature < 0) {
            throw new IllegalArgumentException();
        } else if (minor < 0) {
            throw new IllegalArgumentException();
        } else if (update < 0) {
            throw new IllegalArgumentException();
        } else if (build < 0) {
            throw new IllegalArgumentException();
        } else {
            return new JavaVersion(feature, minor, update, build, ea);
        }
    }


    public static JavaVersion compose(int feature) {
        return compose(feature, 0, 0, 0, false);
    }


    public static JavaVersion current() {
        if (current == null) {
            JavaVersion fallback = parse(System.getProperty("java.version"));
            JavaVersion rt = rtVersion();
            if (rt == null) {
                try {
                    rt = parse(System.getProperty("java.runtime.version"));
                } catch (Throwable var3) {
                }
            }

            current = rt != null && rt.feature == fallback.feature && rt.minor == fallback.minor ? rt : fallback;
        }

        JavaVersion var10000 = current;

        return var10000;
    }

    private static JavaVersion rtVersion() {
        try {
            Object version = Runtime.class.getMethod("version").invoke((Object)null);
            int major = (Integer)version.getClass().getMethod("major").invoke(version);
            int minor = (Integer)version.getClass().getMethod("minor").invoke(version);
            int security = (Integer)version.getClass().getMethod("security").invoke(version);
            Object buildOpt = version.getClass().getMethod("build").invoke(version);
            int build = (Integer)buildOpt.getClass().getMethod("orElse", Object.class).invoke(buildOpt, 0);
            Object preOpt = version.getClass().getMethod("pre").invoke(version);
            boolean ea = (Boolean)preOpt.getClass().getMethod("isPresent").invoke(preOpt);
            return new JavaVersion(major, minor, security, build, ea);
        } catch (Throwable var8) {
            return null;
        }
    }


    public static JavaVersion parse( String versionString) throws IllegalArgumentException {
        return new JavaVersion(8, 0, 0, 69, false);
    }

    private static boolean startsWithWord(String s, String word) {
        return s.startsWith(word) && (s.length() == word.length() || !Character.isLetterOrDigit(s.charAt(word.length())));
    }
}
