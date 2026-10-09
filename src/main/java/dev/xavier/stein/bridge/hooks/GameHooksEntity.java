/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.gfx.SteinGameState;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import java.util.List;

/**
 * The entity side of {@link GameHooks}: hiding and ghosting entities, the status, metadata and
 * health packets, and the name above the head. The bodies of the {@code GameHooks} entry points
 * live here.
 *
 * <p>{@code GameHooks} forces this class to initialise from its own static initializer.
 */
final class GameHooksEntity {

    private GameHooksEntity() {}

    /** Forces this class to initialise: {@code GameHooks} calls it from its static initializer. */
    static void init() {}

    /**
     * In place of Entity.handleStatusUpdate in handleEntityStatus (S19). Status 2 is damage (red
     * animation, sway, sound): the listener can cancel the effect — client side only, the damage
     * belongs to the server. Status 3 is death: the listener is informed before the animation. The
     * other statuses go straight through.
     */
    static void entityStatus(Object entity, byte status) {
        if (GameHooks.SINK != null && entity != null) {
            if (status == 2) {
                try {
                    if (GameHooks.SINK.entityHurt(entity)) {
                        return;
                    }
                } catch (Throwable e) {
                    GameHooks.fail("entityHurt", e);
                }
            } else if (status == 3) {
                try {
                    GameHooks.SINK.entityDeath(entity);
                } catch (Throwable e) {
                    GameHooks.fail("entityDeath", e);
                }
            }
        }
        ((Entity) entity).handleStatusUpdate(status);
    }

    /**
     * In place of DataWatcher.updateWatchedObjectsFromList in handleEntityMetadata (S1C): applies,
     * and if the health or absorption of a living entity changed, the listener is informed — this
     * is how other players' health arrives.
     */
    @SuppressWarnings("unchecked")
    static void entityMetadata(Object watcher, Object list) {
        DataWatcher dataWatcher = (DataWatcher) watcher;
        Entity owner = GameHooks.SINK == null ? null : dataWatcher.owner;
        if (!(owner instanceof EntityLivingBase living)) {
            dataWatcher.updateWatchedObjectsFromList((List) list);
            return;
        }
        float h0 = living.getHealth();
        float a0 = living.getAbsorptionAmount();
        dataWatcher.updateWatchedObjectsFromList((List) list);
        healthAfter(living, h0, a0);
    }

    /**
     * In place of EntityPlayerSP.setPlayerSPHealth in handleUpdateHealth (S06): the player's own
     * health.
     */
    static void playerHealth(Object player, float health) {
        EntityPlayerSP self = (EntityPlayerSP) player;
        if (GameHooks.SINK == null) {
            self.setPlayerSPHealth(health);
            return;
        }
        float h0 = self.getHealth();
        float a0 = self.getAbsorptionAmount();
        self.setPlayerSPHealth(health);
        healthAfter(self, h0, a0);
    }

    private static void healthAfter(EntityLivingBase entity, float h0, float a0) {
        float h1 = entity.getHealth();
        float a1 = entity.getAbsorptionAmount();
        if (h1 != h0 || a1 != a0) {
            try {
                GameHooks.SINK.healthChanged(entity, h0, h1, a0, a1);
            } catch (Throwable e) {
                GameHooks.fail("healthChanged", e);
            }
        }
    }

    /**
     * In place of renderEntitySimple(entity, partialTicks) in RenderGlobal.renderEntities: whoever
     * listens can make the entity vanish.
     *
     * <p>It is the whole draw that does not happen — body, armor, name and shadow —, and not a game
     * invisibility (which still shows what the person wears). To whoever is looking, there is
     * nobody there.
     */
    static boolean renderEntity(Object manager, Object entity, float partialTicks) {
        if (hidden(entity)) {
            return false;
        }
        return ((RenderManager) manager).renderEntitySimple((Entity) entity, partialTicks);
    }

    /** The same in the second pass (renderMultipass), for the entities drawn in two. */
    static void renderMultipass(Object manager, Object entity, float partialTicks) {
        if (hidden(entity)) {
            return;
        }
        // renderMultipass: the MCP table (stable_22) names this method renderWitherSkull.
        ((RenderManager) manager).renderWitherSkull((Entity) entity, partialTicks);
    }

    /**
     * In place of {@code isInvisible()} inside RendererLivingEntity.renderModel: a mod's ghost
     * comes in through the same door as the game's invisibility.
     *
     * <p>The game decides there between three paths — draw normally, do not draw, or draw at 15%
     * without writing depth (the ghost it already makes of someone invisible who is on your team).
     * Saying "invisible" here and "but not to you" just below, it is the game itself that draws the
     * ghost, with its own code.
     */
    static boolean ghostInvisible(Object entity) {
        return ghost(entity) || ((Entity) entity).isInvisible();
    }

