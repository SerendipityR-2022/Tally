package com.example.budgetapp.util;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded in-memory XML transport; no world-readable dump or screenshot files. */
public final class ScreenHierarchyCodec {
    public static final int MAX_CHARS = 200000;
    public static final int MAX_NODES = 2000;
    public static final int MAX_DEPTH = 60;
    private ScreenHierarchyCodec() { }

    public static String encode(ScreenNode node) {
        StringBuilder xml = new StringBuilder("<hierarchy>");
        append(xml, node, 0, new int[]{0});
        xml.append("</hierarchy>");
        if (xml.length() > MAX_CHARS) throw new IllegalArgumentException("Screen XML too large");
        return xml.toString();
    }

    private static void append(StringBuilder xml, ScreenNode node, int depth, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) throw new IllegalArgumentException("Screen tree too large");
        xml.append("<node");
        attribute(xml, "text", node.getText());
        attribute(xml, "content-desc", node.getContentDescription());
        attribute(xml, "package", node.getPackageName());
        attribute(xml, "class", node.getClassName());
        attribute(xml, "resource-id", node.getViewIdResourceName());
        xml.append('>');
        if (xml.length() > MAX_CHARS) throw new IllegalArgumentException("Screen XML too large");
        for (int i = 0; i < node.getChildCount(); i++) append(xml, node.getChild(i), depth + 1, count);
        xml.append("</node>");
    }

    private static void attribute(StringBuilder xml, String key, String value) {
        if (value == null) return;
        xml.append(' ').append(key).append("=\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&': xml.append("&amp;"); break;
                case '<': xml.append("&lt;"); break;
                case '>': xml.append("&gt;"); break;
                case '"': xml.append("&quot;"); break;
                case '\n': xml.append("&#10;"); break;
                case '\r': xml.append("&#13;"); break;
                case '\t': xml.append("&#9;"); break;
                default: if (ch >= 0x20 && ch != 0xfffe && ch != 0xffff) xml.append(ch);
            }
        }
        xml.append('"');
    }

    public static ScreenNode decode(String xml, String expectedPackage) throws Exception {
        if (!ScreenCapturePolicy.isTargetPackage(expectedPackage)) throw new IllegalArgumentException("Invalid screen scope");
        return decodeTree(xml, expectedPackage);
    }

    public static ScreenNode decodeDiagnostic(String xml, String selectedPackage, String owner) throws Exception {
        if (!ScreenCapturePolicy.canInspect(selectedPackage, owner)) throw new IllegalArgumentException("Invalid diagnostic scope");
        return decodeTree(xml, selectedPackage);
    }

    private static ScreenNode decodeTree(String xml, String expectedPackage) throws Exception {
        if (xml == null || xml.length() > MAX_CHARS) throw new IllegalArgumentException("Invalid screen size");
        String upper = xml.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new IllegalArgumentException("DTD not allowed");
        TreeHandler handler = new TreeHandler(expectedPackage);
        org.xml.sax.XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities forbidden"); });
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.parse(new InputSource(new StringReader(xml)));
        if (handler.root == null || !expectedPackage.equals(handler.root.getPackageName())) {
            throw new IllegalArgumentException("Screen package mismatch");
        }
        return handler.root;
    }

    private static final class Draft {
        final String text, desc, pkg, cls, id;
        final List<ScreenNode> children = new ArrayList<>();
        Draft(Attributes a) {
            text = a.getValue("text"); desc = a.getValue("content-desc"); pkg = a.getValue("package");
            cls = a.getValue("class"); id = a.getValue("resource-id");
        }
        ScreenNode build() { return new ScreenNode(text, desc, pkg, cls, id, children); }
    }

    private static final class TreeHandler extends DefaultHandler {
        final String expected;
        final Deque<Draft> stack = new ArrayDeque<>();
        ScreenNode root;
        int nodes;
        boolean wrapperSeen;
        boolean wrapperOpen;
        TreeHandler(String expected) { this.expected = expected; }
        @Override public void startElement(String uri, String local, String name, Attributes a) throws SAXException {
            if ("hierarchy".equals(name) && !wrapperSeen) {
                wrapperSeen = wrapperOpen = true;
                return;
            }
            if (!wrapperOpen || !"node".equals(name) || stack.size() > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw new SAXException("Invalid screen tree");
            }
            String pkg = a.getValue("package");
            if (pkg != null && !pkg.isEmpty() && !expected.equals(pkg)) throw new SAXException("Mixed package screen");
            stack.push(new Draft(a));
        }
        @Override public void endElement(String uri, String local, String name) throws SAXException {
            if ("hierarchy".equals(name)) {
                if (!stack.isEmpty() || !wrapperOpen) throw new SAXException("Invalid screen wrapper");
                wrapperOpen = false;
                return;
            }
            if (stack.isEmpty()) throw new SAXException("Unbalanced screen tree");
            ScreenNode node = stack.pop().build();
            if (stack.isEmpty()) {
                if (root != null) throw new SAXException("Multiple screen roots");
                root = node;
            } else stack.peek().children.add(node);
        }
    }
}
