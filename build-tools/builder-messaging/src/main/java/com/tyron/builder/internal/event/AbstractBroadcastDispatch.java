package com.tyron.builder.internal.event;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.dispatch.Dispatch;
import com.tyron.builder.internal.dispatch.MethodInvocation;
import com.tyron.builder.internal.operations.BuildOperationInvocationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public abstract class AbstractBroadcastDispatch<T> implements Dispatch<MethodInvocation> {
    protected final Class<T> type;

    public AbstractBroadcastDispatch(Class<T> type) {
        this.type = type;
    }

    private String getErrorMessage() {
        String typeDescription = type.getSimpleName().replaceAll("(\\p{Upper})", " $1").trim().toLowerCase();
        return "Failed to notify " + typeDescription + ".";
    }

    protected void dispatch(MethodInvocation invocation, Dispatch<MethodInvocation> handler) {
        try {
            handler.dispatch(invocation);
        } catch (UncheckedException e) {
            throw new ListenerNotificationException(invocation, getErrorMessage(), Collections.singletonList(e.getCause()));
        } catch (BuildOperationInvocationException e) {
            throw new ListenerNotificationException(invocation, getErrorMessage(), Collections.singletonList(e.getCause()));
        } catch (RuntimeException t) {
            throw t;
        } catch (Throwable t) {
            throw new ListenerNotificationException(invocation, getErrorMessage(), Collections.singletonList(t));
        }
    }

    protected void dispatch(MethodInvocation invocation, Iterator<? extends Dispatch<MethodInvocation>> handlers) {
        // Defer creation of failures list, assume dispatch will succeed
        List<Throwable> failures = null;
        while (handlers.hasNext()) {
            Dispatch<MethodInvocation> handler = handlers.next();
            try {
                handler.dispatch(invocation);
            } catch (ListenerNotificationException e) {
                if (failures == null) {
                    failures = new ArrayList<Throwable>();
                }
                if (e.getEvent() == invocation) {
                    failures.addAll(e.getCauses());
                } else {
                    failures.add(e);
                }
            } catch (UncheckedException e) {
                if (failures == null) {
                    failures = new ArrayList<Throwable>();
                }
                failures.add(e.getCause());
            } catch (Throwable t) {
                if (failures == null) {
                    failures = new ArrayList<Throwable>();
                }
                failures.add(t);
            }
        }
        if (failures == null) {
            return;
        }
        if (failures.size() == 1 && failures.get(0) instanceof RuntimeException) {
            throw (RuntimeException) failures.get(0);
        }
        throw new ListenerNotificationException(invocation, getErrorMessage(), failures);
    }
}