package com.vltbr.minidrone;

import com.vltbr.minidrone.mavlink.MavlinkTransport;
import com.vltbr.minidrone.mavlink.MavlinkLinkStatus;
import com.vltbr.minidrone.mavlink.MocapFieldMetadata;
import com.vltbr.minidrone.block.ModBlocks;
import com.vltbr.minidrone.entity.ModEntityTypes;
import com.vltbr.minidrone.item.ModItems;
import com.vltbr.minidrone.sim.VehicleModel;
import com.vltbr.minidrone.sim.VirtualDroneManager;
import com.vltbr.minidrone.sim.VirtualSystemSelfTest;
import com.vltbr.minidrone.world.ArenaOrigin;
import com.vltbr.minidrone.world.DronePlacementHook;
import com.vltbr.minidrone.world.FieldMarkerHook;
import com.vltbr.minidrone.world.FieldSelectorStore;
import com.vltbr.minidrone.world.DroneWorldController;
import com.vltbr.minidrone.world.TrainingArenaController;
import com.vltbr.minidrone.world.TrainingArenaLayout;
import com.vltbr.minidrone.world.TrainingFieldController;
import com.vltbr.minidrone.world.WorldScale;
import com.vltbr.minidrone.world.TrainingFieldDefinition;
import com.vltbr.minidrone.world.VirtualMocapSettingsSavedData;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
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
    private TrainingFieldController trainingFieldController;
    private VirtualMocapSettingsSavedData mocapSettings;
    private MavlinkTransport mavlinkTransport;

    @Override
    public void onInitialize() {
        ModEntityTypes.initialize();
        ModBlocks.initialize();
        ModItems.initialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("minidrone")
                    .then(literal("status").executes(context -> reportStatus(context.getSource())))
                    .then(literal("link")
                        .then(literal("status").executes(context -> reportLinkStatus(context.getSource()))))
                    .then(literal("mocap")
                        .then(literal("enable").executes(context -> setMocapEnabled(context.getSource(), true)))
                        .then(literal("disable").executes(context -> setMocapEnabled(context.getSource(), false)))
                        .then(literal("status").executes(context -> reportMocapStatus(context.getSource()))))
                    .then(literal("selftest").executes(context -> runSelfTest(context.getSource())))
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
                        .then(literal("status").executes(context -> reportArenaStatus(context.getSource())))
                        .then(literal("clear").executes(context -> clearArena(context.getSource()))))
                        .then(literal("drone")
                            .then(literal("place")
                                .executes(context -> placeDrone(context.getSource(), null))
                                .then(net.minecraft.commands.Commands.argument(
                                    "pos", BlockPosArgument.blockPos())
                                    .executes(context -> placeDrone(
                                        context.getSource(),
                                        BlockPosArgument.getBlockPos(context, "pos")))))
                            .then(literal("reset").executes(context -> resetDrone(context.getSource()))))
                    .then(literal("field")
                        .then(literal("status").executes(context -> reportFieldStatus(context.getSource())))
                        .then(literal("clear").executes(context -> clearField(context.getSource())))
                        .then(literal("scan")
                            .executes(context -> scanFieldMarkers(context.getSource(), 32))
                            .then(net.minecraft.commands.Commands.argument(
                                "radius",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 48))
                                .executes(context -> scanFieldMarkers(
                                    context.getSource(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                        context, "radius")))))
                        .then(literal("physics")
                            .executes(context -> reportPhysics(context.getSource()))
                            .then(literal("reset").executes(context -> resetPhysics(context.getSource())))
                            .then(literal("set")
                                .then(net.minecraft.commands.Commands.argument(
                                    "name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .then(net.minecraft.commands.Commands.argument(
                                        "value", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                        .executes(context -> setPhysics(
                                            context.getSource(),
                                            com.mojang.brigadier.arguments.StringArgumentType.getString(
                                                context, "name"),
                                            com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(
                                                context, "value")))))))
                        .then(literal("set")
                            .then(literal("selected")
                                .executes(context -> setFieldFromSelector(context.getSource())))
                            .then(literal("corners")
                                .then(net.minecraft.commands.Commands.argument(
                                    "first", BlockPosArgument.blockPos())
                                    .then(net.minecraft.commands.Commands.argument(
                                        "second", BlockPosArgument.blockPos())
                                        .executes(context -> setFieldCorners(
                                            context.getSource(),
                                            BlockPosArgument.getBlockPos(context, "first"),
                                            BlockPosArgument.getBlockPos(context, "second"))))))
                            .then(literal("center")
                                .then(net.minecraft.commands.Commands.argument(
                                    "center", BlockPosArgument.blockPos())
                                    .then(net.minecraft.commands.Commands.argument(
                                        "width", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 256))
                                        .then(net.minecraft.commands.Commands.argument(
                                            "depth", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 256))
                                            .executes(context -> setFieldCenter(
                                                context.getSource(),
                                                BlockPosArgument.getBlockPos(context, "center"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                                    context, "width"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                                    context, "depth"))))))))
                        .then(literal("origin")
                            .then(literal("center").executes(context ->
                                setFieldOrigin(context.getSource(), TrainingFieldDefinition.OriginMode.CENTRE, 0.0, 0.0)))
                            .then(literal("corner").executes(context ->
                                setFieldOrigin(context.getSource(), TrainingFieldDefinition.OriginMode.CORNER, 0.0, 0.0)))
                            .then(literal("at")
                                .then(net.minecraft.commands.Commands.argument(
                                    "point", BlockPosArgument.blockPos())
                                    .executes(context -> {
                                        var point = BlockPosArgument.getBlockPos(context, "point");
                                        return setFieldOrigin(
                                            context.getSource(),
                                            TrainingFieldDefinition.OriginMode.EXPLICIT,
                                            point.getX() + 0.5,
                                            point.getZ() + 0.5);
                                    })))))
            )
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            trainingArenaController = new TrainingArenaController(server);
            trainingFieldController = new TrainingFieldController(server, trainingArenaController);
            // Marker blocks are registered statically and cannot hold the per-world
            // controller, so they report through this bridge while a world runs.
            FieldMarkerHook.install(trainingFieldController);
            trainingFieldController.setChangeListener(this::publishFieldMetadata);
            DronePlacementHook.install(droneManager);
            droneManager = new VirtualDroneManager(server, trainingFieldController);
            mocapSettings = server.overworld().getDataStorage().computeIfAbsent(
                VirtualMocapSettingsSavedData.factory(),
                VirtualMocapSettingsSavedData.DATA_ID
            );
            mavlinkTransport = new MavlinkTransport(server, droneManager);
            if (mocapSettings.enabled()) {
                mavlinkTransport.setMocapEnabled(true);
            }
            publishFieldMetadata();
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
            FieldMarkerHook.uninstall(trainingFieldController);
            trainingFieldController.setChangeListener(null);
            DronePlacementHook.uninstall(droneManager);
            trainingFieldController = null;
            mocapSettings = null;
            LOGGER.info("Mini Drone System virtual flight controller stopped");
        });

        LOGGER.info("Mini Drone System Mod initialized");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Advertises the training arena in the motion-capture health beacon.
     *
     * <p>The main project draws its field from these values, and only trusts a
     * field centred on the controller's local NED origin - which is exactly what
     * {@link DroneWorldController} makes the arena centre. With no arena there is
     * nothing to advertise, so the metadata is cleared and the backend keeps its
     * own field: claiming a 13 m arena the drone is not actually flying in would
     * put the whole scene in the wrong place.
     */
    private void publishFieldMetadata() {
        if (mavlinkTransport == null) {
            return;
        }
        var field = trainingFieldController == null ? null : trainingFieldController.definition();
        if (field == null) {
            mavlinkTransport.setFieldMetadata(null);
            return;
        }
        double[] offset = field.centerOffsetM();
        // Metres, not blocks: the monitoring side and every backend limit are in SI, and the
        // scale is what turns a 49-block arena into the 12.25 m room it is meant to be.
        double[] sizeM = field.sizeM();
        double scale = WorldScale.metresPerBlock();
        mavlinkTransport.setFieldMetadata(new MocapFieldMetadata(
            sizeM[0],
            sizeM[1],
            field.isCentred(),
            MocapFieldMetadata.CURRENT_PROTOCOL_VERSION,
            System.currentTimeMillis() * 1000L,
            offset[0] * scale,
            offset[1] * scale
        ));
        LOGGER.info(
            "Advertising training field {}x{} m ({},{} blocks, {} m/block) centred_at_origin={} center_offset=({}, {}) origin=({}, {}, {})",
            sizeM[0],
            sizeM[1],
            field.widthM(),
            field.depthM(),
            scale,
            field.isCentred(),
            offset[0],
            offset[1],
            field.originX(),
            field.topY() + ArenaOrigin.PAD_SURFACE_OFFSET_M,
            field.originZ()
        );
    }

    /**
     * Carries the drone to a point in the world (the player's own block by default).
     *
     * <p>Separate from the field commands on purpose: changing the field moves the
     * origin and leaves the vehicle alone, while this moves the vehicle and leaves the
     * field alone.
     */
    private int placeDrone(CommandSourceStack source, BlockPos target) {
        if (droneManager == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        final BlockPos resolved;
        if (target != null) {
            resolved = target;
        } else {
            try {
                resolved = source.getPlayerOrException().blockPosition();
            } catch (Exception exception) {
                source.sendFailure(Component.literal(
                    "Run this as a player or pass a position: /minidrone drone place <pos>"));
                return 0;
            }
        }
        var result = droneManager.placeAt(
            resolved.getX() + 0.5, resolved.getY(), resolved.getZ() + 0.5);
        switch (result) {
            case PLACED -> {
                source.sendSuccess(() -> copyableMessage(String.format(
                    "Virtual drone placed at (%d, %d, %d); the field and its origin did not move",
                    resolved.getX(), resolved.getY(), resolved.getZ())), false);
                return 1;
            }
            case DRONE_ARMED -> {
                source.sendFailure(Component.literal(
                    "Land and disarm the virtual drone before placing it."));
                return 0;
            }
            default -> {
                source.sendFailure(Component.literal(
                    "No training field is defined yet, so the drone has no origin to be placed relative to."));
                return 0;
            }
        }
    }

    /** Puts the drone back on the field origin: the explicit reset. */
    private int resetDrone(CommandSourceStack source) {
        if (droneManager == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("This command needs a player to reset the origin for."));
            return 0;
        }
        var result = droneManager.resetFlightOrigin(player);
        switch (result) {
            case RESET -> {
                source.sendSuccess(() -> copyableMessage(
                    "Virtual drone reset to the field origin: LOCAL_POSITION_NED (0, 0, 0)"), false);
                return 1;
            }
            case DRONE_ACTIVE -> {
                source.sendFailure(Component.literal(
                    "Land and disarm the virtual drone before resetting its origin."));
                return 0;
            }
            default -> {
                source.sendFailure(Component.literal(
                    "The virtual flight origin can only be reset in the Overworld."));
                return 0;
            }
        }
    }

    /**
     * What the vehicle is made of: mass, thrust, drag, lag, limits, contact behaviour.
     *
     * <p>Everything that decides how the vehicle feels is here rather than in the code,
     * so an operator can fly a heavy quad or a nimble one, make the gates narrow, or
     * soften the crash threshold, without editing Java.
     */
    private int reportPhysics(CommandSourceStack source) {
        if (droneManager == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        VehicleModel model = droneManager.primaryDrone().vehicleModel();
        source.sendSuccess(() -> copyableMessage(
            "Virtual airframe:" + model.describe()
                + "\n  /minidrone physics set <name> <value>, /minidrone physics reset"), false);
        return 1;
    }

    private int setPhysics(CommandSourceStack source, String name, double value) {
        if (droneManager == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        VehicleModel current = droneManager.primaryDrone().vehicleModel();
        try {
            VehicleModel updated = current.with(name, value);
            droneManager.primaryDrone().setVehicleModel(updated);
            LOGGER.info("Virtual airframe {} set to {}", name, value);
            source.sendSuccess(() -> copyableMessage(String.format(
                "%s = %s. Derived now: max thrust %.3f N, hover throttle %.0f%%, top speed %.2f m/s",
                name,
                String.valueOf(value),
                updated.maxThrustN(),
                updated.hoverThrottle() * 100.0,
                updated.topSpeedMps())), false);
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(
                exception.getMessage() + ". Known parameters: "
                    + String.join(", ", VehicleModel.parameterNames())));
            return 0;
        }
    }

    private int resetPhysics(CommandSourceStack source) {
        if (droneManager == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        VehicleModel restored = VehicleModel.fromProperties(System.getProperties());
        droneManager.primaryDrone().setVehicleModel(restored);
        source.sendSuccess(() -> copyableMessage(
            "Virtual airframe reset to its defaults" + restored.describe()), false);
        return 1;
    }

    private int reportFieldStatus(CommandSourceStack source) {
        if (trainingFieldController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        source.sendSuccess(() -> copyableMessage(trainingFieldController.describe()), false);
        return trainingFieldController.hasField() ? 1 : 0;
    }

    /**
     * Defines the field by two opposite corners. The lower of the two Y values
     * becomes the surface layer, so a corner placed on the ground and one placed on
     * the wall still describe the floor the drone lands on.
     */
    private int setFieldCorners(CommandSourceStack source, BlockPos first, BlockPos second) {
        if (trainingFieldController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        if (first.getY() != second.getY() && Math.abs(first.getY() - second.getY()) > 2) {
            source.sendFailure(Component.literal(
                "The two corners are more than 2 blocks apart in Y; a field is one flat layer."));
            return 0;
        }
        try {
            var field = trainingFieldController.defineFromCorners(first, second);
            applyFieldChange(source);
            source.sendSuccess(() -> copyableMessage(
                "Training field set from corners: " + field.describe()), false);
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Cannot define that field: " + exception.getMessage()));
            return 0;
        }
    }

    private int setFieldCenter(CommandSourceStack source, BlockPos center, int widthM, int depthM) {
        if (trainingFieldController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        try {
            var field = trainingFieldController.defineFromCentreAndSize(center, widthM, depthM);
            applyFieldChange(source);
            source.sendSuccess(() -> copyableMessage(
                String.format(
                    "Training field set to %d x %d m around (%d, %d, %d): %s",
                    widthM, depthM, center.getX(), center.getY(), center.getZ(), field.describe())),
                false);
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Cannot define that field: " + exception.getMessage()));
            return 0;
        }
    }

    private int setFieldOrigin(
        CommandSourceStack source,
        TrainingFieldDefinition.OriginMode mode,
        double explicitX,
        double explicitZ
    ) {
        if (trainingFieldController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        var updated = trainingFieldController.setOrigin(mode, explicitX, explicitZ);
        if (updated == null) {
            source.sendFailure(Component.literal(
                "No training field is defined yet; set one first (see /minidrone field status)."));
            return 0;
        }
        applyFieldChange(source);
        source.sendSuccess(() -> copyableMessage(
            "Training field origin set: " + trainingFieldController.summary()
                + " (LOCAL_POSITION_NED (0,0,0) moved; the field itself did not)"), false);
        return 1;
    }

    /**
     * Turns the two points the selector item recorded into a field. The points are
     * session state in the item, so this is the step that makes the measurement part
     * of the world.
     */
    private int setFieldFromSelector(CommandSourceStack source) {
        if (trainingFieldController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal(
                "This command measures the field the player selected; run it as a player."));
            return 0;
        }
        var points = FieldSelectorStore.points(player.getUUID());
        if (points.size() < FieldSelectorStore.MAX_POINTS) {
            source.sendFailure(Component.literal(
                "Record two opposite corners with the field selector item first (right-click a block twice)."));
            return 0;
        }
        int[] first = points.get(0);
        int[] second = points.get(1);
        try {
            var field = trainingFieldController.defineFromCorners(
                new BlockPos(first[0], first[1], first[2]),
                new BlockPos(second[0], second[1], second[2]),
                TrainingFieldDefinition.Source.SELECTOR
            );
            applyFieldChange(source);
            source.sendSuccess(() -> copyableMessage(
                "Training field set from the selector: " + field.describe()), false);
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Cannot define that field: " + exception.getMessage()));
            return 0;
        }
    }

    /**
     * Rebuilds the field from the marker blocks around the player. This is the way
     * back for markers the registry never saw (placed by another tool) and the way to
     * deliberately switch a typed-in field over to the markers.
     */
    private int scanFieldMarkers(CommandSourceStack source, int radius) {
        if (trainingFieldController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal(
                "The marker sweep searches around a player; run it as a player."));
            return 0;
        }
        var field = trainingFieldController.scanMarkers(player, radius);
        if (field == null) {
            source.sendFailure(Component.literal(
                "No field could be derived: place two field_corner blocks first (diagonal corners), "
                    + "and optionally a field_center block for the origin. "
                    + trainingFieldController.describeMarkers()));
            return 0;
        }
        applyFieldChange(source);
        source.sendSuccess(() -> copyableMessage(
            "Training field derived from markers: " + field.describe()
                + " (" + trainingFieldController.describeMarkers() + ")"), false);
        return 1;
    }

    private int clearField(CommandSourceStack source) {        if (trainingFieldController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        if (!trainingFieldController.clearManual()) {
            source.sendFailure(Component.literal("No hand-made training field is defined."));
            return 0;
        }
        applyFieldChange(source);
        source.sendSuccess(() -> copyableMessage(
            "Hand-made training field cleared. " + trainingFieldController.describe()), false);
        return 1;
    }

    /**
     * Everything that changes the field has the same three consequences: the beacon
     * advertises something new, the virtual origin moves, and the drone has to be
     * re-placed so the pilot sees it where the new origin says it is.
     */
    /**
     * A field change publishes itself: the controller notifies the transport whenever
     * the field in effect changes, whichever path changed it (command, marker block,
     * selector or sweep). What used to happen here - re-placing the drone - is gone on
     * purpose: moving field data must not teleport the vehicle, because the operator
     * can also place it by hand. Use /minidrone drone reset to put it back on the
     * origin.
     */
    private void applyFieldChange(CommandSourceStack source) {
        // Nothing beyond what the controller already published; kept as the single
        // place the field commands call so the behaviour is documented once.
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

    private int reportLinkStatus(CommandSourceStack source) {
        if (mavlinkTransport == null) {
            source.sendFailure(Component.literal("MAVLink transport is not running."));
            return 0;
        }
        MavlinkLinkStatus status = mavlinkTransport.status();
        long now = System.currentTimeMillis();
        String lastRx = status.lastInboundAtMs() == 0
            ? "never"
            : (Math.max(0L, now - status.lastInboundAtMs()) + "ms ago");
        String lastTx = status.lastOutboundAtMs() == 0
            ? "never"
            : (Math.max(0L, now - status.lastOutboundAtMs()) + "ms ago");
        String message = String.format(
            "MAVLink link: state=%s, remote=%s:%d, local=127.0.0.1:%d, "
                + "backend_fresh=%s, rx_packets=%d, rx_frames=%d, tx_frames=%d, "
                + "last_rx=%s, last_tx=%s, mocap_health=%s, mocap_beacons=%d, "
                + "mocap_control=%s, mocap_expected_id=%s, mocap_forwarding=%s",
            status.state(),
            status.remoteHost(), status.remotePort(), status.localPort(),
            status.backendFresh(now), status.receivedPackets(), status.receivedFrames(),
            status.transmittedFrames(), lastRx, lastTx,
            status.mocapHealthEnabled(),
            // The count is what separates "the mod is not sending" from
            // "the backend is not consuming" when no health state shows up.
            status.healthBeaconsSent(),
            formatMocapControlStatus(status),
            status.mocapExpectedDroneId(),
            status.forwardingHeld() ? "held" : "forwarding"
        );
        source.sendSuccess(() -> copyableMessage(message), false);
        return status.state() == MavlinkLinkStatus.LinkState.DOWN ? 0 : 1;
    }

    private int setMocapEnabled(CommandSourceStack source, boolean enabled) {
        if (mavlinkTransport == null || mocapSettings == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        mocapSettings.setEnabled(enabled);
        mavlinkTransport.setMocapEnabled(enabled);
        String action = enabled ? "enabled" : "disabled";
        source.sendSuccess(() -> mocapStatusMessage(action), false);
        return 1;
    }

    private int reportMocapStatus(CommandSourceStack source) {
        if (mavlinkTransport == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        MavlinkLinkStatus status = mavlinkTransport.status();
        source.sendSuccess(() -> mocapStatusMessage(
            status.mocapHealthEnabled() ? "enabled" : "disabled"), false);
        return 1;
    }

    private Component mocapStatusMessage(String action) {
        MavlinkLinkStatus status = mavlinkTransport.status();
        String state = status.mocapHealthEnabled() ? "enabled" : "disabled";
        MutableComponent message = Component.literal(
            "Virtual mocap: " + state + " (control " + formatMocapControlStatus(status)
                + ", advertised_id=" + status.mocapExpectedDroneId()
                + ", forwarding=" + (status.forwardingHeld()
                    ? "HELD (new setpoints are ignored)"
                    : "running")
                + ") "
                + "[" + action + "]"
        );
        return message
            .append(Component.literal(" "))
            .append(commandButton("ENABLE", "/minidrone mocap enable", status.mocapHealthEnabled()))
            .append(Component.literal(" "))
            .append(commandButton("DISABLE", "/minidrone mocap disable", !status.mocapHealthEnabled()));
    }

    private static Component commandButton(String label, String command, boolean selected) {
        return Component.literal("[" + label + "]")
            .withStyle(style -> style
                .withColor(selected ? ChatFormatting.GREEN : ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Run " + command)
                )));
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
            case CREATED -> {
                // The origin rule now follows the arena, and the field it publishes
                // has to match what the drone will actually fly in.
                publishFieldMetadata();
                droneManager.resetFlightOrigin(player);
                var created = trainingArenaController.info();
                source.sendSuccess(() -> copyableMessage(String.format(
                    "Training arena created at (%d, %d, %d): %dx%d m, placed=%d, skipped=%d",
                    result.centerX(), result.topY(), result.centerZ(),
                    created.widthM(), created.depthM(),
                    result.placed(), result.skipped())), false);
            }
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
        // No arena means no field to advertise: the drone keeps flying in front of
        // the player and the backend has to keep its own field.
        publishFieldMetadata();
        try {
            droneManager.resetFlightOrigin(source.getPlayerOrException());
        } catch (Exception exception) {
            // Console or command-block invocation: the next world load picks up the
            // player-relative rule on its own.
        }
        source.sendSuccess(() -> copyableMessage(String.format(
            "Training arena cleared: removed=%d, preserved_changed=%d",
            result.removed(), result.preserved())), false);
        return 1;
    }

    private int runSelfTest(CommandSourceStack source) {
        VirtualSystemSelfTest.Report report = VirtualSystemSelfTest.run();
        Component message = copyableMessage(report.summary());
        if (report.successful()) {
            source.sendSuccess(() -> message, false);
            return 1;
        }
        source.sendFailure(message);
        return 0;
    }

    private int reportArenaStatus(CommandSourceStack source) {
        if (trainingArenaController == null) {
            source.sendFailure(Component.literal("Mini Drone System is not running in a world."));
            return 0;
        }
        var info = trainingArenaController.info();
        if (!info.present()) {
            source.sendFailure(Component.literal("No recorded training arena exists in this world."));
            return 0;
        }
        source.sendSuccess(() -> copyableMessage(String.format(
            "Training arena center=(%d, %d, %d), size=%dx%d m, recorded_blocks=%d",
            info.centerX(), info.topY(), info.centerZ(),
            info.widthM(), info.depthM(), info.recordedBlocks())), false);
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

    private static String formatMocapControlStatus(MavlinkLinkStatus status) {
        if (!status.mocapHealthEnabled()) {
            return "disabled";
        }
        return status.mocapControlBound()
            ? "listening:127.0.0.1:" + status.mocapControlPort()
            : "not-listening:127.0.0.1:" + status.mocapControlPort();
    }
}
