package com.tyron.builder.compiler.manifest.resources;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * This class gives access to the bidirectional relationship between {@link ResourceType} and
 * {@link ResourceFolderType}.
 */
public final class FolderTypeRelationship {

    private static final Map<ResourceType, List<ResourceFolderType>> mTypeToFolderMap =
            new EnumMap<>(ResourceType.class);

    private static final Map<ResourceFolderType, List<ResourceType>> mFolderToTypeMap =
            new EnumMap<>(ResourceFolderType.class);

    static {
        // generate the relationships in a temporary map

        // Non-id resources which appear in non-value folders.
        add(ResourceType.ANIM, ResourceFolderType.ANIM);
        add(ResourceType.ANIMATOR, ResourceFolderType.ANIMATOR);
        add(ResourceType.COLOR, ResourceFolderType.COLOR);
        add(ResourceType.DRAWABLE, ResourceFolderType.DRAWABLE);
        add(ResourceType.FONT, ResourceFolderType.FONT);
        add(ResourceType.INTERPOLATOR, ResourceFolderType.INTERPOLATOR);
        add(ResourceType.LAYOUT, ResourceFolderType.LAYOUT);
        add(ResourceType.MENU, ResourceFolderType.MENU);
        add(ResourceType.MIPMAP, ResourceFolderType.MIPMAP);
        add(ResourceType.RAW, ResourceFolderType.RAW);
        add(ResourceType.TRANSITION, ResourceFolderType.TRANSITION);
        add(ResourceType.XML, ResourceFolderType.XML);
        add(ResourceType.NAVIGATION, ResourceFolderType.NAVIGATION);


        // Resource types which can appear in values/
        add(ResourceType.ARRAY, ResourceFolderType.VALUES);
        add(ResourceType.ATTR, ResourceFolderType.VALUES);
        add(ResourceType.BOOL, ResourceFolderType.VALUES);
        add(ResourceType.COLOR, ResourceFolderType.VALUES);
        add(ResourceType.DIMEN, ResourceFolderType.VALUES);
        add(ResourceType.DRAWABLE, ResourceFolderType.VALUES);
        add(ResourceType.FRACTION, ResourceFolderType.VALUES);
        add(ResourceType.ID, ResourceFolderType.VALUES);
        add(ResourceType.INTEGER, ResourceFolderType.VALUES);
        add(ResourceType.MACRO, ResourceFolderType.VALUES);
        add(ResourceType.PLURALS, ResourceFolderType.VALUES);
        add(ResourceType.PUBLIC, ResourceFolderType.VALUES);
        add(ResourceType.STRING, ResourceFolderType.VALUES);
        add(ResourceType.STYLE, ResourceFolderType.VALUES);
        add(ResourceType.STYLE_ITEM, ResourceFolderType.VALUES);
        add(ResourceType.STYLEABLE, ResourceFolderType.VALUES);

        // Folders which can contain id declarations
        // These are added to the map last to ensure that the first
        // resource type we get out of a folder map is the primary
        // resource type, not the id.
        add(ResourceType.ID, ResourceFolderType.DRAWABLE);
        add(ResourceType.ID, ResourceFolderType.LAYOUT);
        add(ResourceType.ID, ResourceFolderType.MENU);
        add(ResourceType.ID, ResourceFolderType.NAVIGATION);
        add(ResourceType.ID, ResourceFolderType.TRANSITION);
        add(ResourceType.ID, ResourceFolderType.XML);

        makeSafe();
    }

    /**
     * The ID-providing relationship is also encoded in the above maps by having a folder map to two
     * ResourceTypes, with the second ResourceType being ID.
     */
    private static final EnumSet<ResourceFolderType> ID_PROVIDING_RESOURCE_TYPES =
            EnumSet.of(
                    ResourceFolderType.LAYOUT,
                    ResourceFolderType.MENU,
                    ResourceFolderType.DRAWABLE,
                    ResourceFolderType.XML,
                    ResourceFolderType.TRANSITION,
                    ResourceFolderType.NAVIGATION);

    /**
     * Returns a list of {@link ResourceType}s that can be generated from files inside a folder of
     * the specified type.
     *
     * @param folderType The folder type.
     * @return a list of {@link ResourceType}, possibly empty but never null.
     */
    @NotNull
    public static List<ResourceType> getRelatedResourceTypes(
            @NotNull ResourceFolderType folderType) {
        List<ResourceType> list = mFolderToTypeMap.get(folderType);
        if (list != null) {
            return list;
        }

        return Collections.emptyList();
    }

