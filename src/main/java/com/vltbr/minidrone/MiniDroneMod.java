package com.vltbr.minidrone;

import com.vltbr.minidrone.mavlink.MavlinkTransport;
import com.vltbr.minidrone.entity.ModEntityTypes;
import com.vltbr.minidrone.sim.VirtualDroneManager;
import com.vltbr.minidrone.world.TrainingArenaController;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.commands.Commands.literal;

public final class MiniDroneMod implements ModInitializer {
    public static final String MOD_ID = "mini_drone_system_mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private VirtualDroneManager droneManager;
    private TrainingArenaController trainingArenaController;
    private MavlinkTransport mavlinkTransport;

    @Override
    public void onInitialize() {
        ModEntityTypes.initialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("minidrone")
                    .then(literal("status").executes(context -> reportStatus(context.getSource())))
                    .then(literal("origin")
                        .then(literal("set").executes(context -> resetOrigin(context.getSource()))))
                    .then(literal("arena")
                        .then(literal("create")
                            .executes(context -> createArena(context.getSource(), null))
                            .then(net.minecraft.commands.Commands.argument(
                                "center", BlockPosArgument.blockPos())
                                .executes(context -> createArena(
                                    context.getSource(),
                                    BlockPosArgument.getBlockPos(context, "center")))))
                        .then(literal("clear").executes(context -> clearArena(context.getSource()))))
            )
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            droneManager = new VirtualDroneManager(server);
            trainingArenaController = new TrainingArenaController(server);
            mavlinkTransport = new MavlinkTransport(server, droneManager);
            mavlinkTransport.start();
            LOGGER.info("Mini Drone System virtual flight controller started");
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (droneManager != null) {
                droneManager.tick();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (mavlinkTransport != null) {
                mavlinkTransport.stop();
                mavlinkTransport = null;
            }
            if (droneManager != null) {
                droneManager.close();
            }
            droneManager = null;
            trainingArenaController = null;
            LOGGER.info("Mini Drone System virtual flight controller stopped");
        });

        LOGGER.info("Mini Drone System Mod initialized");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private int reportStatus(CommandSourceStack source) {
        if (droneManager == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        var state = droneManager.snapshot();
        String status = String.format(
            "Virtual drone %s: mode=%s, armed=%s, NED=(%.2f, %.2f, %.2f), velocity=(%.2f, %.2f, %.2f) m/s",
            state.droneId(),
            state.guided() ? "GUIDED" : state.customMode() == 9 ? "LAND" : "SAFE",
            state.armed(),
            state.northM(), state.eastM(), state.downM(),
            state.velocityNorthMps(), state.velocityEastMps(), state.velocityDownMps()
        );
        source.sendSuccess(() -> copyableMessage(status), false);
        return 1;
    }

    private int resetOrigin(CommandSourceStack source) {
        if (droneManager == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("This command must be run by a player in the Overworld."));
            return 0;
        }
        VirtualDroneManager.OriginResetResult result = droneManager.resetFlightOrigin(player);
        switch (result) {
            case RESET -> {
                var origin = droneManager.flightOrigin();
                String message = String.format(
                    "Virtual flight origin reset in front of the player: Minecraft=(%.2f, %.2f, %.2f)",
                    origin.originX(), origin.originY(), origin.originZ()
                );
                source.sendSuccess(() -> copyableMessage(message), false);
            }
            case DRONE_ACTIVE -> source.sendFailure(
                Component.literal("Land and disarm the virtual drone before resetting its origin."));
            case WRONG_DIMENSION -> source.sendFailure(
                Component.literal("The virtual flight origin can only be reset in the Overworld."));
        }
        return result == VirtualDroneManager.OriginResetResult.RESET ? 1 : 0;
    }

    private int createArena(CommandSourceStack source, net.minecraft.core.BlockPos requestedCenter) {
        if (trainingArenaController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("This command must be run by a player in the Overworld."));
            return 0;
        }
        var result = trainingArenaController.create(player, requestedCenter);
        switch (result.status()) {
            case CREATED -> source.sendSuccess(() -> copyableMessage(String.format(
                "Training arena created at (%d, %d, %d): placed=%d, skipped=%d",
                result.centerX(), result.topY(), result.centerZ(), result.placed(), result.skipped())), false);
            case ALREADY_EXISTS -> source.sendFailure(
                Component.literal("A training arena is already recorded in this world. Clear it first."));
            case WRONG_DIMENSION -> source.sendFailure(
                Component.literal("The training arena can only be created in the Overworld."));
            case NO_SPACE -> source.sendFailure(
                Component.literal("No air space was available for the training arena."));
            case INVALID_POSITION -> source.sendFailure(
                Component.literal("The training arena center is outside the Overworld build height."));
        }
        return result.status() == TrainingArenaController.CreateStatus.CREATED ? 1 : 0;
    }

    private int clearArena(CommandSourceStack source) {
        if (trainingArenaController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        var result = trainingArenaController.clear();
        if (result.status() == TrainingArenaController.ClearStatus.NOT_FOUND) {
            source.sendFailure(Component.literal("No recorded training arena exists in this world."));
            return 0;
        }
        source.sendSuccess(() -> copyableMessage(String.format(
            "Training arena cleared: removed=%d, preserved_changed=%d",
            result.removed(), result.preserved())), false);
        return 1;
    }

    private static Component copyableMessage(String message) {
        return Component.literal(message)
            .append(Component.literal(" "))
            .append(Component.literal("[COPY]")
                .withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, message))
                    .withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Copy to clipboard")
                    ))));
    }
}
