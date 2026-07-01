package com.goida.goidajail;

import com.goida.goidajail.registry.ModAttachments;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * GoidaJail — a fully server-side jail system for griefers and rule-breakers.
 *
 * <p>Design highlights:
 * <ul>
 *     <li>This mod is NOT required on clients. The "jail baton" is registered by KubeJS (which
 *     is on both sides), and this mod merely looks it up by id ({@code batonItemId}) at runtime
 *     via {@code AttackEntityEvent}. Because KubeJS creates the item without a recipe, it is
 *     uncraftable into anything (vanilla or modded).</li>
 *     <li>The jail dimension is data-driven (datapack JSON) and is synced to clients
 *     automatically.</li>
 *     <li>All prisoner state and the confiscated inventory are stored in NeoForge data
 *     attachments on the player. Because attachments live in the same player {@code .dat}
 *     file as the live inventory, clearing the inventory and writing the backup happen as
 *     a single atomic disk write — a crash can never desync them.</li>
 * </ul>
 *
 * Attachment registration runs on the mod bus; gameplay handlers use the game bus.
 */
@Mod(GoidaJail.MOD_ID)
public final class GoidaJail {

    public static final String MOD_ID = "goidajail";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GoidaJail(IEventBus modBus, ModContainer container) {
        ModAttachments.ATTACHMENTS.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        LOGGER.info("GoidaJail loaded (server-side).");
    }
}
