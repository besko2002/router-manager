package com.example.routermanager.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@code <ajax_response_xml_root>} document.
 *
 * <p>Shape on the wire:
 * <pre>
 * &lt;ajax_response_xml_root&gt;
 *   &lt;IF_ERRORSTR&gt;SUCC&lt;/IF_ERRORSTR&gt;        &lt;-- scalar
 *   &lt;OBJ_ETH_ID&gt;                              &lt;-- container
 *     &lt;Instance&gt;&lt;ParaName&gt;..&lt;/ParaName&gt;&lt;ParaValue&gt;..&lt;/ParaValue&gt;…&lt;/Instance&gt;
 *   &lt;/OBJ_ETH_ID&gt;
 * &lt;/ajax_response_xml_root&gt;
 * </pre>
 *
 * <p>Container names are NOT always {@code OBJ_*} — {@code wan_internet_lua} uses
 * {@code ID_WAN_COMFIG} (the router's own typo) — so any element holding {@code <Instance>}
 * children counts as a container.
 */
public final class AjaxDocument {

    /** What the router answers when the session is gone or the page was never opened. */
    public static final String SESSION_TIMEOUT = "SessionTimeout";

    private final Map<String, String> scalars;
    private final Map<String, List<AjaxInstance>> containers;
    private final String rootText;

    public AjaxDocument(Map<String, String> scalars,
                        Map<String, List<AjaxInstance>> containers,
                        String rootText) {
        this.scalars = new LinkedHashMap<>(scalars);
        this.containers = new LinkedHashMap<>(containers);
        this.rootText = rootText == null ? "" : rootText;
    }

    public Map<String, String> scalars() {
        return Collections.unmodifiableMap(scalars);
    }

    public Map<String, List<AjaxInstance>> containers() {
        return Collections.unmodifiableMap(containers);
    }

    /** Direct text of the root element — {@code login_token} answers a bare number this way. */
    public String rootText() {
        return rootText;
    }

    public String scalar(String name) {
        return scalars.getOrDefault(name, "");
    }

    public String errorStr() {
        return scalar("IF_ERRORSTR");
    }

    public boolean isSessionTimeout() {
        return SESSION_TIMEOUT.equalsIgnoreCase(errorStr().trim());
    }

    /** Instances of one container, empty when the container is absent. */
    public List<AjaxInstance> instances(String container) {
        return containers.getOrDefault(container, List.of());
    }

    /**
     * Instances of the first container whose name matches one of {@code candidates}; falls back to
     * every instance in the document when none matches, so a renamed container after a firmware
     * update still yields data instead of nothing.
     */
    public List<AjaxInstance> instancesOfAny(String... candidates) {
        for (String candidate : candidates) {
            List<AjaxInstance> found = containers.get(candidate);
            if (found != null && !found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    public List<AjaxInstance> allInstances() {
        List<AjaxInstance> all = new ArrayList<>();
        containers.values().forEach(all::addAll);
        return all;
    }

    @Override
    public String toString() {
        return "AjaxDocument{error=" + errorStr() + ", containers=" + containers.keySet() + "}";
    }
}
