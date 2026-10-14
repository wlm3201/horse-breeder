package com.horsebreeder.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/** The optional Mod Menu entrypoint must also work without YACL installed. */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
            return parent -> null;
        }
        return YaclConfigScreen::create;
    }
}
