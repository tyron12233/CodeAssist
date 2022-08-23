package com.tyron.builder.common.blame;

import org.jetbrains.annotations.NotNull;

/**
 * A message receiver.
 *
 * {@link MessageReceiver}s receive build {@link Message}s and either
 * <ul><li>Output them to a logging system</li>
 * <li>Output them to a user interface</li>
 * <li>Transform them, such as mapping from intermediate files back to source files</li></ul>
 */
public interface MessageReceiver {

    /**
     * Process the given message.
     */
    void receiveMessage(@NotNull Message message);
}