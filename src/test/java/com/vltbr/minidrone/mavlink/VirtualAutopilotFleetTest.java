package com.vltbr.minidrone.mavlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vltbr.minidrone.sim.LocalSetpoint;
import com.vltbr.minidrone.sim.VirtualDroneFleet;
import com.vltbr.minidrone.sim.VirtualDroneHandle;
import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import com.vltbr.minidrone.sim.VirtualDroneState;
import com.vltbr.minidrone.sim.VirtualFlightController;
import com.vltbr.minidrone.sim.VehicleModel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A fleet on the wire: a frame goes to the vehicle it names and to no other.
 *
 * <p>The autopilot exists to answer MAVLink traffic. With one drone "the vehicle" was an
 * answer; with a fleet the only thing a frame says about its target is its system id, so the
 * tests here drive real decoded frames at a two-drone world and check that the second drone
 * can be armed, flown and acknowledged without the first one moving - and that traffic for a
 * system id this world does not fly is dropped instead of landing on the first drone.
 */
class VirtualAutopilotFleetTest {
    private static final int COMPONENT_ID = 1;
    /**
     * What the frame *header* carries when the backend sends a command, as opposed to the
     * target inside the payload. Resolving the vehicle from the header would send every
     * command to nobody, which is why the frames here are built the way the wire builds them.
     */
    private static final int GROUND_CONTROL_SYSTEM_ID = 255;

    /** Two drones addressed the way {@code VirtualDroneManager} addresses them. */
    private static final class FleetWorld implements VirtualFlightController {
        private final VirtualDroneFleet fleet = new VirtualDroneFleet();
        private final VirtualDroneState first = fleet.create(VehicleModel.DEFAULTS);
        private final VirtualDroneState second = fleet.create(VehicleModel.DEFAULTS);
        private int publishCount;

        @Override
        public VirtualDroneSnapshot snapshot() {
            return first.snapshot();
        }

        @Override
        public VirtualFlightController vehicleForSystemId(int systemId) {
            VirtualDroneState drone = fleet.bySystemId(systemId);
            return drone == null ? null : new VirtualDroneHandle(drone, () -> publishCount++);
        }

        // The world-level methods mean the drone a single-drone world means: the first one.
        // The autopilot resolves a vehicle per frame, so these are only here because the
        // contract carries them.

        @Override
        public boolean setMode(int customMode) {
            return first.setMode(customMode);
        }

        @Override
        public boolean setArmed(boolean armed) {
            return first.setArmed(armed);
        }

        @Override
        public boolean takeoff(double altitudeM) {
            return first.takeoff(altitudeM);
        }

        @Override
        public boolean land() {
            return first.land();
        }

        @Override
        public boolean setLocalSetpoint(LocalSetpoint setpoint) {
            return first.setLocalSetpoint(setpoint);
        }

        int firstSystemId() {
            return first.snapshot().systemId();
        }

        int secondSystemId() {
            return second.snapshot().systemId();
        }

        String secondDroneId() {
            return second.snapshot().droneId();
        }

        VirtualDroneState first() {
            return first;
        }

        VirtualDroneState second() {
            return second;
        }

        void tick(int count) {
            for (int index = 0; index < count; index++) {
                first.tick();
                second.tick();
            }
        }
    }

    private static final class Rig {
        final FleetWorld world = new FleetWorld();
        final List<MavlinkOutboundMessage> outbound = new ArrayList<>();
        final VirtualAutopilot autopilot =
            new VirtualAutopilot(Runnable::run, world, outbound::add, new ForwardingHold());

        /**
         * Delivers a frame the way the transport does: encode, decode, handle.
         *
         * <p>`targetSystemId` is the target inside the payload; the header carries the ground
         * station's own system id, exactly as the backend's does.
         */
        void deliver(int targetSystemId, int messageId, byte[] payload) {
            byte[] frame = MavlinkV1Codec.encode(
                1,
                GROUND_CONTROL_SYSTEM_ID,
                COMPONENT_ID,
                messageId,
                payload
            );
            List<MavlinkV1Frame> decoded = MavlinkV1Codec.decode(frame, frame.length);
            assertEquals(1, decoded.size(), "the mod's own codec must accept its own frame");
            assertEquals(
                GROUND_CONTROL_SYSTEM_ID,
                decoded.get(0).systemId(),
                "the header carries the sender, not the target"
            );
            autopilot.handle(decoded.get(0));
        }

