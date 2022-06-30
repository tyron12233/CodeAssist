//package com.tyron.builder.tooling.internal.provider;
//
//import com.tyron.builder.initialization.BuildEventConsumer;
//import com.tyron.builder.internal.buildTree.BuildActionRunner;
//import com.tyron.builder.internal.buildtree.BuildActionRunner;
//import com.tyron.builder.internal.event.ListenerManager;
//import com.tyron.builder.internal.invocation.BuildAction;
//import com.tyron.builder.internal.operations.BuildOperationListener;
//import com.tyron.builder.internal.operations.BuildOperationListenerManager;
//import com.tyron.builder.internal.session.BuildSessionActionExecutor;
//import com.tyron.builder.internal.session.BuildSessionContext;
//
//import java.util.ArrayList;
//import java.util.List;
//
///**
// * Attaches build operation listeners to forward relevant operations back to the client.
// */
//public class SubscribableBuildActionExecutor implements BuildSessionActionExecutor {
//    private final BuildEventConsumer eventConsumer;
//    private final BuildSessionActionExecutor delegate;
//    private final ListenerManager listenerManager;
//    private final BuildOperationListenerManager buildOperationListenerManager;
//    private final List<Object> listeners = new ArrayList<>();
//    private final BuildEventListenerFactory factory;
//
//    public SubscribableBuildActionExecutor(ListenerManager listenerManager,
//                                           BuildOperationListenerManager buildOperationListenerManager,
//                                           BuildEventListenerFactory factory,
//                                           BuildEventConsumer eventConsumer,
//                                           BuildSessionActionExecutor delegate) {
//        this.listenerManager = listenerManager;
//        this.buildOperationListenerManager = buildOperationListenerManager;
//        this.factory = factory;
//        this.eventConsumer = eventConsumer;
//        this.delegate = delegate;
//    }
//
//    @Override
//    public BuildActionRunner.Result execute(BuildAction action, BuildSessionContext buildSession) {
//        if (action instanceof SubscribableBuildAction) {
//            SubscribableBuildAction subscribableBuildAction = (SubscribableBuildAction) action;
//            registerListenersForClientSubscriptions(subscribableBuildAction.getClientSubscriptions(), eventConsumer);
//        }
//        try {
//            return delegate.execute(action, buildSession);
//        } finally {
//            for (Object listener : listeners) {
//                listenerManager.removeListener(listener);
//                if (listener instanceof BuildOperationListener) {
//                    buildOperationListenerManager.removeListener((BuildOperationListener) listener);
//                }
//            }
//            listeners.clear();
//        }
//    }
//
//    private void registerListenersForClientSubscriptions(BuildEventSubscriptions clientSubscriptions, BuildEventConsumer eventConsumer) {
//        for (Object listener : factory.createListeners(clientSubscriptions, eventConsumer)) {
//            registerListener(listener);
//        }
//    }
//
//    private void registerListener(Object listener) {
//        listeners.add(listener);
//        listenerManager.addListener(listener);
//        if (listener instanceof BuildOperationListener) {
//            buildOperationListenerManager.addListener((BuildOperationListener) listener);
//        }
//    }
//}
