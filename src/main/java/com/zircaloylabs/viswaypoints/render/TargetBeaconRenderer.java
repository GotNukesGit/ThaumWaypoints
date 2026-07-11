package com.zircaloylabs.viswaypoints.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.renderer.Tessellator;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.node.KnownNode;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Draws an unmissable beacon at the node you're currently being routed to: a pulsing column of light
 * rising out of the node, plus a box around the node itself.
 *
 * A waypoint icon on a map is easy to lose among everything else on screen; a beam you can see from
 * across the landscape is not. It's drawn with the depth test off so it shows *through* terrain --
 * you can see it from the other side of a hill, which is the entire point.
 *
 * This is pure client-side decoration: it reads the target position and draws geometry. It never
 * touches the world.
 */
public class TargetBeaconRenderer {

    /** How far up the beam goes, in blocks. Tall enough to clear terrain and be seen from a distance. */
    private static final double BEAM_HEIGHT = 260.0;

    private static final double BEAM_RADIUS = 0.32;
    private static final double CORE_RADIUS = 0.14;

    /** Full pulse cycle, in milliseconds. */
    private static final double PULSE_PERIOD_MS = 1600.0;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!VWConfig.showBeacon) return;

        final KnownNode target = ActiveTarget.get();
        if (target == null) return;

        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;

        // Only draw the beacon in the dimension the node actually lives in.
        if (mc.theWorld.provider.dimensionId != target.dim) return;

        final EntityClientPlayerMP player = mc.thePlayer;
        final float partialTicks = event.partialTicks;

        // Interpolated camera position, so the beam doesn't jitter against the world as you move.
        final double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        final double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        final double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;

        final int color = VWConfig.beaconColor;
        final float red = ((color >> 16) & 0xFF) / 255f;
        final float green = ((color >> 8) & 0xFF) / 255f;
        final float blue = (color & 0xFF) / 255f;

        // Smooth 0..1 pulse, so the beam breathes rather than flickers.
        final double phase = (System.currentTimeMillis() % (long) PULSE_PERIOD_MS) / PULSE_PERIOD_MS;
        final float pulse = (float) (0.55 + 0.45 * Math.sin(phase * Math.PI * 2.0));

        final double spin = (System.currentTimeMillis() % 8000L) / 8000.0 * 360.0;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        // Put the origin at the node's centre column, with local Y matching world Y.
        GL11.glTranslated(target.x + 0.5 - camX, -camY, target.z + 0.5 - camZ);

        final double baseY = target.y;

        // Outer beam: wide, soft, slowly rotating.
        GL11.glPushMatrix();
        GL11.glTranslated(0, baseY, 0);
        GL11.glRotated(spin, 0, 1, 0);
        drawBeam(BEAM_RADIUS, BEAM_HEIGHT, red, green, blue, 0.22f * pulse);
        GL11.glPopMatrix();

        // Inner core: narrow and bright, counter-rotating so the beam looks alive.
        GL11.glPushMatrix();
        GL11.glTranslated(0, baseY, 0);
        GL11.glRotated(-spin * 1.6, 0, 1, 0);
        drawBeam(CORE_RADIUS, BEAM_HEIGHT, red, green, blue, 0.55f * pulse);
        GL11.glPopMatrix();

        // A box hugging the node itself, so the exact block is unambiguous once you're close.
        drawBox(baseY - 0.15, 1.3, red, green, blue, 0.75f * pulse);

        GL11.glDepthMask(true);
        GL11.glPopAttrib();
        GL11.glPopMatrix();

        // Restore the colour so we don't tint whatever renders after us.
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** A square column of translucent quads. */
    private static void drawBeam(double radius, double height, float r, float g, float b, float alpha) {
        final Tessellator tessellator = Tessellator.instance;

        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_F(r, g, b, alpha);

        // Fade the beam out towards the top so it doesn't end in a hard edge.
        final float topAlpha = alpha * 0.05f;

        addWall(tessellator, -radius, -radius, radius, -radius, height, r, g, b, alpha, topAlpha);
        addWall(tessellator, radius, -radius, radius, radius, height, r, g, b, alpha, topAlpha);
        addWall(tessellator, radius, radius, -radius, radius, height, r, g, b, alpha, topAlpha);
        addWall(tessellator, -radius, radius, -radius, -radius, height, r, g, b, alpha, topAlpha);

        tessellator.draw();
    }

    private static void addWall(Tessellator tessellator, double x1, double z1, double x2, double z2, double height,
        float r, float g, float b, float bottomAlpha, float topAlpha) {

        tessellator.setColorRGBA_F(r, g, b, bottomAlpha);
        tessellator.addVertex(x1, 0, z1);
        tessellator.addVertex(x2, 0, z2);

        tessellator.setColorRGBA_F(r, g, b, topAlpha);
        tessellator.addVertex(x2, height, z2);
        tessellator.addVertex(x1, height, z1);
    }

    /** A cube outline around the node block. */
    private static void drawBox(double y, double size, float r, float g, float b, float alpha) {
        final double half = size / 2.0;
        final double bottom = y;
        final double top = y + size;

        final Tessellator tessellator = Tessellator.instance;

        GL11.glLineWidth(3.0f);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        tessellator.startDrawing(GL11.GL_LINES);
        tessellator.setColorRGBA_F(r, g, b, alpha);

        final double[][] corners = { { -half, -half }, { half, -half }, { half, half }, { -half, half } };

        for (int i = 0; i < 4; i++) {
            final double[] a = corners[i];
            final double[] c = corners[(i + 1) % 4];

            // Bottom ring.
            tessellator.addVertex(a[0], bottom, a[1]);
            tessellator.addVertex(c[0], bottom, c[1]);

            // Top ring.
            tessellator.addVertex(a[0], top, a[1]);
            tessellator.addVertex(c[0], top, c[1]);

            // Vertical edge.
            tessellator.addVertex(a[0], bottom, a[1]);
            tessellator.addVertex(a[0], top, a[1]);
        }

        tessellator.draw();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }
}
