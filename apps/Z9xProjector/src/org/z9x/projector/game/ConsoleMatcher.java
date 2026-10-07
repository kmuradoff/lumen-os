package org.z9x.projector.game;

import android.hardware.hdmi.HdmiDeviceInfo;

import java.util.List;
import java.util.Locale;

/**
 * MODULE "game" (v6.2). Recognises a game console among the HDMI-CEC devices that system_server's
 * HdmiControlService knows (HdmiControlManager.getConnectedDevices, SystemApi, permission HDMI_CEC).
 *
 * Rules (research/v62 hdmi-console console_ids.tsv):
 *  - only devices behind the given HDMI port (getPortId: derived from the physical address, so a
 *    console behind an AV receiver still matches), only source-type devices, never our own TV
 *    device (logical address 0, vendor 0x6D746B "mtk", OSD name "XGIMI Z9X");
 *  - PlayStation: OSD name "PS2".."PS5..." or containing "playstation"; or Sony vendor 0x080046
 *    (VERIFIED libcec CEC_VENDOR_SONY) + device type PLAYBACK + empty name (a Sony Blu-ray player
 *    reports a name, so it does not match);
 *  - Xbox: OSD name contains "xbox";
 *  - Nintendo: OSD name contains "nintendo" or is "Switch" / "Switch 2".
 * The Microsoft and Nintendo CEC vendor ids and the exact console OSD names are UNVERIFIED: every
 * device seen is logged (tag Z9xGame) so they can be confirmed on the device. Pure functions.
 */
final class ConsoleMatcher {
    static final int VENDOR_SONY = 0x080046;
    static final int VENDOR_SELF_MTK = 0x6D746B;

    enum Brand { PLAYSTATION, XBOX, NINTENDO }

    static final class Match {
        final Brand brand;
        /** CEC OSD name as reported (may be empty). */
        final String osdName;
        final int vendorId, logicalAddress, physicalAddress;

        Match(Brand brand, String osdName, int vendorId, int logicalAddress, int physicalAddress) {
            this.brand = brand;
            this.osdName = osdName == null ? "" : osdName.trim();
            this.vendorId = vendorId;
            this.logicalAddress = logicalAddress;
            this.physicalAddress = physicalAddress;
        }

        /** Stable key for "once per connection / 30 min" of the Play cue. */
        String key() {
            return brand + ":" + physicalAddress + ":" + osdName;
        }

        @Override
        public String toString() {
            return brand + " '" + osdName + "' vendor=0x" + Integer.toHexString(vendorId)
                    + " la=" + logicalAddress + " pa=0x" + Integer.toHexString(physicalAddress);
        }
    }

    private ConsoleMatcher() {}

    /** First console found behind {@code port}, or null. Never throws. */
    static Match find(List<HdmiDeviceInfo> devices, int port) {
        if (devices == null || port <= 0) return null;
        for (HdmiDeviceInfo d : devices) {
            try {
                Match m = match(d, port);
                if (m != null) return m;
            } catch (Throwable ignored) {
                // a malformed entry never stops the scan
            }
        }
        return null;
    }

    /** True when a CEC device (any kind, except ourselves) is known behind {@code port}. */
    static boolean anyDeviceOn(List<HdmiDeviceInfo> devices, int port) {
        if (devices == null || port <= 0) return false;
        for (HdmiDeviceInfo d : devices) {
            try {
                if (d != null && d.isCecDevice() && d.getPortId() == port && !isSelf(d)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static Match match(HdmiDeviceInfo d, int port) {
        if (d == null || !d.isCecDevice() || d.getPortId() != port || isSelf(d)) return null;
        int type = d.getDeviceType();
        if (!d.isSourceType() && type != HdmiDeviceInfo.DEVICE_PLAYBACK) return null;
        String raw = d.getDisplayName();
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int vendor = d.getVendorId();
        Brand b = null;
        if (name.contains("playstation") || name.matches("^ps[2-9]\\b.*")) {
            b = Brand.PLAYSTATION;
        } else if (name.contains("xbox")) {
            b = Brand.XBOX;
        } else if (name.contains("nintendo") || name.matches("^switch( ?2)?$")) {
            b = Brand.NINTENDO;
        } else if (name.isEmpty() && vendor == VENDOR_SONY && type == HdmiDeviceInfo.DEVICE_PLAYBACK) {
            b = Brand.PLAYSTATION;
        }
        if (b == null) return null;
        return new Match(b, raw, vendor, d.getLogicalAddress(), d.getPhysicalAddress());
    }

    private static boolean isSelf(HdmiDeviceInfo d) {
        return d.getLogicalAddress() == 0 || d.getVendorId() == VENDOR_SELF_MTK
                || d.getDeviceType() == HdmiDeviceInfo.DEVICE_TV;
    }

    /** One log line per device (for confirming the UNVERIFIED ids / names). */
    static String describe(HdmiDeviceInfo d) {
        if (d == null) return "null";
        try {
            return "la=" + d.getLogicalAddress() + " pa=0x" + Integer.toHexString(d.getPhysicalAddress())
                    + " port=" + d.getPortId() + " type=" + d.getDeviceType()
                    + " vendor=0x" + Integer.toHexString(d.getVendorId())
                    + " name='" + d.getDisplayName() + "' power=" + d.getDevicePowerStatus();
        } catch (Throwable t) {
            return String.valueOf(d);
        }
    }
}
