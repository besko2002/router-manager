package com.example.routermanager.router;

/**
 * The exact URLs of the H188A web UI, as recorded during recon.
 *
 * <p>A data endpoint only answers after its PAGE has been opened in the same session; otherwise the
 * router replies {@code <IF_ERRORSTR>SessionTimeout</IF_ERRORSTR>}. Hence every endpoint carries
 * the page it belongs to.
 */
public final class ZteEndpoints {

    public static final String PAGE_LOCAL_NET = "localNetStatus";
    public static final String PAGE_DSL_WAN = "dslWanStatus";
    public static final String PAGE_ARP = "arpTable";
    public static final String PAGE_MAC = "macTable";
    public static final String PAGE_STATUS_MGR = "statusMgr";

    public static final String WIRED_DEVICES = "accessdev_landevs_lua.lua";
    public static final String WIFI_DEVICES = "accessdev_ssiddev_lua.lua";
    public static final String LAN_STATUS = "eth_lanstatus_lua.lua";
    public static final String WLAN_STATUS = "wlan_status_lua.lua";
    public static final String DSL_STATUS = "dsl_interface_status_lua.lua";
    public static final String WAN_INTERNET = "wan_internet_lua.lua";
    public static final String ARP_TABLE = "arp_arptable_lua.lua";
    public static final String MAC_TABLE = "macinfo_mactable_lua.lua";
    public static final String STATUS_MGR = "devmgr_statusmgr_lua.lua";

    /** {@code wan_internet} needs the uplink selectors, otherwise it answers with an empty list. */
    public static final String WAN_INTERNET_EXTRA = "&TypeUplink=1&pageType=1";

    public static final String LOGIN_STATE = "/?_type=loginData&_tag=login_entry";
    public static final String LOGIN_TOKEN = "/?_type=loginData&_tag=login_token";
    public static final String LOGOUT = "/?_type=loginData&_tag=logout_entry";
    public static final String ROOT = "/";

    private ZteEndpoints() {
    }

    public static String menuView(String page) {
        return "/?_type=menuView&_tag=" + page + "&Menu3Location=0";
    }

    public static String menuData(String endpoint, String extra, long timestamp) {
        return "/?_type=menuData&_tag=" + endpoint + (extra == null ? "" : extra) + "&_=" + timestamp;
    }

    /** The fixture / simulator key for a wire tag: {@code eth_lanstatus_lua.lua} -> {@code eth_lanstatus_lua}. */
    public static String fixtureName(String endpoint) {
        String name = endpoint;
        if (name.endsWith(".lua")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }
}
