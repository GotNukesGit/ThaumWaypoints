package com.zircaloylabs.viswaypoints.config.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;

import com.zircaloylabs.viswaypoints.VisWaypoints;
import com.zircaloylabs.viswaypoints.config.VWConfig;

import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;

/**
 * In-game options screen. Note ConfigElement lives in net.minecraftforge.common.config while
 * GuiConfig/IConfigElement live in cpw.mods.fml.client.config -- Forge 1.7.10 splits them.
 */
public class VWConfigGui extends GuiConfig {

    public VWConfigGui(GuiScreen parent) {
        super(parent, elements(), VisWaypoints.MODID, false, false, "Vis Waypoints");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static List<IConfigElement> elements() {
        final List<IConfigElement> list = new ArrayList<>();

        list.addAll(new ConfigElement(VWConfig.configuration().getCategory(VWConfig.CATEGORY_GENERAL))
                .getChildElements());
        list.addAll(new ConfigElement(VWConfig.configuration().getCategory(VWConfig.CATEGORY_DISPLAY))
                .getChildElements());

        return list;
    }
}
