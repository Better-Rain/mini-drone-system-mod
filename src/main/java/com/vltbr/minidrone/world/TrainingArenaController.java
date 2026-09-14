package com.vltbr.minidrone.world;

import com.vltbr.minidrone.MiniDroneMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class TrainingArenaController {
    private static final int UPDATE_FLAGS = Block.UPDATE_ALL;
    private static final double DEFAULT_FORWARD_DISTANCE = TrainingArenaLayout.RADIUS + 4.0;

    private final MinecraftServer server;
    private final TrainingArenaSavedData savedData;

    public TrainingArenaController(MinecraftServer server) {
        this.server = server;
        this.savedData = server.overworld().getDataStorage()
            .computeIfAbsent(TrainingArenaSavedData.factory(), TrainingArenaSavedData.DATA_ID);
    }

    public CreateResult create(ServerPlayer player) {
        return create(player, null);
    }

    public CreateResult create(ServerPlayer player, BlockPos requestedCenter) {
        if (player.serverLevel() != server.overworld()) {
            return new CreateResult(CreateStatus.WRONG_DIMENSION, 0, 0);
        }
        if (savedData.hasArena()) {
            return new CreateResult(CreateStatus.ALREADY_EXISTS, 0, 0);
        }

        ServerLevel level = server.overworld();
        BlockPos center = requestedCenter != null
            ? requestedCenter
            : defaultCenter(level, player);
        if (center.getY() < level.getMinBuildHeight() || center.getY() >= level.getMaxBuildHeight()) {
            return new CreateResult(CreateStatus.INVALID_POSITION, 0, 0);
        }
        TrainingArenaLayout layout = TrainingArenaLayout.centered(
            center.getX(), center.getY(), center.getZ());
        List<TrainingArenaSavedData.PlacedBlock> placed = new ArrayList<>();
        int skipped = 0;
        for (TrainingArenaLayout.RelativeBlock block : layout.blocks()) {
            BlockPos position = layout.position(block);
            BlockState current = level.getBlockState(position);
            if (!current.isAir()) {
                skipped++;
                continue;
            }
            if (!level.setBlock(position, stateFor(block.kind()), UPDATE_FLAGS)) {
                skipped++;
                continue;
            }
            placed.add(new TrainingArenaSavedData.PlacedBlock(
                position.getX(), position.getY(), position.getZ(), block.kind()));
        }

        if (placed.isEmpty()) {
            return new CreateResult(CreateStatus.NO_SPACE, 0, skipped);
        }
        savedData.replaceWith(layout, placed);
        MiniDroneMod.LOGGER.info("Created training arena at ({}, {}, {}) with {} blocks ({} skipped)",
            layout.centerX(), layout.topY(), layout.centerZ(), placed.size(), skipped);
        // The centre has to travel with the result: the command prints it back to
        // the operator, and the convenience constructor would leave it as 0,0,0.
        return new CreateResult(
            CreateStatus.CREATED, placed.size(), skipped,
            layout.centerX(), layout.topY(), layout.centerZ());
    }

    public ClearResult clear() {
        if (!savedData.hasArena()) {
            return new ClearResult(ClearStatus.NOT_FOUND, 0, 0);
        }
        ServerLevel level = server.overworld();
        int removed = 0;
        int preserved = 0;
        for (TrainingArenaSavedData.PlacedBlock placed : savedData.placedBlocks()) {
            BlockPos position = new BlockPos(placed.x(), placed.y(), placed.z());
            BlockState expected = stateFor(placed.kind());
            if (level.getBlockState(position).equals(expected)) {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS);
                removed++;
            } else {
                preserved++;
            }
        }
        savedData.clearArena();
        MiniDroneMod.LOGGER.info("Cleared training arena: removed {}, preserved {} changed blocks", removed, preserved);
        return new ClearResult(ClearStatus.CLEARED, removed, preserved);
    }

    public boolean hasArena() {
        return savedData.hasArena();
    }

    public ArenaInfo info() {
        if (!savedData.hasArena()) {
            return new ArenaInfo(false, 0, 0, 0, 0);
        }
        TrainingArenaLayout layout = savedData.layout();
        return new ArenaInfo(
            true,
            layout.centerX(),
            layout.topY(),
            layout.centerZ(),
            savedData.placedBlocks().size()
        );
    }

    private static BlockPos defaultCenter(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontalLook = new Vec3(look.x, 0.0, look.z);
        if (horizontalLook.lengthSqr() < 1.0E-6) {
            horizontalLook = new Vec3(0.0, 0.0, 1.0);
        } else {
            horizontalLook = horizontalLook.normalize();
        }
        int targetX = (int) Math.floor(player.getX() + horizontalLook.x * DEFAULT_FORWARD_DISTANCE);
        int targetZ = (int) Math.floor(player.getZ() + horizontalLook.z * DEFAULT_FORWARD_DISTANCE);
        // WORLD_SURFACE includes leaves and nearby terrain can be much higher than
        // the player. Use the target column and ignore leaf canopies for the
        // default anchor; explicit coordinates remain available for exact layouts.
        int surfaceY = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
        return new BlockPos(targetX, surfaceY, targetZ);
    }

    private static BlockState stateFor(TrainingArenaLayout.Kind kind) {
        return switch (kind) {
            case PLATFORM -> Blocks.SMOOTH_STONE.defaultBlockState();
            case BORDER -> Blocks.RED_CONCRETE.defaultBlockState();
            case CORNER_MARKER -> Blocks.SEA_LANTERN.defaultBlockState();
            case LANDING_PAD -> Blocks.WHITE_CONCRETE.defaultBlockState();
            case LANDING_CENTER -> Blocks.BLACK_CONCRETE.defaultBlockState();
        };
    }

    public enum CreateStatus { CREATED, ALREADY_EXISTS, WRONG_DIMENSION, NO_SPACE, INVALID_POSITION }
    public enum ClearStatus { CLEARED, NOT_FOUND }

    /**
     * {@code centerX/Y/Z} are only meaningful for {@link CreateStatus#CREATED};
     * the other statuses use the convenience constructor and leave them at 0.
     */
    public record CreateResult(
        CreateStatus status,
        int placed,
        int skipped,
        int centerX,
        int topY,
        int centerZ
    ) {
        public CreateResult(CreateStatus status, int placed, int skipped) {
            this(status, placed, skipped, 0, 0, 0);
        }
    }
    public record ClearResult(ClearStatus status, int removed, int preserved) { }
    public record ArenaInfo(boolean present, int centerX, int topY, int centerZ, int recordedBlocks) { }
}