    /**
     * In place of {@code isInvisibleToPlayer(player)} in the same method: a mod's ghost is visible
     * to me.
     */
    static boolean ghostInvisibleToPlayer(Object entity, Object player) {
        return !ghost(entity) && ((Entity) entity).isInvisibleToPlayer((EntityPlayer) player);
    }

    /**
     * In place of renderLayers(...) in RendererLivingEntity.doRender: the ghost comes out without
     * armor, without a held item and without a cape.
     *
     * <p>Without this it would be the game's, which draws the layers outside the invisibility — and
     * a 15% silhouette with solid armor on top shows nothing of what is behind it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void ghostLayers(
            Object renderer,
            Object entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            float scale) {
        if (ghost(entity)) {
            return;
        }
        ((RendererLivingEntity) renderer)
                .renderLayers(
                        (EntityLivingBase) entity,
                        limbSwing,
                        limbSwingAmount,
                        partialTicks,
                        ageInTicks,
                        netHeadYaw,
                        headPitch,
                        scale);
    }

    /**
     * In place of {@code canRenderOnFire()} inside Render.doRenderShadowAndFire: the ghost does not
     * catch fire on screen.
     *
     * <p>The flames are drawn outside the model, like the armor — without this, a 15% silhouette
     * would appear inside a solid fire, which is the opposite of what the ghost exists to do. It
     * keeps burning for the server and for everything else; it is only the screen.
     */
    static boolean ghostFire(Object entity) {
        return !ghost(entity) && ((Entity) entity).canRenderOnFire();
    }

    /**
     * In place of {@code getWatchableObjectInt(id)} inside EntityLivingBase.updatePotionEffects: a
     * ghost's potion color becomes zero, and without a color the game emits no particles at all.
     *
     * <p>The game already emits fewer particles for someone invisible (one in fifteen, instead of
     * one in two), but "fewer" still marks where the person is. Here it is none. Only in that
     * method: the color still counts for everything else.
     */
    static int ghostPotionColor(Object watcher, int id) {
        DataWatcher dataWatcher = (DataWatcher) watcher;
        Object owner = dataWatcher.owner;
        if (owner != null && ghost(owner)) {
            return 0;
        }
        return dataWatcher.getWatchableObjectInt(id);
    }

    /**
     * In place of {@code setDoRenderBrightness(...)} inside RendererLivingEntity.doRender: the
     * ghost does not flash red when taking damage.
     *
     * <p>It is what tints the whole model before the draw (the hit's red, the creeper's white, the
     * darkening on death) and tells the game to untint afterwards. Answering that it did not tint,
     * there is nothing to untint, and the silhouette comes out in its own color. Taking damage
     * stays the same for everything else.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean ghostHurt(Object renderer, Object entity, float partialTicks) {
        if (ghost(entity)) {
            SteinGameState.brightnessSet(renderer, false);
            return false;
        }
        boolean on =
                ((RendererLivingEntity) renderer)
                        .setDoRenderBrightness((EntityLivingBase) entity, partialTicks);
        // The damage color, for the new renderer.
        SteinGameState.brightnessSet(renderer, on);
        return on;
    }

    /**
     * Does any mod want this entity as a ghost? Hidden wins: whoever vanished does not become a
     * ghost.
     */
    static boolean ghost(Object entity) {
        if (GameHooks.SINK == null || SKIP_GHOST_QUERY) {
            return false;
        }
        // The "hidden" question is **not** asked here, and does not need to be: whoever is hidden
        // does not even reach renderModel — its whole draw was already skipped back in
        // renderEntitySimple. Asking again doubled the round trip through the mods for every entity
        // in every frame, and that is five questions per entity.
        try {
            return GameHooks.SINK.ghostEntity(entity);
        } catch (Throwable e) {
            GameHooks.fail("ghostEntity", e);
            return false;
        }
    }

    /**
     * {@code -Dsteinbridge.diag.noGhost=true}: does not ask the mods whether the entity is a ghost.
     * Only for measuring — it is what isolates, in an A/B comparison, the cost of asking, which
     * happens five times per entity per frame.
     */
    private static final boolean SKIP_GHOST_QUERY = HookOptions.SKIP_GHOST_QUERY;

    /** Is the entity hidden by any mod? Also serves our own passes (entity shadow). */
    static boolean hidden(Object entity) {
        if (GameHooks.SINK == null) {
            return false;
        }
        try {
            return GameHooks.SINK.hideEntity(entity);
        } catch (Throwable e) {
            GameHooks.fail("hideEntity", e);
            return false;
        }
    }

    /**
     * In place of getDisplayName() in RendererLivingEntity.renderName: the name above the head,
     * which the listener can change.
     */
    static Object nametag(Object entity) {
        Object name = ((EntityLivingBase) entity).getDisplayName();
        if (GameHooks.SINK != null && name != null) {
            try {
                Object n = GameHooks.SINK.nametag(entity, name);
                if (n != null) {
                    return n;
                }
            } catch (Throwable e) {
                GameHooks.fail("nametag", e);
            }
        }
        return name;
    }
}
