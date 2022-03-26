package com.tyron.builder.api.execution.plan;

import com.google.common.collect.Maps;
import com.google.common.collect.Streams;

import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A factory for creating and accessing ordinal nodes
 */
public class OrdinalNodeAccess {
    TreeMap<Integer, Node> destroyerLocationNodes = Maps.newTreeMap();
    TreeMap<Integer, Node> producerLocationNodes = Maps.newTreeMap();

    Node getOrCreateDestroyableLocationNode(int ordinal) {
        return destroyerLocationNodes.computeIfAbsent(ordinal, this::createDestroyerLocationNode);
    }

    Node getOrCreateOutputLocationNode(int ordinal) {
        return producerLocationNodes.computeIfAbsent(ordinal, this::createProducerLocationNode);
    }

    Collection<Node> getPrecedingDestroyerLocationNodes(int from) {
        return destroyerLocationNodes.headMap(from).values();
    }

    Collection<Node> getPrecedingProducerLocationNodes(int from) {
        return producerLocationNodes.headMap(from).values();
    }

    List<Node> getAllNodes() {
        return Streams.concat(destroyerLocationNodes.values().stream(), producerLocationNodes.values().stream()).collect(Collectors.toList());
    }

    /**
     * Create relationships between the ordinal nodes such that destroyer ordinals cannot complete until all preceding producer
     * ordinals have completed (and vice versa).  This ensures that an ordinal does not complete early simply because the nodes in
     * the ordinal group it represents have no explicit dependencies.
     */
    void createInterNodeRelationships() {
        destroyerLocationNodes.forEach((ordinal, destroyer) -> getPrecedingProducerLocationNodes(ordinal).forEach(destroyer::addDependencySuccessor));
        producerLocationNodes.forEach((ordinal, producer) -> getPrecedingDestroyerLocationNodes(ordinal).forEach(producer::addDependencySuccessor));
    }

    private Node createDestroyerLocationNode(int ordinal) {
        return createOrdinalNode(OrdinalNode.Type.DESTROYER, ordinal);
    }

    private Node createProducerLocationNode(int ordinal) {
        return createOrdinalNode(OrdinalNode.Type.PRODUCER, ordinal);
    }

    private Node createOrdinalNode(OrdinalNode.Type type, int ordinal) {
        Node ordinalNode = new OrdinalNode(type, ordinal);
        ordinalNode.require();
        return ordinalNode;
    }
}