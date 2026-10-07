package com.example.routermanager.router;

import com.example.routermanager.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser tests against every recorded reply in {@code fixtures/} (sanitised MACs, names and
 * accounts). These are the real field names and the real shapes of the H188A.
 */
class FixtureParsingTest {

    @Test
    @DisplayName("accessdev_ssiddev_lua: five Wi-Fi clients with SSID, RSSI and link rates")
    void parsesWifiClients() {
        List<RouterDevice> devices = ZteResponseParser.parseWifiDevices(Fixtures.doc("accessdev_ssiddev_lua"));

        assertThat(devices).hasSize(5);
        assertThat(devices).allSatisfy(device -> {
            assertThat(device.kind()).isEqualTo(DeviceKind.WIFI);
            assertThat(device.mac()).matches("([0-9a-f]{2}:){5}[0-9a-f]{2}");
            assertThat(device.port()).isNull();
        });

        RouterDevice first = devices.get(0);
        assertThat(first.mac()).isEqualTo("02:00:5e:00:00:02");
        assertThat(first.ip()).isEqualTo("192.168.1.2");
        assertThat(first.name()).isEmpty();
        assertThat(first.ssid()).isEqualTo("SSID1");
        assertThat(first.ssidName()).isEqualTo("HomeWiFi");
        assertThat(first.rssi()).isEqualTo(-56);
        assertThat(first.linkTxKbps()).isEqualTo(130000);
        assertThat(first.linkRxKbps()).isEqualTo(130000);
        assertThat(first.channel()).isEqualTo(9);
        assertThat(first.mode()).isEqualTo("11n");
    }

    @Test
    @DisplayName("accessdev_ssiddev_lua: the 5 GHz client is on SSID5")
    void parsesFiveGigahertzClient() {
        List<RouterDevice> devices = ZteResponseParser.parseWifiDevices(Fixtures.doc("accessdev_ssiddev_lua"));

        RouterDevice onFiveGhz = devices.stream()
                .filter(device -> "SSID5".equals(device.ssid())).findFirst().orElseThrow();

        assertThat(onFiveGhz.mac()).isEqualTo("02:00:5e:00:00:06");
        assertThat(onFiveGhz.linkTxKbps()).isEqualTo(650000);
        assertThat(onFiveGhz.rssi()).isEqualTo(-72);
    }

    @Test
    @DisplayName("accessdev_ssiddev_lua: an empty DeviceName stays empty, it is not invented")
    void keepsEmptyDeviceNames() {
        List<RouterDevice> devices = ZteResponseParser.parseWifiDevices(Fixtures.doc("accessdev_ssiddev_lua"));

        assertThat(devices).anySatisfy(device -> assertThat(device.name()).isEmpty());
        assertThat(devices).anySatisfy(device -> assertThat(device.name()).isEqualTo("device-1"));
    }

    @Test
    @DisplayName("accessdev_landevs_lua: the wired client with its LAN port and empty host name")
    void parsesWiredClients() {
        List<RouterDevice> devices = ZteResponseParser.parseWiredDevices(Fixtures.doc("accessdev_landevs_lua"));

        assertThat(devices).hasSize(1);
        RouterDevice device = devices.get(0);
        assertThat(device.kind()).isEqualTo(DeviceKind.WIRED);
        assertThat(device.mac()).isEqualTo("02:00:5e:00:00:01");
        assertThat(device.ip()).isEqualTo("192.168.1.18");
        assertThat(device.name()).isEmpty();
        assertThat(device.port()).isEqualTo("LAN2");
        assertThat(device.ssid()).isNull();
        assertThat(device.rssi()).isNull();
    }

