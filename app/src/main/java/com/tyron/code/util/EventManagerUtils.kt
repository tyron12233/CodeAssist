package com.tyron.code.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tyron.code.event.Event
import com.tyron.code.event.EventManager
import com.tyron.code.event.EventReceiver

fun <T : Event> EventManager.subscribeEvent(
    lifecycleOwner: LifecycleOwner,
    eventClass: Class<T>,
    eventReceiver: EventReceiver<T>
) {
    val receipt = subscribeEvent(eventClass, eventReceiver)
    lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            receipt.unsubscribe()
            lifecycleOwner.lifecycle.removeObserver(this)
        }
    })
}