    /**
     * Returns a single ResourceType that can be generated from files inside a folder of the
     * specified type and which is not {@link ResourceType#ID}.
     *
     * @param folderType The folder type.
     * @return a single non-ID {@link ResourceType}.
     */
    @NotNull
    public static ResourceType getNonIdRelatedResourceType(@NotNull ResourceFolderType folderType) {
        List<ResourceType> resourceTypes = getRelatedResourceTypes(folderType);
        if (resourceTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    // {@link FolderTypeRelationshipTest#testResourceFolderType} guarantees this
                    // should never happen
                    String.format(
                            "No resource types defined for given folder type: %s",
                            folderType.getName()));
        }
        ResourceType resourceType = resourceTypes.get(0);
        if (resourceTypes.size() > 1 && resourceType == ResourceType.ID) {
            resourceType = resourceTypes.get(1);
        }
        return resourceType;
    }

    /**
     * Returns a list of {@link ResourceFolderType} that can contain files generating resources of
     * the specified type.
     *
     * @param resType the type of resource.
     * @return a list of {@link ResourceFolderType}, possibly empty but never null.
     */
    @NotNull
    public static List<ResourceFolderType> getRelatedFolders(@NotNull ResourceType resType) {
        List<ResourceFolderType> list = mTypeToFolderMap.get(resType);
        if (list != null) {
            return list;
        }

        return Collections.emptyList();
    }

    /**
     * Returns the {@link ResourceFolderType} corresponding to a given {@link ResourceType}, that is
     * a folder that can contain files generating resources of the specified type and is not {@link
     * ResourceFolderType#VALUES}, if one exists.
     *
     * <p>Returns null for {@link ResourceType#ID}, since there's not a single {@link
     * ResourceFolderType} that contains only ids.
     */
    @Nullable
    public static ResourceFolderType getNonValuesRelatedFolder(@NotNull ResourceType resType) {
        if (resType == ResourceType.ID) {
            return null;
        }

        List<ResourceFolderType> list = mTypeToFolderMap.get(resType);
        if (list != null) {
            for (ResourceFolderType type : list) {
                if (type != ResourceFolderType.VALUES) {
                    return type;
                }
            }
        }

        return null;
    }

    /**
     * Check if a folder may contain ID generating types (via android:id="@+id/xyz").
     * @param folderType The folder type.
     * @return true if folder may contain ID generating types.
     */
    public static boolean isIdGeneratingFolderType(@NotNull ResourceFolderType folderType) {
        return ID_PROVIDING_RESOURCE_TYPES.contains(folderType);
    }

    @NotNull
    public static Collection<ResourceFolderType> getIdGeneratingFolderTypes() {
        return ID_PROVIDING_RESOURCE_TYPES;
    }

    /**
     * Returns true if the {@link ResourceType} and the {@link ResourceFolderType} values match.
     * @param resType the resource type.
     * @param folderType the folder type.
     * @return true if files inside the folder of the specified {@link ResourceFolderType}
     * could generate a resource of the specified {@link ResourceType}
     */
    public static boolean match(@NotNull ResourceType resType, @NotNull ResourceFolderType folderType) {
        List<ResourceFolderType> list = mTypeToFolderMap.get(resType);

        if (list != null) {
            return list.contains(folderType);
        }

        return false;
    }

    /**
     * Adds a {@link ResourceType} - {@link ResourceFolderType} relationship. this indicates that
     * a file in the folder can generate a resource of the specified type.
     * @param type The resourceType
     * @param folder The {@link ResourceFolderType}
     */
    private static void add(ResourceType type, ResourceFolderType folder) {
        // first we add the folder to the list associated with the type.
        List<ResourceFolderType> folderList = mTypeToFolderMap.get(type);
        if (folderList == null) {
            folderList = new ArrayList<>();
            mTypeToFolderMap.put(type, folderList);
        }
        if (folderList.indexOf(folder) == -1) {
            folderList.add(folder);
        }

        // now we add the type to the list associated with the folder.
        List<ResourceType> typeList = mFolderToTypeMap.get(folder);
        if (typeList == null) {
            typeList = new ArrayList<>();
            mFolderToTypeMap.put(folder, typeList);
        }
        if (typeList.indexOf(type) == -1) {
            typeList.add(type);
        }
    }

    /**
     * Makes the maps safe by replacing the current list values with unmodifiable lists.
     */
    private static void makeSafe() {
        for (ResourceType type : ResourceType.values()) {
            List<ResourceFolderType> list = mTypeToFolderMap.get(type);
            if (list != null) {
                // getNonValuesRelatedFolder above assumes every resource belongs in only one non-values folder.
                assert type == ResourceType.ID || list.size() <= 2;

                // replace with a unmodifiable list wrapper around the current list.
                mTypeToFolderMap.put(type, Collections.unmodifiableList(list));
            }
        }

        for (ResourceFolderType folder : ResourceFolderType.values()) {
            List<ResourceType> list = mFolderToTypeMap.get(folder);
            if (list != null) {
                // replace with a unmodifiable list wrapper around the current list.
                mFolderToTypeMap.put(folder, Collections.unmodifiableList(list));
            }
        }
    }
}