    @Test
    @DisplayName("eth_lanstatus_lua: four LAN ports with byte counters and link state")
    void parsesPortCounters() {
        List<PortCounter> ports = ZteResponseParser.parsePortCounters(Fixtures.doc("eth_lanstatus_lua"));

        assertThat(ports).extracting(PortCounter::port).containsExactly("LAN1", "LAN2", "LAN3", "LAN4");

        PortCounter lan1 = ports.get(0);
        assertThat(lan1.status()).isEqualTo("NoLink");
        assertThat(lan1.up()).isFalse();
        assertThat(lan1.rxBytes()).isZero();
        assertThat(lan1.txBytes()).isZero();

        PortCounter lan2 = ports.get(1);
        assertThat(lan2.status()).isEqualTo("Up");
        assertThat(lan2.up()).isTrue();
        assertThat(lan2.linkSpeedMbps()).isEqualTo(100);
        assertThat(lan2.rxBytes()).isEqualTo(26_711_705L);
        assertThat(lan2.txBytes()).isEqualTo(38_064_729L);
    }

    @Test
    @DisplayName("wlan_status_lua: eight SSIDs, counters joined to the right SSID by _InstID")
    void parsesSsidCounters() {
        List<SsidCounter> counters = ZteResponseParser.parseSsidCounters(Fixtures.doc("wlan_status_lua"));

        assertThat(counters).hasSize(8);
        assertThat(counters).extracting(SsidCounter::alias)
                .containsExactly("SSID1", "SSID2", "SSID3", "SSID4", "SSID5", "SSID6", "SSID7", "SSID8");

        SsidCounter ssid1 = counters.get(0);
        assertThat(ssid1.name()).isEqualTo("HomeWiFi");
        assertThat(ssid1.enabled()).isTrue();
        assertThat(ssid1.rxBytes()).isEqualTo(1_503_556_195L);
        assertThat(ssid1.txBytes()).isEqualTo(3_342_582_966L);
        assertThat(ssid1.rxPackets()).isEqualTo(26_827_462L);
        assertThat(ssid1.txPackets()).isEqualTo(42_817_110L);

        SsidCounter ssid5 = counters.get(4);
        assertThat(ssid5.enabled()).isTrue();
        assertThat(ssid5.rxBytes()).isEqualTo(2_696_715L);
        assertThat(ssid5.txBytes()).isEqualTo(859_436_650L);

        assertThat(counters.get(1).enabled()).isFalse();
        assertThat(counters.get(1).rxBytes()).isZero();
    }

