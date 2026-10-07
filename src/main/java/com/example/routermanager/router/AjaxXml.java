package com.example.routermanager.router;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and renders the router's {@code ajax_response_xml_root} documents. */
public final class AjaxXml {

    private static final String ROOT_ELEMENT = "ajax_response_xml_root";
    private static final String INSTANCE = "Instance";
    private static final String PARA_NAME = "ParaName";
    private static final String PARA_VALUE = "ParaValue";

    /** A bare {@code &} that is not the start of an entity — the router does emit these. */
    private static final Pattern LONE_AMPERSAND =
            Pattern.compile("&(?!#\\d+;|#x[0-9a-fA-F]+;|[a-zA-Z][a-zA-Z0-9]{1,8};)");

    private AjaxXml() {
    }

    // ------------------------------------------------------------------ parsing

    public static AjaxDocument parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new RouterProtocolException("empty router reply");
        }
        Document dom = toDom(sanitize(xml));
        Element root = dom.getDocumentElement();
        if (root == null) {
            throw new RouterProtocolException("router reply has no root element");
        }
        if (!ROOT_ELEMENT.equals(root.getTagName())) {
            // Most often this is the login page served instead of data: a protocol error, not data.
            throw new RouterProtocolException(
                    "unexpected root element <" + root.getTagName() + ">, expected <" + ROOT_ELEMENT + ">");
        }

        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<AjaxInstance>> containers = new LinkedHashMap<>();
        StringBuilder rootText = new StringBuilder();

        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                rootText.append(child.getNodeValue());
                continue;
            }
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            List<Element> instanceElements = childrenNamed(element, INSTANCE);
            if (instanceElements.isEmpty()) {
                scalars.putIfAbsent(element.getTagName(), textOf(element));
            } else {
                List<AjaxInstance> instances = containers
                        .computeIfAbsent(element.getTagName(), key -> new ArrayList<>());
                for (Element instanceElement : instanceElements) {
                    instances.add(parseInstance(instanceElement));
                }
            }
        }
        return new AjaxDocument(scalars, containers, rootText.toString().trim());
    }

    /**
     * Alternating {@code ParaName}/{@code ParaValue}. Tolerated: a name with no value (becomes
     * {@code ""}), a value with no name (dropped), self-closing {@code <ParaValue/>}, empty names,
     * and any other element inside the instance (ignored).
     */
    private static AjaxInstance parseInstance(Element instanceElement) {
        AjaxInstance instance = new AjaxInstance();
        String pendingName = null;
        for (Node child = instanceElement.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            String tag = element.getTagName();
            if (PARA_NAME.equals(tag)) {
                if (pendingName != null) {
                    instance.add(pendingName, "");
                }
                pendingName = textOf(element);
            } else if (PARA_VALUE.equals(tag)) {
                if (pendingName != null) {
                    instance.add(pendingName, textOf(element));
                    pendingName = null;
                }
            }
        }
        if (pendingName != null) {
            instance.add(pendingName, "");
        }
        return instance;
    }

    private static List<Element> childrenNamed(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(((Element) node).getTagName())) {
                found.add((Element) node);
            }
        }
        return found;
    }

    private static String textOf(Element element) {
        String text = element.getTextContent();
        return text == null ? "" : text.trim();
    }

    static String sanitize(String xml) {
        String text = xml.trim();
        // Some replies are prefixed with a BOM or stray whitespace before the declaration.
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return LONE_AMPERSAND.matcher(text).replaceAll(Matcher.quoteReplacement("&amp;"));
    }

    private static Document toDom(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException e) {
            throw new RouterProtocolException("XML parser configuration failed", e);
        } catch (Exception e) {
            throw new RouterProtocolException("router reply is not parsable XML: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ rendering (simulator)

    public static String render(AjaxDocument document) {
        StringBuilder out = new StringBuilder("<?xml version='1.0' encoding='utf-8'?>\n");
        out.append("<ajax_response_xml_root>");
        document.scalars().forEach((name, value) ->
                out.append('<').append(name).append('>').append(escape(value))
                        .append("</").append(name).append('>'));
        document.containers().forEach((container, instances) -> {
            out.append('<').append(container).append('>');
            for (AjaxInstance instance : instances) {
                out.append("<Instance>");
                for (int i = 0; i < instance.size(); i++) {
                    out.append("<ParaName>").append(escape(instance.nameAt(i))).append("</ParaName>");
                    String value = instance.valueAt(i);
                    if (value.isEmpty()) {
                        out.append("<ParaValue />");
                    } else {
                        out.append("<ParaValue>").append(escape(value)).append("</ParaValue>");
                    }
                }
                out.append("</Instance>");
            }
            out.append("</").append(container).append('>');
        });
        return out.append("</ajax_response_xml_root>").toString();
    }

    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
