package com.zircaloylabs.viswaypoints.config.gui;

import com.zircaloylabs.viswaypoints.VisWaypoints;
import com.zircaloylabs.viswaypoints.config.VWConfig;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Applies config changes as soon as the in-game screen is closed, with no restart. */
public class ConfigChangeListener {

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (VisWaypoints.MODID.equals(event.modID)) {
            VWConfig.load();
        }
    }
}
