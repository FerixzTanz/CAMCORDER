package com.kooo.evcam.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link LocalNetwork} 的单元测试。
 *
 * <p>要钉住的是排序：车机开热点时，两块网卡可能同时有地址，
 * 而只有 AP 那块是手机连得上的。挑错了的表现是「扫了码打不开」，
 * 在车上很难判断是网络问题还是应用问题。</p>
 */
public class LocalNetworkTest {

    private static LocalNetwork.Endpoint at(String iface, String ip) {
        return new LocalNetwork.Endpoint(iface, ip, LocalNetwork.classify(iface));
    }

    @Test
    public void hotspotInterfacesAreRecognised() {
        assertEquals(LocalNetwork.Kind.HOTSPOT, LocalNetwork.classify("ap0"));
        assertEquals(LocalNetwork.Kind.HOTSPOT, LocalNetwork.classify("softap0"));
        assertEquals(LocalNetwork.Kind.HOTSPOT, LocalNetwork.classify("p2p-wlan0-0"));
    }

    @Test
    public void wifiClientInterfacesAreRecognised() {
        assertEquals(LocalNetwork.Kind.WIFI, LocalNetwork.classify("wlan0"));
        assertEquals(LocalNetwork.Kind.WIFI, LocalNetwork.classify("wlan1"));
    }

    @Test
    public void usbTetheringIsItsOwnKind() {
        assertEquals(LocalNetwork.Kind.TETHER, LocalNetwork.classify("rndis0"));
        assertEquals(LocalNetwork.Kind.TETHER, LocalNetwork.classify("usb0"));
    }

    @Test
    public void anythingElseIsOther() {
        assertEquals(LocalNetwork.Kind.OTHER, LocalNetwork.classify("eth0"));
        assertEquals(LocalNetwork.Kind.OTHER, LocalNetwork.classify("dummy0"));
        assertEquals(LocalNetwork.Kind.OTHER, LocalNetwork.classify(null));
    }

    /** 车机自己开热点时，AP 那块网卡的地址才是手机连得上的。 */
    @Test
    public void hotspotComesBeforeWifiClient() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("wlan0", "192.168.1.23"),
                at("ap0", "192.168.43.1"));
        assertEquals("192.168.43.1", LocalNetwork.preferred(found).address);
    }

    /** 车机连的是手机热点时，只有 wlan0 有地址，那就是它。 */
    @Test
    public void wifiClientIsUsedWhenThereIsNoHotspot() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("eth0", "10.0.0.5"),
                at("wlan0", "192.168.43.117"));
        assertEquals("192.168.43.117", LocalNetwork.preferred(found).address);
    }

    /** 排序不能删东西 —— 猜错时得让人自己挑一个试。 */
    @Test
    public void sortingKeepsEveryCandidate() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("eth0", "10.0.0.5"),
                at("wlan0", "192.168.1.23"),
                at("ap0", "192.168.43.1"),
                at("rndis0", "192.168.42.129"));
        List<LocalNetwork.Endpoint> ordered = LocalNetwork.sorted(found);
        assertEquals(4, ordered.size());
        assertEquals(LocalNetwork.Kind.HOTSPOT, ordered.get(0).kind);
        assertEquals(LocalNetwork.Kind.WIFI, ordered.get(1).kind);
        assertEquals(LocalNetwork.Kind.TETHER, ordered.get(2).kind);
        assertEquals(LocalNetwork.Kind.OTHER, ordered.get(3).kind);
    }

    @Test
    public void noAddressesMeansNoPreferredOne() {
        assertNull(LocalNetwork.preferred(new ArrayList<>()));
    }

    /** 车机内部的网卡（2026-10-11 实车：vlan2..vlan99、veth、vt3）不给手机用。 */
    @Test
    public void internalInterfacesAreLeftOutWhenWifiIsThere() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("veth", "192.168.55.15"),
                at("vlan4", "10.1.4.6"),
                at("vt3", "192.168.118.1"),
                at("wlan2", "192.168.5.78"));
        List<LocalNetwork.Endpoint> usable = LocalNetwork.usable(found);
        assertEquals(1, usable.size());
        assertEquals("192.168.5.78", usable.get(0).address);
    }

    /** 一个认得出的都没有时就什么都不给，由界面说「车机没连网」，不拿内部网卡凑数。 */
    @Test
    public void nothingIsOfferedWhenNothingIsRecognised() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("eth0", "10.0.0.5"),
                at("vlan4", "10.1.4.6"));
        assertEquals(0, LocalNetwork.usable(found).size());
    }

    /**
     * 车机没连 Wi-Fi、热点也没开时的实车网卡（2026-10-11）：以前退回全部，排第一的是 veth 的
     * 192.168.55.15 —— 一个 192.168.x.x，手机永远打不开，「请先连网」的提示也永远弹不出来。
     */
    @Test
    public void internalLinksAloneNeverBecomeTheQrAddress() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("veth", "192.168.55.15"),
                at("vlan4", "10.1.4.6"),
                at("vt3", "192.168.118.1"));
        assertEquals(0, LocalNetwork.usable(found).size());
    }

    @Test
    public void otherHotspotAndTetherNamesAreRecognised() {
        assertEquals(LocalNetwork.Kind.HOTSPOT, LocalNetwork.classify("swlan0"));
        assertEquals(LocalNetwork.Kind.TETHER, LocalNetwork.classify("ncm0"));
        assertEquals(LocalNetwork.Kind.TETHER, LocalNetwork.classify("bt-pan"));
    }
}
