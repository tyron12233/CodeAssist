/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.viewbinding.tool.store;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.base.Strings;
import com.tyron.viewbinding.parser.XMLLexer;
import com.tyron.viewbinding.parser.XMLParser;
import com.tyron.viewbinding.parser.XMLParserBaseVisitor;
import com.tyron.viewbinding.tool.processing.ErrorMessages;
import com.tyron.viewbinding.tool.processing.Scope;
import com.tyron.viewbinding.tool.processing.scopes.FileScopeProvider;
import com.tyron.viewbinding.tool.store.ResourceBundle.LayoutFileBundle;
import com.tyron.viewbinding.tool.util.L;
import com.tyron.viewbinding.tool.util.ParserHelper;
import com.tyron.viewbinding.tool.util.Preconditions;
import com.tyron.viewbinding.tool.util.RelativizableFile;
import com.tyron.viewbinding.tool.util.StringUtils;
import com.tyron.viewbinding.tool.util.XmlEditor;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gets the list of XML files and creates a list of
 * {@link com.tyron.viewbinding.tool.store.ResourceBundle} that can be persistent or converted to
 * LayoutBinder.
 *
 * CodeAssist note: Commented out/force-disabled Data Binding logic.
 * Also re-wrote [parseXml] and [parseOriginalXml] to allow for parsing directly
 * from an XML string.
 */
public final class LayoutFileParser {

    // private static final String XPATH_BINDING_LAYOUT = "/layout";

    private static final String LAYOUT_PREFIX = "@layout/";

    @Nullable
    public static LayoutFileBundle parseXml(@NonNull final RelativizableFile input,
                                            @NonNull final String pkg,
                                            @Nullable final String xmlContent,
                                            boolean isViewBindingEnabled) throws IOException {
        File inputFile = input.getAbsoluteFile();

        try {
            Scope.enter((FileScopeProvider) inputFile::getAbsolutePath);
            final String encoding = findEncoding(inputFile);

            // stripFile(inputFile, outputFile, encoding, originalFileLookup);
            return parseOriginalXml(
                    RelativizableFile.fromAbsoluteFile(inputFile, input.getBaseDir()), pkg,
                    encoding, isViewBindingEnabled, xmlContent);
        } finally {
            Scope.exit();
        }
    }

/*
    public static boolean stripSingleLayoutFile(File layoutFile, File outputFile)
            throws IOException {
        String encoding = findEncoding(layoutFile);
        String noExt = ParserHelper.stripExtension(layoutFile.getName());
        String binderId = layoutFile.getParentFile().getName() + '/' + noExt;
        String res = XmlEditor.strip(layoutFile, binderId, encoding);
        if (res != null) {
            FileUtils.writeStringToFile(outputFile, res, encoding);
            return true;
        }
        return false;
    }
*/
    private static final boolean DATA_BINDING_IMPLEMENTED = false;
    public static final String DATA_BINDING_NOT_IMPLEMENTED_MESSAGE = "Data Binding is not supported";

    private static Reader getReader(@Nullable final String optionalFileContents,
                                    @NonNull File original,
                                    @NonNull final String encoding)
            throws IOException {
        if (optionalFileContents != null) {
            return new StringReader(optionalFileContents);
        } else {
            FileInputStream fin = new FileInputStream(original);
            return new InputStreamReader(fin, encoding);
        }
    }

