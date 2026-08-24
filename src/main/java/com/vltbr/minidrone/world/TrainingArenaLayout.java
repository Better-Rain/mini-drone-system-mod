package com.vltbr.minidrone.world;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure layout description used by the world controller and unit tests. */
public final class TrainingArenaLayout {
    public static final int RADIUS = 6;

    private final int centerX;
    private final int topY;
    private final int centerZ;
    private final List<RelativeBlock> blocks;

    private TrainingArenaLayout(int centerX, int topY, int centerZ) {
        this.centerX = centerX;
        this.topY = topY;
        this.centerZ = centerZ;
        this.blocks = Collections.unmodifiableList(buildBlocks());
    }

    public static TrainingArenaLayout centered(int centerX, int topY, int centerZ) {
        return new TrainingArenaLayout(centerX, topY, centerZ);
    }

    public int centerX() {
        return centerX;
    }

    public int topY() {
        return topY;
    }

    public int centerZ() {
        return centerZ;
    }

    public List<RelativeBlock> blocks() {
        return blocks;
    }

    public BlockPos position(RelativeBlock block) {
        return new BlockPos(centerX + block.dx(), topY + block.dy(), centerZ + block.dz());
    }

    private List<RelativeBlock> buildBlocks() {
        List<RelativeBlock> result = new ArrayList<>((RADIUS * 2 + 1) * (RADIUS * 2 + 1) + 4);
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                boolean edge = Math.abs(dx) == RADIUS || Math.abs(dz) == RADIUS;
                Kind surfaceKind = edge
                    ? Kind.BORDER
                    : dx == 0 && dz == 0
                        ? Kind.LANDING_CENTER
                        : Math.abs(dx) <= 1 && Math.abs(dz) <= 1
                            ? Kind.LANDING_PAD
                            : Kind.PLATFORM;
                result.add(new RelativeBlock(dx, 0, dz, surfaceKind));
                if (Math.abs(dx) == RADIUS && Math.abs(dz) == RADIUS) {
                    result.add(new RelativeBlock(dx, 1, dz, Kind.CORNER_MARKER));
                }
            }
        }
        return result;
    }

    public enum Kind {
        PLATFORM,
        BORDER,
        CORNER_MARKER,
        LANDING_PAD,
        LANDING_CENTER
    }

    public record RelativeBlock(int dx, int dy, int dz, Kind kind) {
    }
}
