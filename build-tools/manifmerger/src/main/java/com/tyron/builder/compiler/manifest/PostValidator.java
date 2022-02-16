package com.tyron.builder.compiler.manifest;

import static com.tyron.builder.compiler.manifest.Actions.ActionType;

import org.jetbrains.annotations.NotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.tyron.builder.util.XmlUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Validator that runs post merging activities and verifies that all "tools:" instructions
 * triggered an action by the merging tool.
 * <p>
 *
 * This is primarily to catch situations like a user entered a tools:remove="foo" directory on one
 * of its elements and that particular attribute was never removed during the merges possibly
 * indicating an unforeseen change of configuration.
 * <p>
 *
 * Most of the output from this validation should be warnings.
 */
public class PostValidator {

    /**
     * Post validation of the merged document. This will essentially check that all merging
     * instructions were applied at least once.
     *
     * @param xmlDocument merged document to check.
     * @param mergingReport report for errors and warnings.
     */
    public static void validate(
            @NotNull XmlDocument xmlDocument,
            @NotNull MergingReport.Builder mergingReport) {

        Preconditions.checkNotNull(xmlDocument);
        Preconditions.checkNotNull(mergingReport);
        enforceAndroidNamespaceDeclaration(xmlDocument);
        reOrderElements(xmlDocument.getRootNode());
        validate(
                xmlDocument.getRootNode(),
                mergingReport.getActionRecorder().build(),
                mergingReport);
        checkOnlyOneUsesSdk(xmlDocument, mergingReport);
    }

    /**
     * Enforces {@link SdkConstants#ANDROID_URI} declaration in the top level element. It is
     * possible that the original manifest file did not contain any attribute declaration, therefore
     * not requiring a xmlns: declaration. Yet the implicit elements handling may have added
     * attributes requiring the namespace declaration.
     */
    private static void enforceAndroidNamespaceDeclaration(@NotNull XmlDocument xmlDocument) {
        final Element rootElement = xmlDocument.getRootNode().getXml();
        XmlUtils.lookupNamespacePrefix(
                rootElement, SdkConstants.ANDROID_URI, SdkConstants.ANDROID_NS_NAME, true);
    }

    /**
     * Enforces {@link SdkConstants#TOOLS_URI} declaration in the top level element, if necessary.
     * It is possible that the original manifest file did not contain any attribute declaration,
     * therefore not requiring a xmlns: declaration. Yet the implicit elements handling may have
     * added attributes requiring the namespace declaration.
     */
    protected static void enforceToolsNamespaceDeclaration(@NotNull XmlDocument xmlDocument) {
        final Element rootElement = xmlDocument.getRootNode().getXml();
        if (SdkConstants.TOOLS_PREFIX.equals(
                XmlUtils.lookupNamespacePrefix(rootElement, SdkConstants.TOOLS_URI, null, false))) {
            return;
        }
        // if we are here, we did not find the namespace declaration, so we add it if
        // tools namespace is used anywhere in the xml document
        if (elementUsesNamespacePrefix(rootElement, SdkConstants.TOOLS_NS_NAME)) {
            XmlUtils.lookupNamespacePrefix(
                    rootElement, SdkConstants.TOOLS_URI, SdkConstants.TOOLS_NS_NAME, true);
        }
    }

