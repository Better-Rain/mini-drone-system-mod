package com.vltbr.minidrone.world;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure layout description used by the world controller and unit tests. */
public final class TrainingArenaLayout {
    /** Default half-extent of the arena in blocks, applied to both axes. */
    public static final int RADIUS = 6;

    private final int centerX;
    private final int topY;
    private final int centerZ;
    private final int radiusX;
    private final int radiusZ;
    private final List<RelativeBlock> blocks;

    private TrainingArenaLayout(int centerX, int topY, int centerZ, int radiusX, int radiusZ) {
        this.centerX = centerX;
        this.topY = topY;
        this.centerZ = centerZ;
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
        this.blocks = Collections.unmodifiableList(buildBlocks());
    }

    /** The default square arena (13 x 13 blocks at the default radius). */
    public static TrainingArenaLayout centered(int centerX, int topY, int centerZ) {
        return centered(centerX, topY, centerZ, RADIUS, RADIUS);
    }

    /**
     * An arena of {@code 2 * radiusX + 1} by {@code 2 * radiusZ + 1} blocks. The
     * two axes are independent so a longer runway along one of them is possible;
     * the centre stays the landing pad either way, which is what the advertised
     * field and the virtual world origin both key off.
     */
    public static TrainingArenaLayout centered(
        int centerX,
        int topY,
        int centerZ,
        int radiusX,
        int radiusZ
    ) {
        if (radiusX < 0 || radiusZ < 0) {
            throw new IllegalArgumentException("arena radius cannot be negative");
        }
        return new TrainingArenaLayout(centerX, topY, centerZ, radiusX, radiusZ);
    }

    /**
     * Arena footprint along X in metres (one block is one metre), advertised as
     * {@code field_size_m[0]}.
     */
    public int widthM() {
        return radiusX * 2 + 1;
    }

    /** Arena footprint along Z in metres, advertised as {@code field_size_m[1]}. */
    public int depthM() {
        return radiusZ * 2 + 1;
    }

    public int radiusX() {
        return radiusX;
    }

    public int radiusZ() {
        return radiusZ;
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
        List<RelativeBlock> result =
            new ArrayList<>((radiusX * 2 + 1) * (radiusZ * 2 + 1) + 4);
        for (int dx = -radiusX; dx <= radiusX; dx++) {
            for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                boolean edge = Math.abs(dx) == radiusX || Math.abs(dz) == radiusZ;
                Kind surfaceKind = edge
                    ? Kind.BORDER
                    : dx == 0 && dz == 0
                        ? Kind.LANDING_CENTER
                        : Math.abs(dx) <= 1 && Math.abs(dz) <= 1
                            ? Kind.LANDING_PAD
                            : Kind.PLATFORM;
                result.add(new RelativeBlock(dx, 0, dz, surfaceKind));
                if (Math.abs(dx) == radiusX && Math.abs(dz) == radiusZ) {
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
