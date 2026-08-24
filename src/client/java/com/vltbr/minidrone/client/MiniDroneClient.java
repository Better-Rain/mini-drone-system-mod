package com.vltbr.minidrone.client;

import com.vltbr.minidrone.client.render.DroneEntityModel;
import com.vltbr.minidrone.client.render.DroneEntityRenderer;
import com.vltbr.minidrone.entity.ModEntityTypes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class MiniDroneClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(
            DroneEntityModel.LAYER_LOCATION,
            DroneEntityModel::createLayer
        );
        EntityRendererRegistry.register(ModEntityTypes.DRONE, DroneEntityRenderer::new);
    }
}