    /**
     * Check whether element or any of its descendants have an attribute with the given namespace
     *
     * @param element the element under consideration
     * @param prefix the namespace prefix under consideration
     * @return true if element or any of its descendants have an attribute with the given namespace,
     *     false otherwise.
     */
    @VisibleForTesting
    static boolean elementUsesNamespacePrefix(@NotNull Element element, @NotNull String prefix) {
        NamedNodeMap namedNodeMap = element.getAttributes();
        for (int i = 0; i < namedNodeMap.getLength(); i++) {
            Node attribute = namedNodeMap.item(i);
            if (prefix.equals(attribute.getPrefix())) {
                return true;
            }
        }
        NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node childNode = childNodes.item(i);
            if (childNode instanceof Element) {
                if (elementUsesNamespacePrefix((Element) childNode, prefix)) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Reorder child elements :
     * <ul>
     *     <li>&lt;activity-alias&gt; elements within &lt;application&gt; are moved after the
     *     &lt;activity&gt; they target.</li>
     *     <li>&lt;application&gt; is moved last in the list of children
     *     of the <manifest> element.</li>
     *     <li>uses-sdk is moved first in the list of children of the &lt;manifest&gt; element</li>
     * </ul>
     * @param xmlElement the root element of the manifest document.
     */
    private static void reOrderElements(@NotNull XmlElement xmlElement) {

        reOrderActivityAlias(xmlElement);
        reOrderApplication(xmlElement);
        reOrderUsesSdk(xmlElement);
    }

    /**
     * Reorder activity-alias elements to after the activity they reference
     *
     * @param xmlElement the root element of the manifest document.
     */
    private static void reOrderActivityAlias(@NotNull XmlElement xmlElement) {

        // look up application element.
        Optional<XmlElement> element = xmlElement
                .getNodeByTypeAndKey(ManifestModel.NodeTypes.APPLICATION, null);
        if (!element.isPresent()) {
            return;
        }
        XmlElement applicationElement = element.get();

        List<XmlElement> activityAliasElements = applicationElement
                .getAllNodesByType(ManifestModel.NodeTypes.ACTIVITY_ALIAS);
        for (XmlElement activityAlias : activityAliasElements) {
            // get targetActivity attribute
            Optional<XmlAttribute> attribute = activityAlias.getAttribute(
                    XmlNode.fromNSName(SdkConstants.ANDROID_URI, "android", "targetActivity"));
            if (!attribute.isPresent()) {
                continue;
            }
            String targetActivity = attribute.get().getValue();

            // look up target activity element
            element = applicationElement
                    .getNodeByTypeAndKey(ManifestModel.NodeTypes.ACTIVITY, targetActivity);
            if (!element.isPresent()) {
                continue;
            }
            XmlElement activity = element.get();

            // move the activity-alias to after the activity
            Node nextSibling = activity.getXml().getNextSibling();

            // move the activity-alias's comments if any.
            List<Node> comments = XmlElement.getLeadingComments(activityAlias.getXml());

            if (!comments.isEmpty() && !comments.get(0).equals(nextSibling)) {
                for (Node comment : comments) {
                    applicationElement.getXml().removeChild(comment);
                    applicationElement.getXml().insertBefore(comment, nextSibling);
                }
            }

            // move the activity-alias element if neither it or its comments immediately follow the
            // target activity.
            if (!activityAlias.getXml().equals(nextSibling)
                    && !(!comments.isEmpty() && comments.get(0).equals(nextSibling))) {
                applicationElement.getXml().removeChild(activityAlias.getXml());
                applicationElement.getXml().insertBefore(activityAlias.getXml(), nextSibling);
            }
        }
    }

    /**
     * Reorder application element
     *
     * @param xmlElement the root element of the manifest document.
     */
    private static void reOrderApplication(@NotNull XmlElement xmlElement) {

        // look up application element.
        Optional<XmlElement> element = xmlElement
                .getNodeByTypeAndKey(ManifestModel.NodeTypes.APPLICATION, null);
        if (!element.isPresent()) {
            return;
        }
        XmlElement applicationElement = element.get();

        List<Node> comments = XmlElement.getLeadingComments(applicationElement.getXml());

        // move the application's comments if any.
        for (Node comment : comments) {
            xmlElement.getXml().removeChild(comment);
            xmlElement.getXml().appendChild(comment);
        }
        // remove the application element and add it back, it will be automatically placed last.
        xmlElement.getXml().removeChild(applicationElement.getXml());
        xmlElement.getXml().appendChild(applicationElement.getXml());
    }

    /**
     * Reorder uses-sdk element
     *
     * @param xmlElement the root element of the manifest document.
     */
    private static void reOrderUsesSdk(@NotNull XmlElement xmlElement) {

        // look up uses-sdk element.
        Optional<XmlElement> element = xmlElement
                .getNodeByTypeAndKey(ManifestModel.NodeTypes.USES_SDK, null);
        if (!element.isPresent()) {
            return;
        }

        XmlElement usesSdk = element.get();
        Node firstChild = xmlElement.getXml().getFirstChild();
        // already the first element ?
        if (firstChild == usesSdk.getXml()) {
            return;
        }

        List<Node> comments = XmlElement.getLeadingComments(usesSdk.getXml());

        // move the application's comments if any.
        for (Node comment : comments) {
            xmlElement.getXml().removeChild(comment);
            xmlElement.getXml().insertBefore(comment, firstChild);
        }
        // remove the application element and add it back, it will be automatically placed last.
        xmlElement.getXml().removeChild(usesSdk.getXml());
        xmlElement.getXml().insertBefore(usesSdk.getXml(), firstChild);
    }

    /**
     * Validate an xml element and recursively its children elements, ensuring that all merging
     * instructions were applied.
     *
     * @param xmlElement xml element to validate.
     * @param actions the actions recorded during the merging activities.
     * @param mergingReport report for errors and warnings.
     * instructions were applied once or {@link MergingReport.Result#WARNING} otherwise.
     */
    private static void validate(
            @NotNull XmlElement xmlElement,
            @NotNull Actions actions,
            @NotNull MergingReport.Builder mergingReport) {

        NodeOperationType operationType = xmlElement.getOperationType();
        boolean ignoreWarning = checkIgnoreWarning(xmlElement);
        switch (operationType) {
            case REPLACE:
                // we should find at least one rejected twin.
                if (!ignoreWarning
                        && !isNodeOperationPresent(xmlElement, actions, ActionType.REJECTED)) {
                    mergingReport.addMessage(
                            xmlElement,
                            MergingReport.Record.Severity.WARNING,
                            String.format(
                                    "%1$s was tagged at %2$s:%3$d to replace another declaration "
                                            + "but no other declaration present",
                                    xmlElement.getId(),
                                    xmlElement.getDocument().getSourceFile().print(true),
                                    xmlElement.getPosition().getStartLine() + 1));
                }
                break;
            case REMOVE:
            case REMOVE_ALL:
                // we should find at least one rejected twin.
                if (!ignoreWarning
                        && !isNodeOperationPresent(xmlElement, actions, ActionType.REJECTED)) {
                    mergingReport.addMessage(
                            xmlElement,
                            MergingReport.Record.Severity.WARNING,
                            String.format(
                                    "%1$s was tagged at %2$s:%3$d to remove other declarations "
                                            + "but no other declaration present",
                                    xmlElement.getId(),
                                    xmlElement.getDocument().getSourceFile().print(true),
                                    xmlElement.getPosition().getStartLine() + 1));
                }
                break;
        }
        validateAttributes(xmlElement, actions, mergingReport, ignoreWarning);
        validateAndroidAttributes(xmlElement, mergingReport);
        for (XmlElement child : xmlElement.getMergeableElements()) {
            validate(child, actions, mergingReport);
        }
    }

    /** Verifies that all merging attributes on a passed xml element were applied. */
    private static void validateAttributes(
            @NotNull XmlElement xmlElement,
            @NotNull Actions actions,
            @NotNull MergingReport.Builder mergingReport,
            boolean ignoreWarning) {

        @NotNull Collection<Map.Entry<XmlNode.NodeName, AttributeOperationType>> attributeOperations
                = xmlElement.getAttributeOperations();
        for (Map.Entry<XmlNode.NodeName, AttributeOperationType> attributeOperation :
                attributeOperations) {
            switch (attributeOperation.getValue()) {
                case REMOVE:
                    if (!ignoreWarning
                            && !isAttributeOperationPresent(
                            xmlElement, attributeOperation, actions, ActionType.REJECTED)) {
                        mergingReport.addMessage(
                                xmlElement,
                                MergingReport.Record.Severity.WARNING,
                                String.format(
                                        "%1$s@%2$s was tagged at %3$s:%4$d to remove other"
                                                + " declarations but no other declaration present",
                                        xmlElement.getId(),
                                        attributeOperation.getKey(),
                                        xmlElement.getDocument().getSourceFile().print(true),
                                        xmlElement.getPosition().getStartLine() + 1));
                    }
                    break;
                case REPLACE:
                    if (!ignoreWarning
                            && !isAttributeOperationPresent(
                            xmlElement, attributeOperation, actions, ActionType.REJECTED)) {
                        mergingReport.addMessage(
                                xmlElement,
                                MergingReport.Record.Severity.WARNING,
                                String.format(
                                        "%1$s@%2$s was tagged at %3$s:%4$d to replace other"
                                                + " declarations but no other declaration present",
                                        xmlElement.getId(),
                                        attributeOperation.getKey(),
                                        xmlElement.getDocument().getSourceFile().print(true),
                                        xmlElement.getPosition().getStartLine() + 1));
                    }
                    break;
            }
        }

    }

    /**
     * Check in our list of applied actions that a particular {@link ActionType} action was recorded
     * on the passed element.
     *
     * @return true if it was applied, false otherwise.
     */
    private static boolean isNodeOperationPresent(
            @NotNull XmlElement xmlElement, @NotNull Actions actions, ActionType action) {

        for (Actions.NodeRecord nodeRecord : actions.getNodeRecords(xmlElement.getId())) {
            if (nodeRecord.getActionType() == action) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check in our list of attribute actions that a particular {@link ActionType} action was
     * recorded on the passed element.
     *
     * @return true if it was applied, false otherwise.
     */
    private static boolean isAttributeOperationPresent(
            @NotNull XmlElement xmlElement,
            @NotNull Map.Entry<XmlNode.NodeName, AttributeOperationType> attributeOperation,
            @NotNull Actions actions,
            ActionType action) {

        for (Actions.AttributeRecord attributeRecord : actions.getAttributeRecords(
                xmlElement.getId(), attributeOperation.getKey())) {
            if (attributeRecord.getActionType() == action) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates all {@link XmlElement} attributes belonging to the {@link SdkConstants#ANDROID_URI}
     * namespace.
     *
     * @param xmlElement xml element to check the attributes from.
     * @param mergingReport report for errors and warnings.
     */
    private static void validateAndroidAttributes(
            @NotNull XmlElement xmlElement, @NotNull MergingReport.Builder mergingReport) {

        for (XmlAttribute xmlAttribute : xmlElement.getAttributes()) {
            if (xmlAttribute.getModel() != null) {
                AttributeModel.Validator onWriteValidator = xmlAttribute.getModel()
                        .getOnWriteValidator();
                if (onWriteValidator != null) {
                    onWriteValidator.validates(
                            mergingReport, xmlAttribute, xmlAttribute.getValue());
                }
            }
        }
    }
    /**
     * check if the tools:ignore_warning is set
     *
     * @param xmlElement the current XmlElement
     * @return whether the ignoreWarning flag is set
     */
    @VisibleForTesting
    static boolean checkIgnoreWarning(@NotNull XmlElement xmlElement) {
        @NotNull
        Collection<Map.Entry<XmlNode.NodeName, AttributeOperationType>> attributeOperations =
                xmlElement.getAttributeOperations();
        for (Map.Entry<XmlNode.NodeName, AttributeOperationType> attributeOperation :
                attributeOperations) {
            if (attributeOperation.getValue() == AttributeOperationType.IGNORE_WARNING) {
                if (attributeOperation.getKey().toString().equals("tools:true")) {
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private static void checkOnlyOneUsesSdk(
            @NotNull XmlDocument manifest, @NotNull MergingReport.Builder mergingReport) {
        XmlElement root = manifest.getRootNode();
        Preconditions.checkNotNull(root);
        List<XmlElement> list = root.getAllNodesByType(ManifestModel.NodeTypes.USES_SDK);
        if (list.size() > 1) {
            mergingReport.addMessage(
                    manifest.getSourceFile(),
                    MergingReport.Record.Severity.ERROR,
                    "Multiple <uses-sdk>s cannot be present in the merged AndroidManifest.xml. ");
            mergingReport.build();
        }
    }
}

