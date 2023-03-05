package com.android.aaptcompiler.proto;

import com.android.aaptcompiler.ResourceTable;
import com.android.aaptcompiler.ResourceTablePackage;

import java.lang.reflect.Method;
import java.util.List;

public class ResourceTableAcessor {

    @SuppressWarnings("unchecked")
    public static List<ResourceTablePackage> getPackages(ResourceTable resourceTable) throws Exception {
        Method getPackages = ResourceTable.class.getDeclaredMethod("getPackages");
        getPackages.setAccessible(true);
        return (List<ResourceTablePackage>) getPackages.invoke(resourceTable);
    }
}
