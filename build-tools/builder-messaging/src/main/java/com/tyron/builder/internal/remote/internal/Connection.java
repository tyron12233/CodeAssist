package com.tyron.builder.internal.remote.internal;

import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.dispatch.Dispatch;
import com.tyron.builder.internal.dispatch.Receive;

/**
 * <p>A messaging endpoint which allows push-style dispatch and pull-style receive.
 *
 * <p>Implementations are not guaranteed to be completely thread-safe.
 * However, the implementations:
 * <ul>
 * <li>should allow separate threads for dispatching and receiving, i.e. single thread that dispatches
 * and a different single thread that receives should be perfectly safe</li>
 * <li>should allow stopping or requesting stopping from a different thread than receiving/dispatching</li>
 * <li>don't guarantee allowing multiple threads dispatching</li>
 * </ul>
 */
public interface Connection<T> extends Dispatch<T>, Receive<T>, Stoppable {
}
