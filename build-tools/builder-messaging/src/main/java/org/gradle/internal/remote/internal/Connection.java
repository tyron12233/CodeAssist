package org.gradle.internal.remote.internal;

import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.dispatch.Dispatch;
import org.gradle.internal.dispatch.Receive;

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
