package com.goida.goidajail.registry;

import com.goida.goidajail.GoidaJail;
import com.goida.goidajail.jail.PrisonerData;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Data attachments stored on the player entity. The prisoner state is serialized to NBT and
 * copied on death, so a jailed player who dies (e.g. via {@code /kill}) keeps their sentence.
 *
 * <p>The confiscated inventory itself is NOT an attachment — it lives in {@code JailSavedData}
 * (level storage) so it can be inspected/edited via the confiscation GUI even while the
 * prisoner is offline.
 */
public final class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, GoidaJail.MOD_ID);

    /** Per-player jail state: remaining time, original spawn point, previous game mode. */
    public static final Supplier<AttachmentType<PrisonerData>> PRISONER =
            ATTACHMENTS.register("prisoner", () ->
                    AttachmentType.serializable(PrisonerData::new).copyOnDeath().build());

    private ModAttachments() {}
}
