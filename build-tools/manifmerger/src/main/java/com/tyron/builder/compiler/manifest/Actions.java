package com.tyron.builder.compiler.manifest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.google.common.annotations.VisibleForTesting;


import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.io.LineReader;
import com.google.errorprone.annotations.Immutable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.tyron.builder.compiler.manifest.blame.MessageJsonSerializer;
import com.tyron.builder.compiler.manifest.blame.SourceFile;
import com.tyron.builder.compiler.manifest.blame.SourceFilePosition;
import com.tyron.builder.log.ILogger;

import org.xml.sax.SAXException;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openjdk.javax.xml.parsers.ParserConfigurationException;

/**
 * Contains all actions taken during a merging invocation.
 */
@Immutable
public class Actions {

    // TODO: i18n
    @VisibleForTesting
    static final String HEADER = "-- Merging decision tree log ---\n";

    // defines all the records for the merging tool activity, indexed by element name+key.
    // iterator should be ordered by the key insertion order.
    private final Map<XmlNode.NodeKey, DecisionTreeRecord> mRecords;

    public Actions(Map<XmlNode.NodeKey, DecisionTreeRecord> records) {
        mRecords = records;
    }

    /**
     * Returns a {@link com.google.common.collect.ImmutableSet} of all the element's keys that have
     * at least one {@link NodeRecord}.
     */
    @NotNull
    public Set<XmlNode.NodeKey> getNodeKeys() {
        return mRecords.keySet();
    }

    /**
     * Returns an {@link ImmutableList} of {@link NodeRecord} for the element identified with the
     * passed key.
     */
    @NotNull
    public ImmutableList<NodeRecord> getNodeRecords(XmlNode.NodeKey key) {
        return mRecords.containsKey(key)
                ? mRecords.get(key).getNodeRecords()
                : ImmutableList.<NodeRecord>of();
    }

    /**
     * Returns a {@link ImmutableList} of all attributes names that have at least one record for
     * the element identified with the passed key.
     */
    @NotNull
    public ImmutableList<XmlNode.NodeName> getRecordedAttributeNames(XmlNode.NodeKey nodeKey) {
        DecisionTreeRecord decisionTreeRecord = mRecords.get(nodeKey);
        if (decisionTreeRecord == null) {
            return ImmutableList.of();
        }
        return decisionTreeRecord.getAttributesRecords().keySet().asList();
    }

    /**
     * Returns the {@link com.google.common.collect.ImmutableList} of {@link AttributeRecord} for
     * the attribute identified by attributeName of the element identified by elementKey.
     */
    @NotNull
    public ImmutableList<AttributeRecord> getAttributeRecords(XmlNode.NodeKey elementKey,
                                                              XmlNode.NodeName attributeName) {

        DecisionTreeRecord decisionTreeRecord = mRecords.get(elementKey);
        if (decisionTreeRecord == null) {
            return ImmutableList.of();
        }
        return decisionTreeRecord.getAttributeRecords(attributeName);
    }

    /**
     * Initial dump of the merging tool actions, need to be refined and spec'ed out properly.
     * @param logger logger to log to at INFO level.
     */
    void log(ILogger logger) {
        logger.debug(getLogs());
    }

    /**
     * Dump merging tool actions to a text file.
     * @param fileWriter the file to write all actions into.
     * @throws IOException
     */
    void log(FileWriter fileWriter) throws IOException {
        fileWriter.append(getLogs());
    }

