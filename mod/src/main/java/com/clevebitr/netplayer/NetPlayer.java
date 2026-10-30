package com.clevebitr.netplayer;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = NetPlayer.MOD_ID, dist = Dist.CLIENT)
public final class NetPlayer {
    public static final String MOD_ID = "netplayer";

    public NetPlayer(IEventBus modBus) {
        ClientEvents.registerMod(modBus);
        ClientEvents.registerGame();
    }
}
