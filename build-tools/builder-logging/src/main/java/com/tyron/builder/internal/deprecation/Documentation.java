package com.tyron.builder.internal.deprecation;

import com.google.common.base.Preconditions;
import com.tyron.builder.api.internal.DocumentationRegistry;

public abstract class Documentation {
    private static final DocumentationRegistry DOCUMENTATION_REGISTRY = new DocumentationRegistry();

    static final Documentation NO_DOCUMENTATION = new NullDocumentation();

    public static Documentation userManual(String id, String section) {
        return new UserGuide(id, section);
    }

    static Documentation userManual(String id) {
        return new UserGuide(id, null);
    }

    static Documentation upgradeGuide(int majorVersion, String upgradeGuideSection) {
        return new UpgradeGuide(majorVersion, upgradeGuideSection);
    }

    public static Documentation dslReference(Class<?> targetClass, String property) {
        return new DslReference(targetClass, property);
    }

    abstract String documentationUrl();

    public String consultDocumentationMessage() {
        return String.format("See %s for more details.", documentationUrl());
    }

    private static class NullDocumentation extends Documentation {

        private NullDocumentation() {
        }

        @Override
        String documentationUrl() {
            return null;
        }

        @Override
        public String consultDocumentationMessage() {
            return null;
        }
    }

    private static class UserGuide extends Documentation {
        private final String id;
        private final String section;

        private UserGuide(String id, String section) {
            this.id = Preconditions.checkNotNull(id);
            this.section = section;
        }

        @Override
        String documentationUrl() {
            if (section != null) {
                return DOCUMENTATION_REGISTRY.getDocumentationFor(id, section);
            }
            return DOCUMENTATION_REGISTRY.getDocumentationFor(id);
        }
    }

    private static class UpgradeGuide extends Documentation {
        private final int majorVersion;
        private final String section;

        private UpgradeGuide(int majorVersion, String section) {
            this.majorVersion = majorVersion;
            this.section = Preconditions.checkNotNull(section);
        }

        @Override
        String documentationUrl() {
            return DOCUMENTATION_REGISTRY.getDocumentationFor("upgrading_version_" + majorVersion, section);
        }

        @Override
        public String consultDocumentationMessage() {
            return "Consult the upgrading guide for further information: " + documentationUrl();
        }
    }

    private static class DslReference extends Documentation {
        private final Class<?> targetClass;
        private final String property;

        public DslReference(Class<?> targetClass, String property) {
            this.targetClass = Preconditions.checkNotNull(targetClass);
            this.property = Preconditions.checkNotNull(property);
        }

        @Override
        String documentationUrl() {
            return DOCUMENTATION_REGISTRY.getDslRefForProperty(targetClass, property);
        }
    }

}


