package com.tyron.builder.model.internal.core;


import com.google.common.base.Predicate;
import com.tyron.builder.model.RuleSource;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.model.internal.inspect.ExtractedRuleSource;

import javax.annotation.Nullable;
import java.util.Set;

public interface MutableModelNode extends ModelNode {
    boolean canBeViewedAs(ModelType<?> type);

    /**
     * @see ModelPromise#getTypeDescriptions(MutableModelNode)
     */
    Iterable<String> getTypeDescriptions();

    /**
     * Creates a (potentially) mutable view over this node's value. When this node is not mutable, an immutable view is returned instead.
     *
     * Callers should try to {@link ModelView#close()} the returned view when it is done with, allowing any internal cleanup to occur.
     *
     * Throws if this node can't be expressed as a mutable view of the requested type.
     */
    <T> ModelView<? extends T> asMutable(ModelType<T> type, ModelRuleDescriptor ruleDescriptor);

    /**
     * Adds a reference node to the graph. A reference node is a node that refers to some other node elsewhere in the graph, similar to a symbolic link.
     */
    <T> void addReference(String name, ModelType<T> type, ModelNode target, ModelRuleDescriptor ruleDescriptor);

    /**
     * Adds a node to the graph, linked from this node. The given registration is used to initialize the node when required.
     *
     * The path returned by {@link ModelRegistration#getPath()} is used to determine the name of the link.
     */
    void addLink(ModelRegistration registration);

    /**
     * Removes a node linked from this node from the graph.
     */
    void removeLink(String name);

    /**
     * Applies an action to this node.
     */
    void applyToSelf(ModelActionRole type, ModelAction action);

    /**
     * Applies an action to all linked nodes of this node that satisfy the given predicate.
     *
     * The predicate and the type returned by {@link ModelAction#getSubject()} are both used to filter the nodes,
     * such that the action is applied only to those linked nodes with a view of the requested type available that
     * also satisfy the predicate.
     */
    void applyTo(NodePredicate predicate, ModelActionRole role, ModelAction action);

    /**
     * Applies an action to a linked node.
     *
     * The path returned by {@link ModelAction#getSubject()} is used to select the link to apply the action to.
     */
    void applyToLink(ModelActionRole type, ModelAction action);

    /**
     * Applies the rules defined in the given rule source to this node.
     */
    void applyToSelf(Class<? extends RuleSource> rules);

    /**
     * Applies the rules defined in the given rule source to this node.
     */
    void applyToSelf(ExtractedRuleSource<?> rules);

    /**
     * Applies an action that defines further rules in the given role to the child of this node that is addressed by the subject of the action.
     */
    void defineRulesForLink(ModelActionRole role, ModelAction action);

    /**
     * Applies a rule source to all linked nodes of this node that satisfy the given predicate.
     *
     * The predicate and the type returned by {@link ModelAction#getSubject()} are both used to filter the nodes,
     * such that the action is applied only to those linked nodes with a view of the requested type available that
     * also satisfy the predicate.
     */
    void applyTo(NodePredicate predicate, Class<? extends RuleSource> rules);

    /**
     * Applies an action that defines rules for the node in the given role to all nodes linked from this node.
     *
     * The type returned by {@link ModelAction#getSubject()} is used to filter the nodes, such that the action is applied only to those linked nodes with a view of the
     * requested type available.
     */
    void defineRulesFor(NodePredicate predicate, ModelActionRole role, ModelAction action);

    boolean hasLink(String name, Predicate<? super MutableModelNode> predicate);

    @Nullable
    MutableModelNode getLink(String name);

    int getLinkCount(Predicate<? super MutableModelNode> predicate);

    Set<String> getLinkNames(Predicate<? super MutableModelNode> predicate);

    Set<String> getLinkNames();

    @Override
    Iterable<? extends MutableModelNode> getLinks(ModelType<?> type);

    Iterable<? extends MutableModelNode> getLinks(Predicate<? super MutableModelNode> predicate);

    <T> void setPrivateData(Class<? super T> type, T object);

    <T> void setPrivateData(ModelType<? super T> type, T object);

    <T> T getPrivateData(Class<T> type);

    <T> T getPrivateData(ModelType<T> type);

    Object getPrivateData();

    /**
     * Change the target of this reference node. Works only on reference nodes that are in the {@link com.tyron.builder.model.internal.core.ModelNode.State#Registered} state.
     */
    void setTarget(ModelNode target);

    /**
     * Ensure that the views are available, with default values applied.
     */
    void ensureUsable();

    void ensureAtLeast(ModelNode.State state);

    boolean isAtLeast(ModelNode.State state);

    void setHidden(boolean hidden);

    boolean isMutable();

    MutableModelNode getParent();

    void addProjection(ModelProjection projection);
}
