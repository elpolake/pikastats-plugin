package dev.PikaStats;

import io.netty.channel.Channel;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viaproxy.plugins.events.Proxy2ServerChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.types.ITyped;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

final class PikaStatsListener {
    private final Services services;

    PikaStatsListener(Services services) {
        this.services = services;
    }

    @EventHandler
    public void onProxy2ServerChannelInitialize(Proxy2ServerChannelInitializeEvent event) {
        if (event.getType() != ITyped.Type.POST || event.isLegacyPassthrough()) {
            return;
        }
        Channel channel = event.getChannel();
        ProxyConnection connection = channel == null ? null : ProxyConnection.fromChannel(channel);
        if (connection == null) {
            return;
        }
        for (PacketHandler handler : connection.getPacketHandlers()) {
            if (handler instanceof PikaStatsPacketHandler) {
                return;
            }
        }
        connection.getPacketHandlers().add(new PikaStatsPacketHandler(connection, this.services));
    }
}