    private static LayoutFileBundle parseOriginalXml(
            @NonNull final RelativizableFile originalFile, @NonNull final String pkg,
            @NonNull final String encoding, boolean isViewBindingEnabled,
            @Nullable final String optionalFileContents) // CodeAssist added
            throws IOException {
        File original = originalFile.getAbsoluteFile();
        try {
            Scope.enter((FileScopeProvider) original::getAbsolutePath);
            final String xmlNoExtension = ParserHelper.stripExtension(original.getName());
            Reader reader = getReader(optionalFileContents, original, encoding);
            ANTLRInputStream inputStream = new ANTLRInputStream(reader);
            XMLLexer lexer = new XMLLexer(inputStream);
            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            XMLParser parser = new XMLParser(tokenStream);
            XMLParser.DocumentContext expr = parser.document();
            XMLParser.ElementContext root = expr.element();
            boolean isBindingData = "layout".equals(root.elmName.getText());

            // XMLParser.ElementContext data;
            XMLParser.ElementContext rootView;
            if (isBindingData) {
                if (!DATA_BINDING_IMPLEMENTED) {
                    L.e(DATA_BINDING_NOT_IMPLEMENTED_MESSAGE);
                    return null;
                }

                /*if (!isDataBindingEnabled) {
                    L.e(ErrorMessages.FOUND_LAYOUT_BUT_NOT_ENABLED);
                    return null;
                }
                data = getDataNode(root);
                rootView = getViewNode(original, root);*/
            } else if (isViewBindingEnabled) {
                if ("true".equalsIgnoreCase(attributeMap(root).get("tools:viewBindingIgnore"))) {
                    L.d("Ignoring %s for view binding", originalFile);
                    return null;
                }
                // data = null;
                rootView = root;
            } else {
                return null;
            }

            boolean isMerge = "merge".equals(rootView.elmName.getText());
            if (isBindingData && isMerge && !filter(rootView, "include").isEmpty()) {
                L.e(ErrorMessages.INCLUDE_INSIDE_MERGE);
                return null;
            }

            String rootViewType = getViewName(rootView);
            String rootViewId = attributeMap(rootView).get("android:id");
            LayoutFileBundle bundle =
                new LayoutFileBundle(
                    originalFile, xmlNoExtension, original.getParentFile().getName(), pkg,
                    isMerge, isBindingData, rootViewType, rootViewId);

            final String newTag = original.getParentFile().getName() + '/' + xmlNoExtension;
           // parseData(original, data, bundle);
            parseExpressions(newTag, rootView, isMerge, bundle);

            return bundle;
        } finally {
            Scope.exit();
        }
    }

    private static boolean isProcessedElement(String name) {
        if (Strings.isNullOrEmpty(name)) {
            return false;
        }
        if ("view".equals(name) || "include".equals(name) || name.indexOf('.') >= 0) {
            return true;
        }
        return !name.toLowerCase().equals(name);
    }