    @Test
    @DisplayName("wlan_status_lua: a TX counter above 2^31 is read as a long, not a negative int")
    void readsCountersBeyondIntRange() {
        SsidCounter ssid1 = ZteResponseParser.parseSsidCounters(Fixtures.doc("wlan_status_lua")).get(0);

        assertThat(ssid1.txBytes()).isGreaterThan(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("dsl_interface_status_lua: current and max sync rates in kbit/s")
    void parsesDslRates() {
        DslRates rates = ZteResponseParser.parseDslRates(Fixtures.doc("dsl_interface_status_lua"));

        assertThat(rates.downCurrentKbps()).isEqualTo(40959);
        assertThat(rates.downMaxKbps()).isEqualTo(104927);
        assertThat(rates.upCurrentKbps()).isEqualTo(5119);
        assertThat(rates.upMaxKbps()).isEqualTo(37641);
        assertThat(rates.status()).isEqualTo("Up");
    }

    @Test
    @DisplayName("devmgr_statusmgr_lua: model, firmware and hardware (the serial is not read)")
    void parsesDeviceInfo() {
        RouterInfo info = ZteResponseParser.parseDeviceInfo(Fixtures.doc("devmgr_statusmgr_lua"));

        assertThat(info.model()).isEqualTo("H188A");
        assertThat(info.firmware()).isEqualTo("V2.1.3P2_TE");
        assertThat(info.hardware()).isEqualTo("V2.1.1");
        assertThat(info.uptimeSeconds()).isNull();
    }

    @Test
    @DisplayName("wan_internet_lua: the CONNECTED WAN wins over the unconfigured leftovers")
    void parsesWanInfo() {
        RouterInfo base = ZteResponseParser.parseDeviceInfo(Fixtures.doc("devmgr_statusmgr_lua"));

        RouterInfo info = ZteResponseParser.mergeWanInfo(base, Fixtures.doc("wan_internet_lua"));

        assertThat(info.model()).isEqualTo("H188A");
        assertThat(info.wanStatus()).isEqualTo("Connected");
        assertThat(info.wanType()).isEqualTo("pppoe");
        assertThat(info.wanName()).isEqualTo("WAN0");
        assertThat(info.uptimeSeconds()).isEqualTo(331_037L);
    }

    @Test
    @DisplayName("arp_arptable_lua: MAC -> IP map")
    void parsesArpTable() {
        Map<String, String> arp = ZteResponseParser.parseArpTable(Fixtures.doc("arp_arptable_lua"));

        assertThat(arp).hasSize(6);
        assertThat(arp.get("02:00:5e:00:00:01")).isEqualTo("192.168.1.18");
        assertThat(arp.get("02:00:5e:00:00:06")).isEqualTo("192.168.1.9");
    }

    @Test
    @DisplayName("macinfo_mactable_lua: MAC -> interface it was learned on")
    void parsesMacTable() {
        Map<String, String> macs = ZteResponseParser.parseMacTable(Fixtures.doc("macinfo_mactable_lua"));

        assertThat(macs).hasSize(6);
        assertThat(macs.get("02:00:5e:00:00:01")).isEqualTo("LAN2");
        assertThat(macs.get("02:00:5e:00:00:06")).isEqualTo("SSID5");
    }

    @Test
    @DisplayName("merging the fixtures gives six distinct devices, five Wi-Fi and one wired")
    void mergesAllFixtures() {
        List<RouterDevice> devices = ZteResponseParser.mergeDevices(
                ZteResponseParser.parseWifiDevices(Fixtures.doc("accessdev_ssiddev_lua")),
                ZteResponseParser.parseWiredDevices(Fixtures.doc("accessdev_landevs_lua")),
                ZteResponseParser.parseArpTable(Fixtures.doc("arp_arptable_lua")),
                ZteResponseParser.parseMacTable(Fixtures.doc("macinfo_mactable_lua")));

        assertThat(devices).hasSize(6);
        assertThat(devices).filteredOn(device -> device.kind() == DeviceKind.WIFI).hasSize(5);
        assertThat(devices).filteredOn(device -> device.kind() == DeviceKind.WIRED).hasSize(1);
        assertThat(devices).extracting(RouterDevice::mac).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a missing IP is filled from the ARP table")
    void fillsMissingIpFromArp() {
        List<RouterDevice> wifi = List.of(new RouterDevice(DeviceKind.WIFI, "02:00:5e:00:00:06", "",
                "", "SSID5", "HomeWiFi", -70, 1000, 1000, 36, "11ac", null));

        List<RouterDevice> merged = ZteResponseParser.mergeDevices(wifi, List.of(),
                ZteResponseParser.parseArpTable(Fixtures.doc("arp_arptable_lua")), Map.of());

        assertThat(merged.get(0).ip()).isEqualTo("192.168.1.9");
    }

    @Test
    @DisplayName("a missing LAN port or SSID is filled from the MAC table")
    void fillsMissingInterfaceFromMacTable() {
        Map<String, String> macTable = ZteResponseParser.parseMacTable(Fixtures.doc("macinfo_mactable_lua"));
        List<RouterDevice> wired = List.of(new RouterDevice(DeviceKind.WIRED, "02:00:5e:00:00:01",
                "192.168.1.18", "", null, null, null, null, null, null, null, null));
        List<RouterDevice> wifi = List.of(new RouterDevice(DeviceKind.WIFI, "02:00:5e:00:00:03",
                "192.168.1.3", "", null, null, -60, 1000, 1000, 9, "11n", null));

        List<RouterDevice> merged = ZteResponseParser.mergeDevices(wifi, wired, Map.of(), macTable);

        assertThat(merged).anySatisfy(device -> {
            if (device.kind() == DeviceKind.WIRED) {
                assertThat(device.port()).isEqualTo("LAN2");
            }
        });
        assertThat(merged.stream().filter(device -> device.kind() == DeviceKind.WIFI).findFirst()
                .orElseThrow().ssid()).isEqualTo("SSID1");
    }

    @Test
    @DisplayName("a MAC listed on both sides keeps the richer Wi-Fi record")
    void wifiWinsOverWiredForTheSameMac() {
        String mac = "02:00:5e:00:00:09";
        List<RouterDevice> wifi = List.of(new RouterDevice(DeviceKind.WIFI, mac, "192.168.1.20", "phone",
                "SSID1", "HomeWiFi", -50, 130000, 130000, 9, "11n", null));
        List<RouterDevice> wired = List.of(new RouterDevice(DeviceKind.WIRED, mac, "192.168.1.20", "",
                null, null, null, null, null, null, null, "LAN3"));

        List<RouterDevice> merged = ZteResponseParser.mergeDevices(wifi, wired, Map.of(), Map.of());

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).kind()).isEqualTo(DeviceKind.WIFI);
        assertThat(merged.get(0).rssi()).isEqualTo(-50);
    }

    @Test
    @DisplayName("empty containers and missing endpoints degrade to empty lists, not exceptions")
    void toleratesEmptyDocuments() {
        AjaxDocument empty = AjaxXml.parse(
                "<ajax_response_xml_root><IF_ERRORSTR>SUCC</IF_ERRORSTR></ajax_response_xml_root>");

        assertThat(ZteResponseParser.parseWifiDevices(empty)).isEmpty();
        assertThat(ZteResponseParser.parseWiredDevices(empty)).isEmpty();
        assertThat(ZteResponseParser.parsePortCounters(empty)).isEmpty();
        assertThat(ZteResponseParser.parseSsidCounters(empty)).isEmpty();
        assertThat(ZteResponseParser.parseArpTable(empty)).isEmpty();
        assertThat(ZteResponseParser.parseMacTable(empty)).isEmpty();
        assertThat(ZteResponseParser.parseDslRates(empty)).isEqualTo(DslRates.empty());
        assertThat(ZteResponseParser.parseDeviceInfo(empty)).isEqualTo(RouterInfo.empty());
        assertThat(ZteResponseParser.mergeWanInfo(RouterInfo.empty(), empty))
                .isEqualTo(RouterInfo.empty());
    }

    @Test
    @DisplayName("junk numbers and junk MACs never break a poll")
    void toleratesJunkValues() {
        AjaxDocument document = AjaxXml.parse("""
                <ajax_response_xml_root><IF_ERRORSTR>SUCC</IF_ERRORSTR><OBJ_ETH_ID><Instance>
                <ParaName>AliasName</ParaName><ParaValue>LAN1</ParaValue>
                <ParaName>BytesReceived</ParaName><ParaValue>n/a</ParaValue>
                <ParaName>BytesSent</ParaName><ParaValue>-5</ParaValue>
                <ParaName>LinkSpeed</ParaName><ParaValue>fast</ParaValue>
                </Instance></OBJ_ETH_ID></ajax_response_xml_root>""");

        List<PortCounter> ports = ZteResponseParser.parsePortCounters(document);

        assertThat(ports).hasSize(1);
        assertThat(ports.get(0).rxBytes()).isZero();
        // A negative counter is impossible; it is clamped rather than propagated.
        assertThat(ports.get(0).txBytes()).isZero();
        assertThat(ports.get(0).linkSpeedMbps()).isNull();
    }

    @Test
    @DisplayName("a device without a MAC is skipped entirely")
    void skipsDevicesWithoutMac() {
        AjaxDocument document = AjaxXml.parse("""
                <ajax_response_xml_root><OBJ_WLANAD_ID><Instance>
                <ParaName>ADMACAddress</ParaName><ParaValue />
                <ParaName>ADIPAddress</ParaName><ParaValue>192.168.1.5</ParaValue>
                </Instance></OBJ_WLANAD_ID></ajax_response_xml_root>""");

        assertThat(ZteResponseParser.parseWifiDevices(document)).isEmpty();
    }

    @Test
    @DisplayName("session_timeout.xml is the shape the client must recognise")
    void sessionTimeoutFixture() {
        assertThat(AjaxXml.parse(Fixtures.text("session_timeout.xml")).isSessionTimeout()).isTrue();
    }

    @Test
    @DisplayName("login_token.xml carries the numeric salt")
    void loginTokenFixture() {
        assertThat(AjaxXml.parse(Fixtures.text("login_token.xml")).rootText()).isEqualTo("72704973");
    }
}
