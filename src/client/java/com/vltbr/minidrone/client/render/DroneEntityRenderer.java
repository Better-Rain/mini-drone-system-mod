package com.vltbr.minidrone.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.vltbr.minidrone.entity.DroneEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public final class DroneEntityRenderer extends EntityRenderer<DroneEntity> {
    private static final ResourceLocation WHITE_TEXTURE = ResourceLocation.withDefaultNamespace(
        "textures/entity/shulker/shulker_white.png"
    );

    private final DroneEntityModel model;

    public DroneEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = new DroneEntityModel(context.bakeLayer(DroneEntityModel.LAYER_LOCATION));
        shadowRadius = 0.45F;
        shadowStrength = 0.55F;
    }

    @Override
    public void render(
        DroneEntity entity,
        float entityYaw,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int packedLight
    ) {
        poseStack.pushPose();
        // No lift. Minecraft puts an entity's position at the bottom of its bounding box,
        // and the physics step now places that bottom exactly on whatever the vehicle
        // rests on, so the model's own origin belongs at the block face. The 0.28 that used
        // to be here stacked on top of the controller's half-height offset and left the
        // model hovering about 45 cm above the ground it had landed on.
        poseStack.translate(0.0, 0.0, 0.0);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(entity.getXRot()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(entity.rollDegrees()));
        poseStack.scale(-1.0F, -1.0F, 1.0F);

        float speed = entity.isArmed() ? 2.4F : 0.08F;
        model.setRotorAngle((entity.tickCount + partialTick) * speed);
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE_TEXTURE));
        model.render(poseStack, vertices, packedLight, entity.isArmed());
        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(DroneEntity entity) {
        return WHITE_TEXTURE;
    }
}