    private static void parseExpressions(String newTag, final XMLParser.ElementContext rootView,
            final boolean isMerge, LayoutFileBundle bundle) {
        final List<XMLParser.ElementContext> bindingElements = new ArrayList<>();
        final List<XMLParser.ElementContext> otherElementsWithIds = new ArrayList<>();
        rootView.accept(new XMLParserBaseVisitor<Void>() {
            @Override
            public Void visitElement(@NonNull XMLParser.ElementContext ctx) {
                if (filter(ctx)) {
                    bindingElements.add(ctx);
                } else {
                    String name = ctx.elmName.getText();
                    if (isProcessedElement(name) &&
                            attributeMap(ctx).containsKey("android:id")) {
                        otherElementsWithIds.add(ctx);
                    }
                }
                visitChildren(ctx);
                return null;
            }

            private boolean filter(XMLParser.ElementContext ctx) {
                if (isMerge) {
                    // account for XMLParser.ContentContext
                    if (ctx.getParent().getParent() == rootView) {
                        return true;
                    }
                } else if (ctx == rootView) {
                    return true;
                }
                return hasIncludeChild(ctx) || XmlEditor.hasExpressionAttributes(ctx) ||
                        "include".equals(ctx.elmName.getText());
            }

            private boolean hasIncludeChild(XMLParser.ElementContext ctx) {
                for (XMLParser.ElementContext child : XmlEditor.elements(ctx)) {
                    if ("include".equals(child.elmName.getText())) {
                        return true;
                    }
                }
                return false;
            }
        });

        final HashMap<XMLParser.ElementContext, String> nodeTagMap = new HashMap<>();
        L.d("number of binding nodes %d", bindingElements.size());
        int tagNumber = 0;
        for (XMLParser.ElementContext parent : bindingElements) {
            final Map<String, String> attributes = attributeMap(parent);
            String nodeName = parent.elmName.getText();
            String viewName = null;
            String includedLayoutName = null;
            final String id = attributes.get("android:id");
            final String tag;
            final String originalTag = attributes.get("android:tag");
            if ("include".equals(nodeName)) {
                // get the layout attribute
                final String includeValue = attributes.get("layout");
                if (Strings.isNullOrEmpty(includeValue)) {
                    L.e("%s must include a layout", parent);
                }
                if (!includeValue.startsWith(LAYOUT_PREFIX)) {
                    L.e("included value (%s) must start with %s.",
                            includeValue, LAYOUT_PREFIX);
                }
                // if user is binding something there, there MUST be a layout file to be
                // generated.
                includedLayoutName = includeValue.substring(LAYOUT_PREFIX.length());
                final ParserRuleContext myParentContent = parent.getParent();
                Preconditions.check(myParentContent instanceof XMLParser.ContentContext,
                        "parent of an include tag must be a content context but it is %s",
                        myParentContent.getClass().getCanonicalName());
                final ParserRuleContext grandParent = myParentContent.getParent();
                Preconditions.check(grandParent instanceof XMLParser.ElementContext,
                        "grandparent of an include tag must be an element context but it is %s",
                        grandParent.getClass().getCanonicalName());
                //noinspection SuspiciousMethodCalls
                tag = nodeTagMap.get(grandParent);
            } else if ("fragment".equals(nodeName)) {
                if (XmlEditor.hasExpressionAttributes(parent)) {
                    L.e("fragments do not support data binding expressions.");
                }
                continue;
            } else {
                viewName = getViewName(parent);
                // account for XMLParser.ContentContext
                if (rootView == parent || (isMerge && parent.getParent().getParent() == rootView)) {
                    tag = newTag + "_" + tagNumber;
                } else {
                    tag = "binding_" + tagNumber;
                }
                tagNumber++;
            }
            final ResourceBundle.BindingTargetBundle bindingTargetBundle =
                    bundle.createBindingTarget(id, viewName, true, tag, originalTag,
                            new Location(parent));
            nodeTagMap.put(parent, tag);
            bindingTargetBundle.setIncludedLayout(includedLayoutName);

            for (XMLParser.AttributeContext attr : XmlEditor.expressionAttributes(parent)) {
                String value = escapeQuotes(attr.attrValue.getText(), true);
                final boolean isOneWay = value.startsWith("@{");
                final boolean isTwoWay = value.startsWith("@={");
                if (isOneWay || isTwoWay) {
                    if (value.charAt(value.length() - 1) != '}') {
                        L.e("Expecting '}' in expression '%s'", attr.attrValue.getText());
                    }
                    final int startIndex = isTwoWay ? 3 : 2;
                    final int endIndex = value.length() - 1;
                    final String strippedValue = value.substring(startIndex, endIndex);
                    Location attrLocation = new Location(attr);
                    Location valueLocation = new Location();
                    // offset to 0 based
                    valueLocation.startLine = attr.attrValue.getLine() - 1;
                    valueLocation.startOffset = attr.attrValue.getCharPositionInLine() +
                            attr.attrValue.getText().indexOf(strippedValue);
                    valueLocation.endLine = attrLocation.endLine;
                    valueLocation.endOffset = attrLocation.endOffset - 2; // account for: "}
                    bindingTargetBundle.addBinding(escapeQuotes(attr.attrName.getText(), false),
                            strippedValue, isTwoWay, attrLocation, valueLocation);
                }
            }
        }

        for (XMLParser.ElementContext elm : otherElementsWithIds) {
            final String id = attributeMap(elm).get("android:id");
            final String className = getViewName(elm);
            bundle.createBindingTarget(id, className, true, null, null, new Location(elm));
        }
    }

