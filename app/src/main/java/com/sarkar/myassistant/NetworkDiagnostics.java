package com.sarkar.myassistant;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * NetworkDiagnostics handles local Hotspot/Wi-Fi IP discovery, Wi-Fi socket binding,
 * strict IP validation, smart failure diagnostics, and device hardware telemetry.
 */
public final class NetworkDiagnostics {

    private static final String TAG = "NetworkDiagnostics";
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    );

    private NetworkDiagnostics() {}

    public static boolean isValidIpv4(String ip) {
        return ip != null && IPV4_PATTERN.matcher(ip.trim()).matches();
    }

    public static boolean isLocalLanIp(String ip) {
        if (!isValidIpv4(ip)) return false;
        return ip.startsWith("192.168.") || ip.startsWith("10.") || is172Private(ip);
    }

    private static boolean is172Private(String ip) {
        if (!ip.startsWith("172.")) return false;
        try {
            String[] parts = ip.split("\\.");
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Finds the best local IPv4 address for Wi-Fi Hotspot (Host or Client).
     * Prioritizes AP/Hotspot interfaces (ap0, softap, swlan, wlan1) and 192.168.43.x / 192.168.x.x.
     */
    public static String getBestLocalIpAddress() {
        List<String> candidates = getAllLocalIpv4Addresses();
        if (candidates.isEmpty()) {
            return "0.0.0.0";
        }
        // Prefer 192.168.43.1 (Hotspot host) or 192.168.43.x (Hotspot client)
        for (String ip : candidates) {
            if (ip.startsWith("192.168.43.")) return ip;
        }
        // Then any 192.168.x.x LAN IP
        for (String ip : candidates) {
            if (ip.startsWith("192.168.")) return ip;
        }
        return candidates.get(0);
    }

    public static List<String> getAllLocalIpv4Addresses() {
        List<String> priorityIps = new ArrayList<>();
        List<String> otherIps = new ArrayList<>();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                String name = intf.getName().toLowerCase();
                Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                while (enumIpAddr.hasMoreElements()) {
                    InetAddress addr = enumIpAddr.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip == null) continue;
                        if (name.contains("ap") || name.contains("softap") || name.contains("swlan") || name.contains("wlan")) {
                            priorityIps.add(ip);
                        } else if (!name.contains("rmnet") && !name.contains("ccmni")) {
                            otherIps.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to enumerate interfaces: " + e.getMessage());
        }
        priorityIps.addAll(otherIps);
        return priorityIps;
    }

    /**
     * Returns an active Wi-Fi Network so local sockets can bypass cellular routing when offline.
     */
    public static Network findWifiNetwork(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return network;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findWifiNetwork error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Creates a Socket bound to the Wi-Fi network (if available and target is a local LAN IP)
     * so offline Hotspot connections never accidentally route through Mobile Data.
     */
    public static Socket createBoundLocalSocket(Context context, String peerIp, int port, int timeoutMs) throws IOException {
        Socket socket = null;
        if (isLocalLanIp(peerIp)) {
            Network wifiNetwork = findWifiNetwork(context);
            if (wifiNetwork != null) {
                try {
                    socket = wifiNetwork.getSocketFactory().createSocket();
                    socket.connect(new InetSocketAddress(peerIp, port), timeoutMs);
                    return socket;
                } catch (IOException e) {
                    Log.w(TAG, "Wi-Fi bound socket connect failed, trying standard socket: " + e.getMessage());
                    try {
                        if (socket != null) socket.close();
                    } catch (IOException ignored) {}
                    // Fall through to standard socket (needed when this device is the Hotspot Host and wifiNetwork is null/different)
                }
            }
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(peerIp, port), timeoutMs);
        return socket;
    }

    /**
     * Smart diagnostic engine explaining why a call failed or disconnected.
     */
    public static String diagnoseSocketFailure(
            String localIp,
            String peerIp,
            Exception error
    ) {
        if (!isValidIpv4(peerIp)) {
            return "Wrong IP address format";
        }
        if (localIp != null && localIp.equals(peerIp)) {
            return "Cannot call your own device IP (" + localIp + ")";
        }
        if ("0.0.0.0".equals(localIp)) {
            return "Wi-Fi / Hotspot interface offline (Turn on Hotspot or connect to Wi-Fi)";
        }
        if (!sameSubnet24(localIp, peerIp)) {
            return "Subnet mismatch / AP Isolation (My IP: " + localIp + ", Peer: " + peerIp + ")";
        }
        if (error instanceof SocketTimeoutException) {
            return "Socket timeout (AP Isolation enabled or peer device unreachable on port 8888)";
        }
        if (error instanceof ConnectException) {
            String msg = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
            if (msg.contains("econnrefused") || msg.contains("refused")) {
                return "Connection refused (Open My Assistant app on the peer device)";
            }
            if (msg.contains("enetunreach") || msg.contains("ehostunreach")) {
                return "AP Isolation enabled or host unreachable on hotspot";
            }
        }
        return "Socket error: " + (error != null ? error.getMessage() : "Unknown failure");
    }

    /**
     * Smart diagnostic engine explaining why WebRTC ICE failed or disconnected mid-call.
     */
    public static String diagnoseWebRtcFailure(
            String localIp,
            String peerIp,
            int localCandidateCount,
            int localIpv4HostCount,
            int remoteCandidateCount,
            boolean signalingConnected,
            boolean byeReceived
    ) {
        if (byeReceived) {
            return "Peer ended the call";
        }
        if (!signalingConnected) {
            return "TCP Signaling socket dropped unexpectedly";
        }
        if ("0.0.0.0".equals(localIp)) {
            return "Wi-Fi / Hotspot disconnected during call";
        }
        if (localCandidateCount == 0 || localIpv4HostCount == 0) {
            return "ICE Candidate gathering failed (No local hotspot IPv4 candidates found)";
        }
        if (remoteCandidateCount == 0) {
            return "ICE Negotiation failed (0 remote ICE candidates received from peer)";
        }
        if (peerIp != null && !peerIp.isEmpty() && !sameSubnet24(localIp, peerIp)) {
            return "AP Isolation enabled or subnet mismatch (" + localIp + " vs " + peerIp + ")";
        }
        return "AP Isolation enabled or UDP media blocked by hotspot firewall";
    }

    private static boolean sameSubnet24(String ip1, String ip2) {
        if (!isValidIpv4(ip1) || !isValidIpv4(ip2)) return false;
        String prefix1 = ip1.substring(0, ip1.lastIndexOf('.'));
        String prefix2 = ip2.substring(0, ip2.lastIndexOf('.'));
        return prefix1.equals(prefix2);
    }

    /**
     * Collects live device telemetry for Device Status Check.
     */
    public static String collectDeviceStatus(
            Context context,
            String localIp,
            boolean torchOn,
            String iceState,
            int activePeers
    ) {
        int batteryPct = -1;
        boolean charging = false;
        try {
            Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    batteryPct = (int) ((level * 100f) / scale);
                }
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Exception ignored) {}

        boolean adminActive = false;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComp = new ComponentName(context, MyAdminReceiver.class);
            adminActive = dpm != null && dpm.isAdminActive(adminComp);
        } catch (Exception ignored) {}

        Network wifiNet = findWifiNetwork(context);
        List<String> allIps = getAllLocalIpv4Addresses();

        return "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")\n"
                + "Battery: " + (batteryPct >= 0 ? batteryPct + "%" : "Unknown") + (charging ? " (Charging)" : "") + "\n"
                + "Primary IP: " + localIp + ":8888\n"
                + "All Local IPs: " + (allIps.isEmpty() ? "None" : String.join(", ", allIps)) + "\n"
                + "Wi-Fi Transport: " + (wifiNet != null ? "Connected (Bound)" : "Hotspot Host / Offline") + "\n"
                + "Torch / Flash: " + (torchOn ? "ON" : "OFF") + "\n"
                + "Screen Lock Admin: " + (adminActive ? "Active" : "Not Enabled") + "\n"
                + "Active Peers: " + activePeers + " | ICE: " + iceState;
    }
}
