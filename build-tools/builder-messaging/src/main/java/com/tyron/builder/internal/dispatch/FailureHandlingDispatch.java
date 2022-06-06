package com.tyron.builder.internal.dispatch;

public class FailureHandlingDispatch<T> implements Dispatch<T> {
    private final Dispatch<? super T> dispatch;
    private final DispatchFailureHandler<? super T> handler;

    public FailureHandlingDispatch(Dispatch<? super T> dispatch, DispatchFailureHandler<? super T> handler) {
        this.dispatch = dispatch;
        this.handler = handler;
    }

    @Override
    public void dispatch(T message) {
        try {
            dispatch.dispatch(message);
        } catch (Throwable throwable) {
            handler.dispatchFailed(message, throwable);
        }
    }
}