    private static String getViewName(XMLParser.ElementContext elm) {
        String viewName = elm.elmName.getText();
        if ("view".equals(viewName)) {
            String classNode = attributeMap(elm).get("class");
            if (Strings.isNullOrEmpty(classNode)) {
                L.e("No class attribute for 'view' node");
            }
            return classNode;
        }
        if ("include".equals(viewName) && !XmlEditor.hasExpressionAttributes(elm)) {
            return "android.view.View";
        }
        if ("fragment".equals(viewName)) {
            return "android.view.View";
        }
        return viewName;
    }
/*

    private static void parseData(File xml, XMLParser.ElementContext data,
            ResourceBundle.LayoutFileBundle bundle) {
        if (data == null) {
            return;
        }
        for (XMLParser.ElementContext imp : filter(data, "import")) {
            final Map<String, String> attrMap = attributeMap(imp);
            String type = attrMap.get("type");
            String alias = attrMap.get("alias");
            Preconditions.check(StringUtils.isNotBlank(type), "Type of an import cannot be empty."
                    + " %s in %s", imp.toStringTree(), xml);
            if (Strings.isNullOrEmpty(alias)) {
                alias = type.substring(type.lastIndexOf('.') + 1);
            }
            bundle.addImport(alias, type, new Location(imp));
        }

        for (XMLParser.ElementContext variable : filter(data, "variable")) {
            final Map<String, String> attrMap = attributeMap(variable);
            String type = attrMap.get("type");
            String name = attrMap.get("name");
            Preconditions.checkNotNull(type, "variable must have a type definition %s in %s",
                    variable.toStringTree(), xml);
            Preconditions.checkNotNull(name, "variable must have a name %s in %s",
                    variable.toStringTree(), xml);
            bundle.addVariable(name, type, new Location(variable), true);
        }
        final XMLParser.AttributeContext className = findAttribute(data, "class");
        if (className != null) {
            final String name = escapeQuotes(className.attrValue.getText(), true);
            if (StringUtils.isNotBlank(name)) {
                Location location = new Location(
                        className.attrValue.getLine() - 1,
                        className.attrValue.getCharPositionInLine() + 1,
                        className.attrValue.getLine() - 1,
                        className.attrValue.getCharPositionInLine() + name.length()
                );
                bundle.setBindingClass(name, location);
            }
        }
    }
*/

    /*private static XMLParser.ElementContext getDataNode(XMLParser.ElementContext root) {
        final List<XMLParser.ElementContext> data = filter(root, "data");
        if (data.isEmpty()) {
            return null;
        }
        Preconditions.check(data.size() == 1, "XML layout can have only 1 data tag");
        return data.get(0);
    }

    private static XMLParser.ElementContext getViewNode(File xml, XMLParser.ElementContext root) {
        final List<XMLParser.ElementContext> view = filterNot(root, "data");
        Preconditions.check(view.size() == 1, "XML layout %s must have 1 view but has %s. root"
                        + " children count %s", xml, view.size(), root.getChildCount());
        return view.get(0);
    }*/

    private static List<XMLParser.ElementContext> filter(XMLParser.ElementContext root,
            String name) {
        List<XMLParser.ElementContext> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        final XMLParser.ContentContext content = root.content();
        if (content == null) {
            return result;
        }
        for (XMLParser.ElementContext child : XmlEditor.elements(root)) {
            if (name.equals(child.elmName.getText())) {
                result.add(child);
            }
        }
        return result;
    }
/*

    private static List<XMLParser.ElementContext> filterNot(XMLParser.ElementContext root,
            String name) {
        List<XMLParser.ElementContext> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        final XMLParser.ContentContext content = root.content();
        if (content == null) {
            return result;
        }
        for (XMLParser.ElementContext child : XmlEditor.elements(root)) {
            if (!name.equals(child.elmName.getText())) {
                result.add(child);
            }
        }
        return result;
    }
*/

