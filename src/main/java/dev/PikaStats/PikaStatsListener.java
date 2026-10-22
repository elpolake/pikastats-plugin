package dev.PikaStats;

import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.Proxy2ServerChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.types.ITyped;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

final class PikaStatsListener {
    private final PikaStatsApi api;
    private final LocalWebSocketServer websocket;
    private final BedWarsMode fallbackMode;

    PikaStatsListener(PikaStatsApi api, LocalWebSocketServer websocket, BedWarsMode fallbackMode) {
        this.api = api;
        this.websocket = websocket;
        this.fallbackMode = fallbackMode;
    }

    @EventHandler
    public void onProxy2ServerChannelInitialize(Proxy2ServerChannelInitializeEvent event) {
        if (event.getType() != ITyped.Type.POST || event.isLegacyPassthrough()) {
            return;
        }

        Channel channel = event.getChannel();
        if (channel == null) {
            return;
        }

        ProxyConnection connection = ProxyConnection.fromChannel(channel);
        if (connection == null) {
            return;
        }

        for (PacketHandler packetHandler : connection.getPacketHandlers()) {
            if (packetHandler instanceof PikaStatsPacketHandler) {
                return;
            }
        }

        PikaStatsOverlayTracker overlayTracker = new PikaStatsOverlayTracker(this.api, this.websocket, this.fallbackMode);
        connection.getPacketHandlers().add(new PikaStatsPacketHandler(connection, this.api, overlayTracker));
    }
}
