package com.bluedream.lottery;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;

public class ExplosionListener implements Listener {
    private final BlueDreamLottery plugin;
    private final GlobalListener globalListener;

    public ExplosionListener(BlueDreamLottery plugin, GlobalListener globalListener) {
        this.plugin = plugin;
        this.globalListener = globalListener;
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        globalListener.handleExplosion(event.blockList());
    }
}
