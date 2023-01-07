package com.tyron.code.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

fun <T : ViewModel> ViewModelStoreOwner.viewModel(clazz: Class<T>, vararg params: Any?): T {
    return ViewModelProvider(this, object : ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val types : Array<Class<*>> = params.map { it!!::class.java }.toTypedArray()
            @Suppress("UNCHECKED_CAST")
            return clazz.getConstructor(*types).newInstance(*params) as T
        }
    })[clazz]
}