package io.github.jungm.crema.internal.security;

import java.util.Locale;

/**
 * The one definition of a loopback host: {@code localhost}, an IPv4 address in {@code 127.0.0.0/8}, or the IPv6
 * address {@code ::1}, with or without the brackets that {@link java.net.URI#getHost()} keeps.
 */
public final class Loopback {

    private Loopback() {
    }

    /**
     * @param host a host name or IP address literal, as {@link java.net.URI#getHost()} returns it; may be
     *        {@code null}
     */
    public static boolean isHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("[::1]") || h.equals("::1") || isIpv4Loopback(h);
    }

    private static boolean isIpv4Loopback(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(c -> c >= '0' && c <= '9')
                    || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