    private String getLogs() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(HEADER);
        for (Map.Entry<XmlNode.NodeKey, Actions.DecisionTreeRecord> record : mRecords.entrySet()) {
            stringBuilder.append(record.getKey()).append("\n");
            for (Actions.NodeRecord nodeRecord : record.getValue().getNodeRecords()) {
                nodeRecord.print(stringBuilder);
                stringBuilder.append('\n');
            }
            for (Map.Entry<XmlNode.NodeName, List<Actions.AttributeRecord>> attributeRecords :
                    record.getValue().mAttributeRecords.entrySet()) {
                stringBuilder.append('\t').append(attributeRecords.getKey()).append('\n');
                for (Actions.AttributeRecord attributeRecord : attributeRecords.getValue()) {
                    stringBuilder.append("\t\t");
                    attributeRecord.print(stringBuilder);
                    stringBuilder.append('\n');
                }
            }
        }
        return stringBuilder.toString();
    }

    /**
     * Defines all possible actions taken from the merging tool for an xml element or attribute.
     */
    enum ActionType {
        /**
         * The element was added into the resulting merged manifest.
         */
        ADDED,
        /**
         * The element was injected from the merger invocation parameters.
         */
        INJECTED,
        /**
         * The element was merged with another element into the resulting merged manifest.
         */
        MERGED,
        /**
         * The element was rejected.
         */
        REJECTED,
        /**
         * The implied element was added was added when importing a library that expected the
         * element to be present by default while targeted SDK requires its declaration.
         */
        IMPLIED,
    }

    /**
     * Defines an abstract record contain common metadata for elements and attributes actions.
     */
    public abstract static class Record {

        @NotNull protected final ActionType mActionType;
        @NotNull protected final SourceFilePosition mActionLocation;
        @NotNull protected final XmlNode.NodeKey mTargetId;
        @Nullable
        protected final String mReason;

        private Record(@NotNull ActionType actionType,
                       @NotNull SourceFilePosition actionLocation,
                       @NotNull XmlNode.NodeKey targetId,
                       @Nullable String reason) {
            mActionType = Preconditions.checkNotNull(actionType);
            mActionLocation = Preconditions.checkNotNull(actionLocation);
            mTargetId = Preconditions.checkNotNull(targetId);
            mReason = reason;
        }

        public ActionType getActionType() {
            return mActionType;
        }

        public SourceFilePosition getActionLocation() {
            return mActionLocation;
        }

        public XmlNode.NodeKey getTargetId() {
            return mTargetId;
        }

        public void print(StringBuilder stringBuilder) {
            stringBuilder.append(mActionType)
                    .append(" from ")
                    .append(mActionLocation);
            if (mReason != null) {
                stringBuilder.append(" reason: ")
                        .append(mReason);
            }
        }
    }

    /**
     * Defines a merging tool action for an xml element.
     */
    public static class NodeRecord extends Record {

        private final NodeOperationType mNodeOperationType;

        NodeRecord(@NotNull ActionType actionType,
                   @NotNull SourceFilePosition actionLocation,
                   @NotNull XmlNode.NodeKey targetId,
                   @Nullable String reason,
                   @NotNull NodeOperationType nodeOperationType) {
            super(actionType, actionLocation, targetId, reason);
            this.mNodeOperationType = Preconditions.checkNotNull(nodeOperationType);
        }

        @Override
        public String toString() {
            return "Id=" + mTargetId.toString() + " actionType=" + getActionType()
                    + " location=" + getActionLocation()
                    + " opType=" + mNodeOperationType;
        }
    }

    /**
     * Defines a merging tool action for an xml attribute
     */
    public static class AttributeRecord extends Record {

        // first in wins which should be fine, the first
        // operation type will be the highest priority one
        private final AttributeOperationType mOperationType;

        AttributeRecord(
                @NotNull ActionType actionType,
                @NotNull SourceFilePosition actionLocation,
                @NotNull XmlNode.NodeKey targetId,
                @Nullable String reason,
                @Nullable AttributeOperationType operationType) {
            super(actionType, actionLocation, targetId, reason);
            this.mOperationType = operationType;
        }

        @Nullable
        public AttributeOperationType getOperationType() {
            return mOperationType;
        }

        @Override
        public String toString() {
            return "Id=" + mTargetId + " actionType=" + getActionType()
                    + " location=" + getActionLocation()
                    + " opType=" + getOperationType();
        }
    }

    public String persist() throws IOException  {
        GsonBuilder gson = new GsonBuilder().setPrettyPrinting();
        gson.enableComplexMapKeySerialization();
        MessageJsonSerializer.registerTypeAdapters(gson);
        return gson.create().toJson(this);
    }

    @Nullable
    public static Actions load(InputStream inputStream) throws IOException {

        return getGsonParser().fromJson(new InputStreamReader(inputStream), Actions.class);
    }

    private static class NodeNameDeserializer implements JsonDeserializer<XmlNode.NodeName> {

        @Override
        public XmlNode.NodeName deserialize(JsonElement json, Type typeOfT,
                                            JsonDeserializationContext context) throws JsonParseException {
            if (json.getAsJsonObject().get("mNamespaceURI") != null) {
                return context.deserialize(json, XmlNode.NamespaceAwareName.class);
            } else {
                return context.deserialize(json, XmlNode.Name.class);
            }
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static Actions load(String xml) {

        return getGsonParser().fromJson(xml, Actions.class);
    }

    private static Gson getGsonParser() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.enableComplexMapKeySerialization();
        gsonBuilder.registerTypeAdapter(XmlNode.NodeName.class, new NodeNameDeserializer());
        MessageJsonSerializer.registerTypeAdapters(gsonBuilder);
        return gsonBuilder.create();
    }

    public ImmutableMultimap<Integer, Record> getResultingSourceMapping(XmlDocument xmlDocument)
            throws ParserConfigurationException, SAXException, IOException {

        SourceFile inMemory = SourceFile.UNKNOWN;

        XmlDocument loadedWithLineNumbers = XmlLoader.load(
                xmlDocument.getSelectors(),
                xmlDocument.getSystemPropertyResolver(),
                inMemory,
                xmlDocument.prettyPrint(),
                XmlDocument.Type.MAIN,
                Optional.<String>absent() /* mainManifestPackageName */);

        ImmutableMultimap.Builder<Integer, Record> mappingBuilder = ImmutableMultimap.builder();
        for (XmlElement xmlElement : loadedWithLineNumbers.getRootNode().getMergeableElements()) {
            parse(xmlElement, mappingBuilder);
        }
        return mappingBuilder.build();
    }

    private void parse(XmlElement element,
                       ImmutableMultimap.Builder<Integer, Record> mappings) {
        DecisionTreeRecord decisionTreeRecord = mRecords.get(element.getId());
        if (decisionTreeRecord != null) {
            Actions.NodeRecord nodeRecord = findNodeRecord(decisionTreeRecord);
            if (nodeRecord != null) {
                mappings.put(element.getPosition().getStartLine(), nodeRecord);
            }
            for (XmlAttribute xmlAttribute : element.getAttributes()) {
                Actions.AttributeRecord attributeRecord = findAttributeRecord(decisionTreeRecord,
                        xmlAttribute);
                if (attributeRecord != null) {
                    mappings.put(xmlAttribute.getPosition().getStartLine(), attributeRecord);
                }
            }
        }
        for (XmlElement xmlElement : element.getMergeableElements()) {
            parse(xmlElement, mappings);
        }
    }

    public String blame(XmlDocument xmlDocument)
            throws IOException, SAXException, ParserConfigurationException {

        ImmutableMultimap<Integer, Record> resultingSourceMapping =
                getResultingSourceMapping(xmlDocument);
        LineReader lineReader = new LineReader(
                new StringReader(xmlDocument.prettyPrint()));

        StringBuilder actualMappings = new StringBuilder();
        String line;
        int count = 0;
        while ((line = lineReader.readLine()) != null) {
            actualMappings.append(count + 1).append(line).append("\n");
            if (resultingSourceMapping.containsKey(count)) {
                for (Record record : resultingSourceMapping.get(count)) {
                    actualMappings.append(count + 1).append("-->")
                            .append(record.getActionLocation().toString())
                            .append("\n");
                }
            }
            count++;
        }
        return actualMappings.toString();
    }

    @Nullable
    private static Actions.NodeRecord findNodeRecord(DecisionTreeRecord decisionTreeRecord) {
        for (Actions.NodeRecord nodeRecord : decisionTreeRecord.getNodeRecords()) {
            if (nodeRecord.getActionType() == Actions.ActionType.ADDED) {
                return nodeRecord;
            }
        }
        return null;
    }

    @Nullable
    private static Actions.AttributeRecord findAttributeRecord(
            DecisionTreeRecord decisionTreeRecord,
            XmlAttribute xmlAttribute) {
        for (Actions.AttributeRecord attributeRecord : decisionTreeRecord
                .getAttributeRecords(xmlAttribute.getName())) {
            if (attributeRecord.getActionType() == Actions.ActionType.ADDED) {
                return attributeRecord;
            }
        }
        return null;
    }

    /**
     * Internal structure on how {@link Actions.Record}s are kept for an
     * xml element.
     *
     * Each xml element should have an associated DecisionTreeRecord which keeps a list of
     * {@link Actions.NodeRecord} for all the node actions related
     * to this xml element.
     *
     * It will also contain a map indexed by attribute name on all the attribute actions related
     * to that particular attribute within the xml element.
     *
     */
    static class DecisionTreeRecord {
        // all other occurrences of the nodes decisions, in order of decisions.
        private final List<NodeRecord> mNodeRecords = new ArrayList<NodeRecord>();

        // all attributes decisions indexed by attribute name.
        final Map<XmlNode.NodeName, List<AttributeRecord>> mAttributeRecords =
                new HashMap<XmlNode.NodeName, List<AttributeRecord>>();

        ImmutableList<NodeRecord> getNodeRecords() {
            return ImmutableList.copyOf(mNodeRecords);
        }

        ImmutableMap<XmlNode.NodeName, List<AttributeRecord>> getAttributesRecords() {
            return ImmutableMap.copyOf(mAttributeRecords);
        }

        DecisionTreeRecord() {
        }

        void addNodeRecord(NodeRecord nodeRecord) {
            mNodeRecords.add(nodeRecord);
        }

        ImmutableList<AttributeRecord> getAttributeRecords(XmlNode.NodeName attributeName) {
            List<AttributeRecord> attributeRecords = mAttributeRecords.get(attributeName);
            return attributeRecords == null
                    ? ImmutableList.<AttributeRecord>of()
                    : ImmutableList.copyOf(attributeRecords);
        }
    }
}
