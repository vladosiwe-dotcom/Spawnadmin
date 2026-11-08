package com.spawnadmin;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SpawnAdmin.MOD_ID)
public final class SpawnAdmin {
    public static final String MOD_ID = "spawnadmin";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SpawnAdmin(IEventBus modBus) {
        LOGGER.info("Spawn Admin: Heavenly Summoning loading");
    }
}
