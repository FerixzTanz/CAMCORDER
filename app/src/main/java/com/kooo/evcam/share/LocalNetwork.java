package com.kooo.evcam.share;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 找出「手机能访问到车机的哪个地址」。
 *
 * <h3>两种连法，地址不在同一个网卡上</h3>
 *
 * <ul>
 *   <li><b>车机开热点、手机连上来</b>：能用的是车机 AP 那块网卡的地址
 *       （通常是 {@code ap0} / {@code softap0}，地址多为 192.168.43.1）；</li>
 *   <li><b>手机开热点、车机连上去</b>：车机是客户端，能用的是它 {@code wlan0}
 *       上拿到的那个地址。</li>
 * </ul>
 *
 * <p>所以不能只认一种网卡。这里把所有可用的 IPv4 地址都列出来，
 * 按「哪个更可能是那条直连」排个序。认不出是热点 / 无线 / USB 共享的那些（车机内部的 vlan、veth……）
 * 在 {@link #usable} 里不给手机 —— 一个认得出的都没有时才全给：
 * 车机固件五花八门，猜错时得让人能自己挑一个试。</p>
 *
 * <p>网卡名的判断和排序都是纯函数，可以单独测。</p>
 */
public final class LocalNetwork {

    /** 这块网卡大概是干什么的。 */
    public enum Kind {
        /** 本机就是热点：AP 或 Wi-Fi Direct。 */
        HOTSPOT,
        /** 本机是无线客户端，连在别人的热点或路由上。 */
        WIFI,
        /** USB 网络共享。 */
        TETHER,
        /** 其余（以太网、虚拟网卡等）。 */
        OTHER
    }

    /** 一个可以写进二维码的地址。 */
    public static final class Endpoint {
        public final String interfaceName;
        public final String address;
        public final Kind kind;

        public Endpoint(String interfaceName, String address, Kind kind) {
            this.interfaceName = interfaceName;
            this.address = address;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s (%s, %s)", address, interfaceName, kind);
        }
    }

    private LocalNetwork() {
    }

    /**
     * 按网卡名猜它是哪一类。
     *
     * <p>名字是唯一能拿到的线索 —— Android 没有公开「哪块网卡是热点」的接口。
     * 认成 OTHER 的不会出现在二维码里（除非一个认得出的都没有），所以新见到的网卡名要补在这里。</p>
     */
    public static Kind classify(String interfaceName) {
        if (interfaceName == null) {
            return Kind.OTHER;
        }
        String name = interfaceName.toLowerCase(Locale.US);
        if (name.startsWith("ap") || name.contains("softap") || name.startsWith("p2p")
                || name.startsWith("swlan")) {
            return Kind.HOTSPOT;
        }
        if (name.startsWith("wlan") || name.startsWith("wifi")) {
            return Kind.WIFI;
        }
        if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")
                || name.startsWith("bt-pan")) {
            return Kind.TETHER;
        }
        return Kind.OTHER;
    }

    /** 排序用的优先级，越小越靠前。 */
    static int priority(Kind kind) {
        switch (kind) {
            case HOTSPOT:
                return 0;
            case WIFI:
                return 1;
            case TETHER:
                return 2;
            default:
                return 3;
        }
    }

    /**
     * 按「更可能是那条直连」排序，不删任何一项。
     *
     * <p>热点排在无线客户端之前：车机开热点时，AP 那块网卡的地址才是手机连得上的；
     * 而这两块网卡可能同时有地址。</p>
     */
    public static List<Endpoint> sorted(List<Endpoint> endpoints) {
        List<Endpoint> copy = new ArrayList<>(endpoints);
        Collections.sort(copy, (a, b) -> {
            int byKind = priority(a.kind) - priority(b.kind);
            return byKind != 0 ? byKind : a.interfaceName.compareTo(b.interfaceName);
        });
        return copy;
    }

    /**
     * 手机可能连得上的那几个：热点、无线、USB 共享。
     *
     * <p>车机上还挂着一串内部网卡（{@code vlan*}、{@code veth}、{@code vt*}，10.1.x.x、198.18.x.x 这些），
     * 是车机和车上别的控制器之间的线，手机永远够不着。以前一并列出来，「切换地址」要按十几下，
     * 默认那个也可能落在上面（2026-10-11：项目拥有者对着 10.1.4.6 扫了半天）。</p>
     *
     * <p>一个能用的都没有时返回空，<b>不</b>退回全部。以前退回全部，可这台车机上内部网卡永远在
     * （{@code veth} 192.168.55.15、{@code vt3} 192.168.118.1……），车机没连 Wi-Fi、热点也没开时，
     * 二维码照样给出一个 192.168.x.x，手机永远打不开，「请先连网」的提示也永远弹不出来。
     * 现在空就是空，由界面说「车机没连网」，并把找到的网卡列出来，万一哪台车机的无线网卡名字
     * 认不出来，看一眼就知道要补哪个名字。</p>
     */
    public static List<Endpoint> usable(List<Endpoint> endpoints) {
        List<Endpoint> out = new ArrayList<>();
        for (Endpoint e : sorted(endpoints)) {
            if (e.kind != Kind.OTHER) {
                out.add(e);
            }
        }
        return out;
    }

    /** 排在最前的那个；一个都没有时返回 null。 */
    public static Endpoint preferred(List<Endpoint> endpoints) {
        List<Endpoint> ordered = sorted(endpoints);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    /**
     * 列出本机所有可用的 IPv4 地址。
     *
     * <p>只要 IPv4：二维码里塞一个 IPv6 地址，手机浏览器多半打不开，
     * 而且那串东西长到二维码会密得扫不动。</p>
     *
     * <p>回环地址跳过 —— 127.0.0.1 对另一台设备没有任何意义。</p>
     */
    public static List<Endpoint> enumerate() {
        List<Endpoint> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return found;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    found.add(new Endpoint(iface.getName(),
                            address.getHostAddress(), classify(iface.getName())));
                }
            }
        } catch (Exception e) {
            // 拿不到网卡列表就当作没有，交给上层提示 —— 不是崩溃的理由
            return found;
        }
        return sorted(found);
    }
}
