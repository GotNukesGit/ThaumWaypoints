package com.zircaloylabs.viswaypoints.keys;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;

public final class KeyBindings {

    public static final String CATEGORY = "key.categories.viswaypoints";

    /** Plan a refill run and drop waypoints to the chosen nodes. */
    public static final KeyBinding route = new KeyBinding("key.viswaypoints.route", Keyboard.KEY_V, CATEGORY);

    /** Remove every waypoint this mod created, whether or not the wand is full. */
    public static final KeyBinding clear = new KeyBinding("key.viswaypoints.clear", Keyboard.KEY_NONE, CATEGORY);

    private KeyBindings() {}

    public static void init() {
        ClientRegistry.registerKeyBinding(route);
        ClientRegistry.registerKeyBinding(clear);
    }
}
