package com.tyron.viewbinding.tool.ext

import com.squareup.javapoet.TypeName
import javax.lang.model.type.MirroredTypeException

// type extraction that handles type mirrors
fun safeType(f : () -> String) : String {
    return try {
        f()
    } catch (mirrorExp : MirroredTypeException) {
        TypeName.get(mirrorExp.typeMirror).toString()
    }
}