        MavlinkOutboundMessage firstWithMessageId(int messageId) {
            for (MavlinkOutboundMessage message : outbound) {
                if (message.messageId() == messageId) {
                    return message;
                }
            }
            return null;
        }

        MavlinkOutboundMessage lastWithMessageId(int messageId) {
            MavlinkOutboundMessage found = null;
            for (MavlinkOutboundMessage message : outbound) {
                if (message.messageId() == messageId) {
                    found = message;
                }
            }
            return found;
        }
    }

    private static byte[] commandLong(int targetSystem, int targetComponent, int command, float... params) {
        ByteBuffer payload = MavlinkPayloads.writer(33);
        for (int index = 0; index < 7; index++) {
            payload.putFloat(index < params.length ? params[index] : 0.0f);
        }
        payload.putShort((short) command);
        payload.put((byte) targetSystem);
        payload.put((byte) targetComponent);
        payload.put((byte) 0);
        return payload.array();
    }

    private static byte[] positionTargetLocalNed(
        int targetSystem, double north, double east, double down
    ) {
        ByteBuffer payload = MavlinkPayloads.writer(53);
        payload.putInt(1234);
        payload.putFloat((float) north);
        payload.putFloat((float) east);
        payload.putFloat((float) down);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putShort((short) MavlinkProtocol.POSITION_TARGET_TYPE_MASK_POSITION_ONLY);
        payload.put((byte) targetSystem);
        payload.put((byte) COMPONENT_ID);
        payload.put((byte) MavlinkProtocol.MAV_FRAME_LOCAL_NED);
        return payload.array();
    }

    @Test
    void aCommandReachesTheDroneItsSystemIdNames() {
        Rig rig = new Rig();

        // GUIDE the second drone and arm it: the first one must not move.
        rig.deliver(
            rig.world.secondSystemId(),
            MavlinkProtocol.COMMAND_LONG,
            commandLong(
                rig.world.secondSystemId(),
                COMPONENT_ID,
                MavlinkProtocol.MAV_CMD_DO_SET_MODE,
                1.0f,
                MavlinkProtocol.ARDUCOPTER_MODE_GUIDED
            )
        );
        rig.deliver(
            rig.world.secondSystemId(),
            MavlinkProtocol.COMMAND_LONG,
            commandLong(
                rig.world.secondSystemId(),
                COMPONENT_ID,
                MavlinkProtocol.MAV_CMD_COMPONENT_ARM_DISARM,
                1.0f
            )
        );

        assertTrue(rig.world.second().snapshot().armed(), "the addressed drone was armed");
        assertFalse(rig.world.first().snapshot().armed(), "the other drone was left alone");
        assertTrue(rig.world.first().snapshot().systemId() != rig.world.second().snapshot().systemId(),
            "the two drones answer to different system ids");
    }

    @Test
    void anAckSpeaksAsTheVehicleBeingAnswered() {
        Rig rig = new Rig();

        rig.deliver(
            rig.world.secondSystemId(),
            MavlinkProtocol.COMMAND_LONG,
            commandLong(
                rig.world.secondSystemId(),
                COMPONENT_ID,
                MavlinkProtocol.MAV_CMD_SET_MESSAGE_INTERVAL,
                0.0f
            )
        );

        MavlinkOutboundMessage ack = rig.lastWithMessageId(MavlinkProtocol.COMMAND_ACK);
        assertNotNull(ack, "the command was acknowledged");
        assertEquals(rig.world.secondSystemId(), ack.systemId(),
            "the ack must not go out under the first drone's identity");
        assertEquals(COMPONENT_ID, ack.componentId());
    }

    @Test
    void aCommandForAnUnknownVehicleIsDroppedInsteadOfLandingOnTheFirstDrone() {
        Rig rig = new Rig();
        int strangerSystemId = 200;

        assertNull(rig.world.vehicleForSystemId(strangerSystemId), "no drone flies that id");
        rig.deliver(
            strangerSystemId,
            MavlinkProtocol.COMMAND_LONG,
            commandLong(strangerSystemId, COMPONENT_ID, MavlinkProtocol.MAV_CMD_COMPONENT_ARM_DISARM, 1.0f)
        );

        assertFalse(rig.world.first().snapshot().armed(), "the first drone stayed untouched");
        assertFalse(rig.world.second().snapshot().armed(), "the second drone stayed untouched");
        assertTrue(rig.outbound.isEmpty(), "nothing was answered for a vehicle this world does not fly");
    }

