package com.tyron.builder.initialization;


import com.tyron.builder.internal.dispatch.Dispatch;

/**
 * A consumer for build events provided by the build requester. This can be used to forward events to the build requester.
 */
public interface BuildEventConsumer extends Dispatch<Object> {
}