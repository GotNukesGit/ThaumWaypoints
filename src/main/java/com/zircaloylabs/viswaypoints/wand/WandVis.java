package com.zircaloylabs.viswaypoints.wand;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.common.items.wands.ItemWandCasting;

/**
 * Reads the player's wand and works out how much vis it is missing.
 *
 * Units matter here and are easy to get wrong. Thaumcraft stores wand vis in "centivis": the NBT tag
 * for each primal holds vis * 100, and ItemWandCasting#getMaxVis returns (rod capacity * 100). But a
 * node holds vis in whole units, and draining transfers whole node-vis (ItemWandCasting#addVis
 * multiplies the amount by 100 before storing it). So to answer "how much node vis do I still need",
 * the centivis deficit must be divided by 100 -- which is what this class exposes.
 */
public final class WandVis {

    /** Thaumcraft stores wand vis multiplied by this factor. */
    public static final int CENTIVIS_PER_VIS = 100;

    private WandVis() {}

    /** The wand the player is holding, or null if they aren't holding one. */
    public static ItemStack heldWand() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return null;

        final EntityPlayer player = mc.thePlayer;
        final ItemStack held = player.getCurrentEquippedItem();

        if (held == null || !(held.getItem() instanceof ItemWandCasting)) return null;
        return held;
    }

    /**
     * How much vis, in whole node-vis units, the wand is short of full for each primal aspect.
     * Aspects that are already full are omitted, so an empty result means "wand is topped up".
     */
    public static Map<Aspect, Integer> deficit(ItemStack wandStack) {
        final Map<Aspect, Integer> out = new LinkedHashMap<>();
        if (wandStack == null) return out;

        final ItemWandCasting wand = (ItemWandCasting) wandStack.getItem();
        final int maxCentivis = wand.getMaxVis(wandStack);

        for (Aspect primal : Aspect.getPrimalAspects()) {
            final int currentCentivis = wand.getVis(wandStack, primal);
            final int missingCentivis = maxCentivis - currentCentivis;
            if (missingCentivis <= 0) continue;

            // Round up: needing 150 centivis means you must still pull 2 whole vis from a node.
            final int missingVis = (missingCentivis + CENTIVIS_PER_VIS - 1) / CENTIVIS_PER_VIS;
            if (missingVis > 0) out.put(primal, missingVis);
        }

        return out;
    }

    /** True when every primal on the wand is at capacity. */
    public static boolean isFull(ItemStack wandStack) {
        return deficit(wandStack).isEmpty();
    }

    /** Current charge as a fraction of capacity across all six primals, for display. */
    public static float fillFraction(ItemStack wandStack) {
        if (wandStack == null) return 0f;

        final ItemWandCasting wand = (ItemWandCasting) wandStack.getItem();
        final int maxCentivis = wand.getMaxVis(wandStack);
        if (maxCentivis <= 0) return 1f;

        int current = 0;
        int capacity = 0;

        for (Aspect primal : Aspect.getPrimalAspects()) {
            current += Math.min(wand.getVis(wandStack, primal), maxCentivis);
            capacity += maxCentivis;
        }

        return capacity == 0 ? 1f : (float) current / (float) capacity;
    }
}
