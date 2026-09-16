package com.vltbr.minidrone.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The block-to-metre scale, which is what lets a small in-game vehicle be the small real
 * vehicle it is meant to be without changing how it is drawn or built.
 *
 * <p>At 1.0 (the default, and every existing setup) nothing moves. At 0.25 a 49-block arena
 * reads as the 12.25 m room it stands for, while the vehicle's 1.4 m/s limit stays exactly
 * what the backend contract says - the scale changes how far the world is said to be, not
 * how fast the vehicle is told to fly.
 */
class WorldScaleTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void defaultsToOneMetrePerBlock() {
        assertEquals(1.0, WorldScale.fromProperties(new Properties()), EPSILON);
        assertEquals(1.0, WorldScale.fromProperties(null), EPSILON);
        assertEquals(1.0, WorldScale.DEFAULT_METRES_PER_BLOCK, EPSILON);
    }

    @Test
    void readsTheOperatorsProperty() {
        Properties properties = new Properties();
        properties.setProperty(WorldScale.PROPERTY, "0.25");
        assertEquals(0.25, WorldScale.fromProperties(properties), EPSILON);
    }

    /** A bad number must not poison every coordinate in the world. */
    @Test
    void fallsBackForValuesThatCannotBeUsed() {
        for (String raw : new String[] {"0", "-1", "not a number", ""}) {
            Properties properties = new Properties();
            properties.setProperty(WorldScale.PROPERTY, raw);
            assertEquals(
                1.0,
                WorldScale.fromProperties(properties),
                EPSILON,
                "value '" + raw + "' should fall back"
            );
        }
    }

    /** NED is metres, the world is blocks: the round trip has to survive the scale. */
    @Test
    void convertsBetweenMetresAndBlocksInBothDirections() {
        NedWorldTransform origin = new NedWorldTransform(10.0, 64.0, 20.0, 0.25);

        // 1 m north of the origin is 4 blocks north of it, because a block is 0.25 m.
        WorldPose world = origin.toWorldPose(1.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertEquals(20.0 - 4.0, world.z(), EPSILON, "north is +1 m, so 4 blocks");
        assertEquals(10.0, world.x(), EPSILON);
        assertEquals(64.0, world.y(), EPSILON);

        double[] ned = DronePlacement.nedOffsetFor(origin, world.x(), world.y(), world.z());
        assertEquals(1.0, ned[0], EPSILON, "and back again");
        assertEquals(0.0, ned[1], EPSILON);
        assertEquals(0.0, ned[2], EPSILON);
    }

    /** Height scales the same way: a metre up is four blocks up at 0.25. */
    @Test
    void scalesHeightAsWell() {
        NedWorldTransform origin = new NedWorldTransform(0.0, 64.0, 0.0, 0.25);
        WorldPose world = origin.toWorldPose(0.0, 0.0, -1.0, 0.0, 0.0, 0.0);
        assertEquals(64.0 + 4.0, world.y(), EPSILON);
        double[] ned = DronePlacement.nedOffsetFor(origin, world.x(), world.y(), world.z());
        assertEquals(-1.0, ned[2], EPSILON);
    }

    /** At the default scale the arithmetic is the old arithmetic, unchanged. */
    @Test
    void aScaleOfOneIsTheOldBehaviour() {
        NedWorldTransform origin = new NedWorldTransform(10.0, 64.0, 20.0);
        assertEquals(1.0, origin.metresPerBlock(), EPSILON);
        WorldPose world = origin.toWorldPose(1.0, 2.0, -3.0, 0.0, 0.0, 0.0);
        assertEquals(8.0, world.x(), EPSILON, "east 2 m is 2 blocks: the axis mirrors, so 10 - 2");
        assertEquals(67.0, world.y(), EPSILON, "down -3 m is 3 blocks up");
        assertEquals(19.0, world.z(), EPSILON, "north 1 m is 1 block");
    }

    /**
     * A 49-block arena with the scale applied reads as the room it stands for, which is the
     * whole point: the vehicle stays under a block of world and reads as a 0.22 m nano quad.
     */
    @Test
    void aBigArenaReadsAsARoom() {
        double metresPerBlock = 0.25;
        double arenaBlocks = 49.0;
        double vehicleBlocks = 0.9;

        assertEquals(12.25, arenaBlocks * metresPerBlock, EPSILON);
        assertEquals(0.225, vehicleBlocks * metresPerBlock, EPSILON);
        double share = vehicleBlocks / arenaBlocks;
        assertTrue(share < 0.02, "the vehicle should be under 2 per cent of the room: " + share);
    }
}
