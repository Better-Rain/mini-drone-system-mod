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

import java.util.ArrayList;
import java.util.List;

public final class TrainingArenaController {
    private static final int UPDATE_FLAGS = Block.UPDATE_ALL;

    private final MinecraftServer server;
    private final TrainingArenaSavedData savedData;

    public TrainingArenaController(MinecraftServer server) {
        this.server = server;
        this.savedData = server.overworld().getDataStorage()
            .computeIfAbsent(TrainingArenaSavedData.factory(), TrainingArenaSavedData.DATA_ID);
    }

    public CreateResult create(ServerPlayer player) {
        if (player.serverLevel() != server.overworld()) {
            return new CreateResult(CreateStatus.WRONG_DIMENSION, 0, 0);
        }
        if (savedData.hasArena()) {
            return new CreateResult(CreateStatus.ALREADY_EXISTS, 0, 0);
        }

        ServerLevel level = server.overworld();
        BlockPos playerPos = player.blockPosition();
        // Leave one air block above the tallest nearby surface so the layout never
        // overlaps the player or replaces terrain while still staying close by.
        int topY = highestSurface(level, playerPos) + 1;
        TrainingArenaLayout layout = TrainingArenaLayout.centered(playerPos.getX(), topY, playerPos.getZ());
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
        return new CreateResult(CreateStatus.CREATED, placed.size(), skipped);
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

    private static int highestSurface(ServerLevel level, BlockPos center) {
        int highest = center.getY();
        for (int dx = -TrainingArenaLayout.RADIUS; dx <= TrainingArenaLayout.RADIUS; dx++) {
            for (int dz = -TrainingArenaLayout.RADIUS; dz <= TrainingArenaLayout.RADIUS; dz++) {
                highest = Math.max(highest, level.getHeight(
                    Heightmap.Types.WORLD_SURFACE, center.getX() + dx, center.getZ() + dz));
            }
        }
        return highest;
    }

    private static BlockState stateFor(TrainingArenaLayout.Kind kind) {
        return switch (kind) {
            case PLATFORM -> Blocks.SMOOTH_STONE.defaultBlockState();
            case BORDER -> Blocks.RED_CONCRETE.defaultBlockState();
            case CORNER_MARKER -> Blocks.SEA_LANTERN.defaultBlockState();
        };
    }

    public enum CreateStatus { CREATED, ALREADY_EXISTS, WRONG_DIMENSION, NO_SPACE }
    public enum ClearStatus { CLEARED, NOT_FOUND }

    public record CreateResult(CreateStatus status, int placed, int skipped) { }
    public record ClearResult(ClearStatus status, int removed, int preserved) { }
}
