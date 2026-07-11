package com.zircaloylabs.viswaypoints.render;

import com.zircaloylabs.viswaypoints.node.KnownNode;

/**
 * The node the player is currently being sent to, shared between the run logic and the renderer.
 *
 * Deliberately a tiny static holder: the render thread needs to know the target every frame, and
 * threading a reference from the session through Forge's event bus for that would be more machinery
 * than the problem deserves. Null means no run is active and nothing should be drawn.
 */
public final class ActiveTarget {

    private static volatile KnownNode target;

    private ActiveTarget() {}

    public static void set(KnownNode node) {
        target = node;
    }

    public static void clear() {
        target = null;
    }

    public static KnownNode get() {
        return target;
    }
}
