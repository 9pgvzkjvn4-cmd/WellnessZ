package com.wellnessz.gateway.connector.poct;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.IngestSink;

/** Test access to package-private POCT1-A listener functions. */
public final class PoctListenerAccess {

    private PoctListenerAccess() {
    }

    public static byte[] read(InputStream in) throws IOException {
        return PoctListener.readDocument(in, 1 << 20);
    }

    public static byte[] process(IngestSink sink, Clock clock, byte[] doc) {
        GatewayProperties p = new Binder(new MapConfigurationPropertySource(Map.of(
                "wz.gateway.poct[0].id", "poct-dm", "wz.gateway.poct[0].bind-address", "127.0.0.1",
                "wz.gateway.poct[0].port", "0"))).bindOrCreate("wz.gateway", GatewayProperties.class);
        return new PoctListener(p.poct().get(0), sink, clock, false).process(doc);
    }
}
