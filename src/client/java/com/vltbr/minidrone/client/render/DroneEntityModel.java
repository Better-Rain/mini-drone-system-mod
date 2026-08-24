package com.vltbr.minidrone.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vltbr.minidrone.MiniDroneMod;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.texture.OverlayTexture;

public final class DroneEntityModel {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
        MiniDroneMod.id("drone"),
        "main"
    );

    private final ModelPart frame;
    private final ModelPart frontMarker;
    private final ModelPart statusLight;
    private final ModelPart[] rotors;

    public DroneEntityModel(ModelPart root) {
        frame = root.getChild("frame");
        frontMarker = root.getChild("front_marker");
        statusLight = root.getChild("status_light");
        rotors = new ModelPart[] {
            root.getChild("rotor_front_left"),
            root.getChild("rotor_front_right"),
            root.getChild("rotor_rear_left"),
            root.getChild("rotor_rear_right")
        };
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        CubeListBuilder frame = CubeListBuilder.create()
            .texOffs(0, 0).addBox(-4.0F, -1.5F, -2.5F, 8.0F, 3.0F, 5.0F)
            .texOffs(0, 9).addBox(-0.75F, -0.75F, -8.0F, 1.5F, 1.5F, 16.0F)
            .texOffs(0, 12).addBox(-8.0F, -0.75F, -0.75F, 16.0F, 1.5F, 1.5F)
            .texOffs(0, 16).addBox(-6.5F, 1.0F, -6.5F, 1.0F, 3.0F, 1.0F)
            .texOffs(4, 16).addBox(5.5F, 1.0F, -6.5F, 1.0F, 3.0F, 1.0F)
            .texOffs(8, 16).addBox(-6.5F, 1.0F, 5.5F, 1.0F, 3.0F, 1.0F)
            .texOffs(12, 16).addBox(5.5F, 1.0F, 5.5F, 1.0F, 3.0F, 1.0F);
        root.addOrReplaceChild("frame", frame, PartPose.ZERO);

        root.addOrReplaceChild(
            "front_marker",
            CubeListBuilder.create().texOffs(0, 20)
                .addBox(-2.0F, -1.8F, -3.0F, 4.0F, 1.0F, 1.0F),
            PartPose.ZERO
        );
        root.addOrReplaceChild(
            "status_light",
            CubeListBuilder.create().texOffs(0, 22)
                .addBox(-1.0F, -2.0F, 1.5F, 2.0F, 1.0F, 1.0F),
            PartPose.ZERO
        );

        addRotor(root, "rotor_front_left", -6.0F, -6.0F);
        addRotor(root, "rotor_front_right", 6.0F, -6.0F);
        addRotor(root, "rotor_rear_left", -6.0F, 6.0F);
        addRotor(root, "rotor_rear_right", 6.0F, 6.0F);
        return LayerDefinition.create(mesh, 64, 32);
    }

    private static void addRotor(PartDefinition root, String name, float x, float z) {
        root.addOrReplaceChild(
            name,
            CubeListBuilder.create()
                .texOffs(16, 20).addBox(-5.0F, -0.25F, -0.6F, 10.0F, 0.5F, 1.2F)
                .texOffs(16, 22).addBox(-0.6F, -0.25F, -5.0F, 1.2F, 0.5F, 10.0F)
                .texOffs(40, 20).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 1.5F, 2.0F),
            PartPose.offset(x, -1.0F, z)
        );
    }

    public void setRotorAngle(float angle) {
        rotors[0].yRot = angle;
        rotors[1].yRot = -angle;
        rotors[2].yRot = -angle;
        rotors[3].yRot = angle;
    }

    public void render(
        PoseStack poseStack,
        VertexConsumer vertices,
        int packedLight,
        boolean armed
    ) {
        frame.render(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY, 0xFF263238);
        frontMarker.render(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY, 0xFF00BCD4);
        statusLight.render(
            poseStack,
            vertices,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            armed ? 0xFF4CAF50 : 0xFFF44336
        );
        for (ModelPart rotor : rotors) {
            rotor.render(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY, 0xFFB0BEC5);
        }
    }
}