    /** A broadcast target (system id zero) means the drone a single-drone world means. */
    @Test
    void aBroadcastCommandReachesTheFirstDrone() {
        Rig rig = new Rig();

        rig.deliver(0, MavlinkProtocol.COMMAND_LONG, commandLong(
            0, 0, MavlinkProtocol.MAV_CMD_DO_SET_MODE, 1.0f, MavlinkProtocol.ARDUCOPTER_MODE_GUIDED));
        rig.deliver(0, MavlinkProtocol.COMMAND_LONG, commandLong(
            0, 0, MavlinkProtocol.MAV_CMD_COMPONENT_ARM_DISARM, 1.0f));

        assertTrue(rig.world.first().snapshot().armed(), "the first drone took the broadcast");
        assertFalse(rig.world.second().snapshot().armed(), "and the second one did not");
    }

    @Test
    void aSetpointMovesTheAddressedDroneByItsOwnIdentity() {
        Rig rig = new Rig();
        int systemId = rig.world.secondSystemId();

        // Arm and take off the second drone, then command a position hold away from the origin.
        rig.deliver(systemId, MavlinkProtocol.COMMAND_LONG, commandLong(
            systemId, COMPONENT_ID, MavlinkProtocol.MAV_CMD_DO_SET_MODE, 1.0f,
            MavlinkProtocol.ARDUCOPTER_MODE_GUIDED));
        rig.deliver(systemId, MavlinkProtocol.COMMAND_LONG, commandLong(
            systemId, COMPONENT_ID, MavlinkProtocol.MAV_CMD_COMPONENT_ARM_DISARM, 1.0f));
        rig.deliver(systemId, MavlinkProtocol.COMMAND_LONG, commandLong(
            systemId, COMPONENT_ID, MavlinkProtocol.MAV_CMD_NAV_TAKEOFF, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f));
        // Let the takeoff finish before commanding a cruise target: a vehicle still climbing
        // to its hold altitude is not flying the setpoint yet.
        rig.world.tick(40);
        assertTrue(rig.world.second().snapshot().airborne(), "the second drone took off");

        rig.deliver(systemId, MavlinkProtocol.SET_POSITION_TARGET_LOCAL_NED,
            positionTargetLocalNed(systemId, 2.0, 1.0, -1.0));

        rig.world.tick(60);

        assertTrue(rig.world.second().snapshot().northM() > 0.3,
            "the setpoint moved the addressed drone");
        assertEquals(0.0, rig.world.first().snapshot().northM(), 1.0e-9,
            "and left the other drone where it was");
    }

    @Test
    void theTransportSendsOneFramePerDrone() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState one = fleet.create(VehicleModel.DEFAULTS);
        VirtualDroneState two = fleet.create(VehicleModel.DEFAULTS);

        List<VirtualDroneSnapshot> snapshots = MavlinkTransport.fleetSnapshots(fleet.all());

        assertEquals(2, snapshots.size(), "every drone gets its own telemetry");
        assertEquals(one.snapshot().droneId(), snapshots.get(0).droneId(), "creation order is kept");
        assertEquals(two.snapshot().droneId(), snapshots.get(1).droneId());
        assertTrue(snapshots.get(0).systemId() != snapshots.get(1).systemId(),
            "each frame carries that drone's own system id");
        assertEquals(List.of(), MavlinkTransport.fleetSnapshots(List.of()),
            "an empty world sends nothing");
        assertEquals(List.of(), MavlinkTransport.fleetSnapshots(null));
    }

    @Test
    void theFleetIsAddressableBySystemId() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState one = fleet.create(VehicleModel.DEFAULTS);
        VirtualDroneState two = fleet.create(VehicleModel.DEFAULTS);

        assertEquals(one, fleet.bySystemId(one.snapshot().systemId()));
        assertEquals(two, fleet.bySystemId(two.snapshot().systemId()));
        assertNull(fleet.bySystemId(200), "an unknown system id names no drone");
    }

    /** The handle is the adapter the manager hands the autopilot; it must republish on change. */
    @Test
    void aHandleReportsChangesToItsOwner() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        int[] publishes = {0};
        VirtualDroneHandle handle = new VirtualDroneHandle(drone, () -> publishes[0]++);

        assertNull(handle.vehicleForSystemId(55), "a handle speaks for one vehicle");
        assertEquals(handle, handle.vehicleForSystemId(54));
        assertTrue(handle.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED));
        assertTrue(handle.setArmed(true));
        assertTrue(publishes[0] >= 2, "every accepted command republished the world view");
    }
}