    /*private static void stripFile(File xml, File out, String encoding,
            LayoutXmlProcessor.OriginalFileLookup originalFileLookup)
            throws ParserConfigurationException, IOException, SAXException,
            XPathExpressionException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xml);
        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xPath = xPathFactory.newXPath();
        File actualFile = originalFileLookup == null ? null
                : originalFileLookup.getOriginalFileFor(xml);
        // TO-DO get rid of original file lookup
        if (actualFile == null) {
            actualFile = xml;
        }
        // always create id from actual file when available. Gradle may duplicate files.
        String noExt = ParserHelper.stripExtension(actualFile.getName());
        String binderId = actualFile.getParentFile().getName() + '/' + noExt;
        // now if file has any binding expressions, find and delete them
        boolean changed = isBindingLayout(doc, xPath);
        if (changed) {
            stripBindingTags(xml, out, binderId, encoding);
        } else if (!xml.equals(out)){
            FileUtils.copyFile(xml, out);
        }
    }*/

    /*private static boolean isBindingLayout(Document doc, XPath xPath)
            throws XPathExpressionException {
        return !get(doc, xPath, XPATH_BINDING_LAYOUT).isEmpty();
    }*/

    /*private static List<Node> get(Document doc, XPath xPath, String pattern)
            throws XPathExpressionException {
        final XPathExpression expr = xPath.compile(pattern);
        return toList((NodeList) expr.evaluate(doc, XPathConstants.NODESET));
    }*/

    /*private static List<Node> toList(NodeList nodeList) {
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < nodeList.getLength(); i++) {
            result.add(nodeList.item(i));
        }
        return result;
    }

    private static void stripBindingTags(File xml, File output, String newTag, String encoding)
            throws IOException {
        String res = XmlEditor.strip(xml, newTag, encoding);
        Preconditions.checkNotNull(res, "layout file should've changed %s", xml.getAbsolutePath());
        if (res != null) {
            L.d("file %s has changed, overwriting %s",
                    xml.getAbsolutePath(), output.getAbsolutePath());
            FileUtils.writeStringToFile(output, res, encoding);
        }
    }*/

    private static String findEncoding(File f) throws IOException {
        try (FileInputStream fin = new FileInputStream(f)) {
            UniversalDetector universalDetector = new UniversalDetector(null);

            byte[] buf = new byte[4096];
            int nread;
            while ((nread = fin.read(buf)) > 0 && !universalDetector.isDone()) {
                universalDetector.handleData(buf, 0, nread);
            }

            universalDetector.dataEnd();

            String encoding = universalDetector.getDetectedCharset();
            if (encoding == null) {
                encoding = "utf-8";
            }
            return encoding;
        }
    }

    private static Map<String, String> attributeMap(XMLParser.ElementContext root) {
        final Map<String, String> result = new HashMap<>();
        for (XMLParser.AttributeContext attr : XmlEditor.attributes(root)) {
            result.put(escapeQuotes(attr.attrName.getText(), false),
                    escapeQuotes(attr.attrValue.getText(), true));
        }
        return result;
    }
/*
    private static XMLParser.AttributeContext findAttribute(XMLParser.ElementContext element,
            String name) {
        for (XMLParser.AttributeContext attr : element.attribute()) {
            if (escapeQuotes(attr.attrName.getText(), false).equals(name)) {
                return attr;
            }
        }
        return null;
    }*/

    private static String escapeQuotes(String textWithQuotes, boolean unescapeValue) {
        char first = textWithQuotes.charAt(0);
        int start = 0, end = textWithQuotes.length();
        if (first == '"' || first == '\'') {
            start = 1;
        }
        char last = textWithQuotes.charAt(textWithQuotes.length() - 1);
        if (last == '"' || last == '\'') {
            end -= 1;
        }
        String val = textWithQuotes.substring(start, end);
        if (unescapeValue) {
            return StringUtils.unescapeXml(val);
        } else {
            return val;
        }
    }

    private LayoutFileParser() {
    }
}
