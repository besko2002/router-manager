package com.example.routermanager.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The XML layer: the alternating ParaName/ParaValue shape and everything that can go wrong in it. */
class AjaxXmlTest {

    private static final String ENVELOPE = """
            <?xml version='1.0' encoding='utf-8'?>
            <ajax_response_xml_root><IF_ERRORSTR>SUCC</IF_ERRORSTR><OBJ_X_ID>%s</OBJ_X_ID></ajax_response_xml_root>
            """;

    private static AjaxDocument withInstances(String instances) {
        return AjaxXml.parse(ENVELOPE.formatted(instances));
    }

    @Test
    @DisplayName("alternating ParaName/ParaValue becomes a key/value instance")
    void parsesAlternatingPairs() {
        AjaxDocument document = withInstances(
                "<Instance><ParaName>A</ParaName><ParaValue>1</ParaValue>"
                        + "<ParaName>B</ParaName><ParaValue>2</ParaValue></Instance>");

        List<AjaxInstance> instances = document.instances("OBJ_X_ID");
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).get("A")).isEqualTo("1");
        assertThat(instances.get(0).get("B")).isEqualTo("2");
        assertThat(document.errorStr()).isEqualTo("SUCC");
    }

    @Test
    @DisplayName("a self-closing <ParaValue /> is an empty value, not a missing field")
    void parsesSelfClosingValue() {
        AjaxInstance instance = withInstances(
                "<Instance><ParaName>HostName</ParaName><ParaValue />"
                        + "<ParaName>IP</ParaName><ParaValue>192.168.1.5</ParaValue></Instance>")
                .instances("OBJ_X_ID").get(0);

        assertThat(instance.has("HostName")).isTrue();
        assertThat(instance.get("HostName")).isEmpty();
        assertThat(instance.get("IP")).isEqualTo("192.168.1.5");
    }

    @Test
    @DisplayName("a name whose value never arrives is kept as empty")
    void toleratesNameWithoutValue() {
        AjaxInstance instance = withInstances(
                "<Instance><ParaName>A</ParaName><ParaName>B</ParaName><ParaValue>2</ParaValue></Instance>")
                .instances("OBJ_X_ID").get(0);

        assertThat(instance.get("A")).isEmpty();
        assertThat(instance.get("B")).isEqualTo("2");
    }

    @Test
    @DisplayName("a value with no preceding name is dropped")
    void dropsOrphanValue() {
        AjaxInstance instance = withInstances(
                "<Instance><ParaValue>orphan</ParaValue><ParaName>A</ParaName><ParaValue>1</ParaValue></Instance>")
                .instances("OBJ_X_ID").get(0);

        assertThat(instance.size()).isEqualTo(1);
        assertThat(instance.get("A")).isEqualTo("1");
    }

    @Test
    @DisplayName("an empty ParaName does not break the instance")
    void toleratesEmptyName() {
        AjaxInstance instance = withInstances(
                "<Instance><ParaName /><ParaValue>x</ParaValue>"
                        + "<ParaName>A</ParaName><ParaValue>1</ParaValue></Instance>")
                .instances("OBJ_X_ID").get(0);

        assertThat(instance.get("A")).isEqualTo("1");
        assertThat(instance.get("")).isEqualTo("x");
    }

    @Test
    @DisplayName("unknown elements inside an instance are ignored")
    void ignoresUnknownElements() {
        AjaxInstance instance = withInstances(
                "<Instance><Junk>?</Junk><ParaName>A</ParaName><ParaValue>1</ParaValue></Instance>")
                .instances("OBJ_X_ID").get(0);

        assertThat(instance.asMap()).containsExactly(java.util.Map.entry("A", "1"));
    }

    @Test
    @DisplayName("lookups are case-insensitive and missing fields read as empty, never null")
    void lookupIsLenient() {
        AjaxInstance instance = withInstances(
                "<Instance><ParaName>MACAddress</ParaName><ParaValue>02:00:5e:00:00:01</ParaValue></Instance>")
                .instances("OBJ_X_ID").get(0);

        assertThat(instance.get("macaddress")).isEqualTo("02:00:5e:00:00:01");
        assertThat(instance.get("nope")).isEmpty();
        assertThat(instance.getIntOrNull("nope")).isNull();
        assertThat(instance.getLong("nope", -1)).isEqualTo(-1);
    }

    @Test
    @DisplayName("a bare & in a value does not break parsing")
    void repairsBareAmpersand() {
        AjaxDocument document = withInstances(
                "<Instance><ParaName>SSID</ParaName><ParaValue>Tom & Jerry</ParaValue></Instance>");

        assertThat(document.instances("OBJ_X_ID").get(0).get("SSID")).isEqualTo("Tom & Jerry");
    }

    @Test
    @DisplayName("real entities survive the ampersand repair")
    void keepsRealEntities() {
        AjaxDocument document = withInstances(
                "<Instance><ParaName>SSID</ParaName><ParaValue>a&amp;b&#38;c</ParaValue></Instance>");

        assertThat(document.instances("OBJ_X_ID").get(0).get("SSID")).isEqualTo("a&b&c");
    }

    @Test
    @DisplayName("any element holding <Instance> counts as a container, OBJ_ prefix or not")
    void containerNeedNotStartWithObj() {
        AjaxDocument document = AjaxXml.parse("""
                <ajax_response_xml_root><ID_WAN_COMFIG><Instance>
                <ParaName>ConnStatus</ParaName><ParaValue>Connected</ParaValue>
                </Instance></ID_WAN_COMFIG></ajax_response_xml_root>""");

        assertThat(document.containers().keySet()).containsExactly("ID_WAN_COMFIG");
        assertThat(document.allInstances()).hasSize(1);
    }

    @Test
    @DisplayName("login_token answers a bare number in the root element")
    void readsRootText() {
        assertThat(AjaxXml.parse("<ajax_response_xml_root>72704973</ajax_response_xml_root>").rootText())
                .isEqualTo("72704973");
    }

    @Test
    @DisplayName("SessionTimeout is recognised")
    void detectsSessionTimeout() {
        AjaxDocument document = AjaxXml.parse("""
                <ajax_response_xml_root><IF_ERRORSTR>SessionTimeout</IF_ERRORSTR></ajax_response_xml_root>""");

        assertThat(document.isSessionTimeout()).isTrue();
        assertThat(AjaxXml.parse("<ajax_response_xml_root><IF_ERRORSTR>SUCC</IF_ERRORSTR>"
                + "</ajax_response_xml_root>").isSessionTimeout()).isFalse();
    }

    @Test
    @DisplayName("garbage and empty replies raise a protocol error, not a random exception")
    void rejectsNonXml() {
        // Well-formed XML that is not an ajax reply (the login page) is still a protocol error.
        assertThatThrownBy(() -> AjaxXml.parse("<html>login page</html>"))
                .isInstanceOf(RouterProtocolException.class)
                .hasMessageContaining("unexpected root element");
        assertThatThrownBy(() -> AjaxXml.parse("<ajax_response_xml_root><broken>"))
                .isInstanceOf(RouterProtocolException.class);
        assertThatThrownBy(() -> AjaxXml.parse("   "))
                .isInstanceOf(RouterProtocolException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> AjaxXml.parse(null)).isInstanceOf(RouterProtocolException.class);
    }

    @Test
    @DisplayName("a doctype is refused (no external entity expansion)")
    void refusesDoctype() {
        assertThatThrownBy(() -> AjaxXml.parse(
                "<!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><ajax_response_xml_root>&x;"
                        + "</ajax_response_xml_root>"))
                .isInstanceOf(RouterProtocolException.class);
    }

    @Test
    @DisplayName("render -> parse keeps every field, including empty ones")
    void renderRoundTrip() {
        AjaxDocument original = withInstances(
                "<Instance><ParaName>A</ParaName><ParaValue>1</ParaValue>"
                        + "<ParaName>B</ParaName><ParaValue /></Instance>"
                        + "<Instance><ParaName>A</ParaName><ParaValue>2</ParaValue></Instance>");

        AjaxDocument reparsed = AjaxXml.parse(AjaxXml.render(original));

        assertThat(reparsed.errorStr()).isEqualTo("SUCC");
        assertThat(reparsed.instances("OBJ_X_ID")).hasSize(2);
        assertThat(reparsed.instances("OBJ_X_ID").get(0).get("A")).isEqualTo("1");
        assertThat(reparsed.instances("OBJ_X_ID").get(0).has("B")).isTrue();
        assertThat(reparsed.instances("OBJ_X_ID").get(0).get("B")).isEmpty();
        assertThat(reparsed.instances("OBJ_X_ID").get(1).get("A")).isEqualTo("2");
    }

    @Test
    @DisplayName("rendering escapes markup in values")
    void renderEscapes() {
        AjaxDocument document = withInstances(
                "<Instance><ParaName>N</ParaName><ParaValue>a&lt;b&amp;c</ParaValue></Instance>");

        String xml = AjaxXml.render(document);

        assertThat(xml).contains("a&lt;b&amp;c");
        assertThat(AjaxXml.parse(xml).instances("OBJ_X_ID").get(0).get("N")).isEqualTo("a<b&c");
    }

    @Test
    @DisplayName("instancesOfAny falls back through candidate container names")
    void instancesOfAnyPicksTheFirstMatch() {
        AjaxDocument document = withInstances(
                "<Instance><ParaName>A</ParaName><ParaValue>1</ParaValue></Instance>");

        assertThat(document.instancesOfAny("OBJ_MISSING", "OBJ_X_ID")).hasSize(1);
        assertThat(document.instancesOfAny("OBJ_MISSING")).isEmpty();
        assertThat(document.instances("OBJ_MISSING")).isEmpty();
    }

    @Test
    @DisplayName("AjaxInstance.set replaces the first occurrence and appends unknown names")
    void instanceMutation() {
        AjaxInstance instance = AjaxInstance.of("A", "1", "A", "2");

        instance.set("A", "9").set("B", "3");

        assertThat(instance.get("A")).isEqualTo("9");
        assertThat(instance.valueAt(1)).isEqualTo("2");
        assertThat(instance.get("B")).isEqualTo("3");
        assertThat(instance.copy().get("A")).isEqualTo("9");
    }
}
