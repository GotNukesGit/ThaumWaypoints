package com.zircaloylabs.viswaypoints;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.config.gui.ConfigChangeListener;
import com.zircaloylabs.viswaypoints.keys.ClientEvents;
import com.zircaloylabs.viswaypoints.keys.KeyBindings;
import com.zircaloylabs.viswaypoints.node.NodeMemory;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Vis Waypoints: routes you to the nearest Thaumcraft nodes that can refill your wand, and cleans the
 * waypoints up once it's full.
 *
 * Entirely client-side. It reads your wand's NBT, the nodes TCNodeTracker has recorded, and any node
 * tile entities that happen to be loaded, then writes JourneyMap waypoints -- none of which the
 * server needs to know about. acceptableRemoteVersions = "*" so it never blocks connecting to a
 * server that doesn't have it.
 */
@Mod(
    modid = VisWaypoints.MODID,
    name = "Vis Waypoints",
    version = Tags.VERSION,
    guiFactory = "com.zircaloylabs.viswaypoints.config.gui.VWGuiFactory",
    acceptableRemoteVersions = "*",
    dependencies = "required-after:Thaumcraft;after:tcnodetracker;after:journeymap")
public class VisWaypoints {

    public static final String MODID = "viswaypoints";

    public static final Logger LOG = LogManager.getLogger(MODID);

    @SideOnly(Side.CLIENT)
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        VWConfig.init(event.getSuggestedConfigurationFile());
        NodeMemory.init(
            event.getModConfigurationDirectory()
                .toPath());
    }

    @SideOnly(Side.CLIENT)
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        KeyBindings.init();

        final ClientEvents events = new ClientEvents();
        FMLCommonHandler.instance()
            .bus()
            .register(events);
        MinecraftForge.EVENT_BUS.register(events);

        // Applies in-game config edits immediately.
        FMLCommonHandler.instance()
            .bus()
            .register(new ConfigChangeListener());
    }
}
