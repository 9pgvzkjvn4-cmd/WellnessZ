package com.wellnessz.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WellnessZ edge gateway and Device Host agent (E31-F09, E34-HLR-03): one Java 21 application in two roles –
 * {@code GATEWAY} (clinic / outstation industrial PC, Linux) and {@code DEVICE_HOST_AGENT} (vendor PC, Windows service
 * or Linux systemd). Captures device data over MLLP, POCT1-A, folder exports, vendor databases, serial lines, BLE relays
 * and DICOM drop folders, keeps it in an encrypted store-and-forward queue and forwards it to the core ingest API.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    static String version() {
        Package p = GatewayApplication.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "0.1.0-dev" : v;
    }
}
