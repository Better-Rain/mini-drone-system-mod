package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.LocalSetpoint;
import com.vltbr.minidrone.sim.VehicleModel;
import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import com.vltbr.minidrone.sim.VirtualDroneState;
import com.vltbr.minidrone.sim.VirtualFlightController;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inbound half of the contract: a frame the backend sends, decoded by the
 * mod's own codec, applied by the mod's own autopilot to the mod's own flight
 * model.
 *
 * <p>The contract verifier plays the vehicle, so it can never exercise this
 * direction - it proves the backend asks for the right things, not that the mod
 * acts on them. These cases walk {@link MavlinkV1Codec} and
 * {@link VirtualAutopilot} together for every {@code type_mask} shape the main
 * project's {@code set_pva_target} produces, which is what PVA support means in
 * practice.
 */
class VirtualAutopilotInboundTest {
    private static final int POSITION_ONLY = 0x0DF8;
    private static final int POSITION_VELOCITY = 0x0DC0;
    private static final int POSITION_VELOCITY_ACCELERATION = 0x0C00;
    private static final int VELOCITY_ONLY = 0x0DC7;
    private static final int YAW_ONLY = 0x09FF;
    private static final int FULL = 0x0000;
    private static final int NOTHING_COMMANDED = 0xFFFF;

    /** The flight model behind the interface the autopilot depends on. */
    private static final class TestController implements VirtualFlightController {
        private final VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");

        @Override
        public VirtualDroneSnapshot snapshot() {
            return drone.snapshot();
        }

        @Override
        public VirtualFlightController vehicleForSystemId(int systemId) {
            return systemId == drone.snapshot().systemId() ? this : null;
        }

        @Override
        public boolean setMode(int customMode) {
            return drone.setMode(customMode);
        }

        @Override
        public boolean setArmed(boolean armed) {
            return drone.setArmed(armed);
        }

        @Override
        public boolean takeoff(double altitudeM) {
            return drone.takeoff(altitudeM);
        }

        @Override
        public boolean land() {
            return drone.land();
        }

        @Override
        public boolean setLocalSetpoint(LocalSetpoint setpoint) {
            return drone.setLocalSetpoint(setpoint);
        }

        void tick(int count) {
            for (int index = 0; index < count; index++) {
                drone.tick();
            }
        }
    }

    private static final class Rig {
        final TestController controller = new TestController();
        final List<MavlinkOutboundMessage> outbound = new ArrayList<>();
        final ForwardingHold hold = new ForwardingHold();
        final VirtualAutopilot autopilot;

        Rig() {
            autopilot = new VirtualAutopilot(Runnable::run, controller, outbound::add, hold);
            controller.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
            controller.setArmed(true);
            assertTrue(controller.takeoff(1.0));
            controller.tick(40);
            assertTrue(controller.snapshot().airborne());
        }

        void tick(int count) {
            controller.tick(count);
        }

        VirtualDroneSnapshot snapshot() {
            return controller.snapshot();
        }

        /** Delivers a frame the way the transport does: encode, decode, handle. */
        void deliver(
            int typeMask,
            double north,
            double east,
            double down,
            double velocityNorth,
            double velocityEast,
            double velocityDown,
            double yaw
        ) {
            ByteBuffer payload = MavlinkPayloads.writer(53);
            payload.putInt(1234);
            payload.putFloat((float) north);
            payload.putFloat((float) east);
            payload.putFloat((float) down);
            payload.putFloat((float) velocityNorth);
            payload.putFloat((float) velocityEast);
            payload.putFloat((float) velocityDown);
            payload.putFloat(0.0f);
            payload.putFloat(0.0f);
            payload.putFloat(0.0f);
            payload.putFloat((float) yaw);
            payload.putFloat(0.0f);
            payload.putShort((short) typeMask);
            payload.put((byte) 54);
            payload.put((byte) 1);
            payload.put((byte) MavlinkProtocol.MAV_FRAME_LOCAL_NED);

            byte[] frame = MavlinkV1Codec.encode(
                1,
                54,
                1,
                MavlinkProtocol.SET_POSITION_TARGET_LOCAL_NED,
                payload.array()
            );
            List<MavlinkV1Frame> decoded = MavlinkV1Codec.decode(frame, frame.length);
            assertEquals(1, decoded.size(), "the mod's own codec must accept its own frame");
            autopilot.handle(decoded.get(0));
        }
    }

    @Test
    void appliesAPositionOnlySetpoint() {
        Rig rig = new Rig();
        rig.deliver(POSITION_ONLY, 2.0, 1.0, -1.0, 0, 0, 0, 0);
        rig.tick(1);

        // One tick of the airframe response: the setpoint arrived, the vehicle started
        // towards it, and the speed is inside the envelope - but the airframe has to tip
        // its thrust into the movement first, so it has not built the flight controller's
        // 1.4 m/s in a single 50 ms tick. The lean it starts with points the way the
        // target is: nose-down for north, rolled right for east, no yaw commanded.
        VirtualDroneSnapshot moving = rig.snapshot();
        double speed = Math.hypot(moving.velocityNorthMps(), moving.velocityEastMps());
        assertTrue(speed > 0.0, "the setpoint did not start the vehicle moving");
        assertTrue(
            speed < VehicleModel.DEFAULTS.maxHorizontalSpeedMps(),
            "the airframe reached the commanded speed in one tick");
        assertTrue(moving.northM() > 0.0);
        assertTrue(moving.eastM() > 0.0);
        assertTrue(moving.pitchRad() < 0.0, "the airframe started without leaning into the command");
        assertTrue(moving.rollRad() > 0.0, "the airframe started without leaning into the command");

        // The response catches up with the command, so the vehicle is taken to the point
        // and held there: the tracker flies the leg at a speed the airframe can stop from
        // and the plant's arrival deadband catches the last few centimetres (measured on
        // this leg: the vehicle snaps onto (2, 1) on tick 44 and is still exactly there at
        // the end of the budget, having covered its 0.0403 m of approach at 0.2511 m/s).
        rig.tick(60);
        VirtualDroneSnapshot arrived = rig.snapshot();
        assertEquals(2.0, arrived.northM(), 0.01);
        assertEquals(1.0, arrived.eastM(), 0.01);
        assertEquals(-1.0, arrived.downM(), 0.01);
    }

    @Test
    void appliesPositionWithVelocityAndAcceleration() {
        Rig rig = new Rig();
        rig.deliver(POSITION_VELOCITY_ACCELERATION, 2.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0);
        rig.tick(1);

        assertTrue(rig.snapshot().northM() > 0.0, "the setpoint was dropped");
    }

    @Test
    void appliesAPositionVelocitySetpoint() {
        Rig rig = new Rig();
        rig.deliver(POSITION_VELOCITY, 2.0, 0.0, -1.0, 0.1, 0.0, 0.0, 0.0);
        rig.tick(4);

        assertTrue(rig.snapshot().northM() > 0.0, "the setpoint was dropped");
    }

    @Test
    void appliesAVelocityOnlySetpoint() {
        Rig rig = new Rig();
        rig.deliver(VELOCITY_ONLY, 0, 0, 0, 0.5, 0.0, 0.0, 0.0);

        // The command is not a teleport: the airframe builds the lean that produces the
        // speed first, so one tick in the vehicle is leaning north (nose-down) and moving
        // well short of 0.5 m/s.
        rig.tick(1);
        VirtualDroneSnapshot leaning = rig.snapshot();
        double firstSpeed = leaning.velocityNorthMps();
        assertTrue(firstSpeed > 0.0, "the velocity channel did not start the vehicle moving");
        assertTrue(firstSpeed < 0.5, "the airframe reached the commanded speed in one tick");
        assertTrue(leaning.pitchRad() < 0.0, "the velocity channel drove the vehicle without leaning");
        assertEquals(0.0, leaning.velocityEastMps(), 1e-9);

        // Two seconds is past the response time, so the command is reached and held: the
        // velocity loop's trim cancels the drag the lean is fighting, so a 0.5 m/s command
        // settles on 0.5 m/s (measured 0.49931 m/s at two seconds, still rising towards it
        // from below) instead of on the 0.427 m/s the error alone would balance drag at.
        rig.tick(39);
        VirtualDroneSnapshot flying = rig.snapshot();
        assertEquals(0.5, flying.velocityNorthMps(), 0.005);
        assertTrue(flying.northM() > 0.5, "the velocity channel did not move the drone");
    }

    @Test
    void appliesAYawOnlySetpoint() {
        Rig rig = new Rig();
        rig.deliver(YAW_ONLY, 0, 0, 0, 0, 0, 0, 0.5);
        rig.tick(15);

        assertEquals(0.5, rig.snapshot().yawRad(), 1e-4);
    }

    @Test
    void appliesAFullyCommandedSetpoint() {
        Rig rig = new Rig();
        rig.deliver(FULL, 2.0, 1.0, -1.0, 0.2, 0.0, 0.0, 0.5);
        rig.tick(4);

        VirtualDroneSnapshot flying = rig.snapshot();
        assertTrue(flying.northM() > 0.0, "the setpoint was dropped");
        assertTrue(flying.yawRad() > 0.0, "the yaw channel was dropped");
    }

    @Test
    void ignoresAFrameThatCommandsNothing() {
        Rig rig = new Rig();
        VirtualDroneSnapshot before = rig.snapshot();

        rig.deliver(NOTHING_COMMANDED, 2.0, 1.0, -1.0, 0.5, 0.5, 0.0, 0.5);
        rig.tick(10);

        VirtualDroneSnapshot after = rig.snapshot();
        assertEquals(before.northM(), after.northM(), 1e-9);
        assertEquals(before.eastM(), after.eastM(), 1e-9);
        assertEquals(0.0, after.velocityNorthMps(), 1e-9);
        assertEquals(before.yawRad(), after.yawRad(), 1e-9);
    }

    @Test
    void doesNotAnswerWithCommandsOfItsOwn() {
        Rig rig = new Rig();
        rig.deliver(POSITION_ONLY, 2.0, 1.0, -1.0, 0, 0, 0, 0);
        rig.tick(5);

        // A setpoint is fire-and-forget: no COMMAND_ACK is owed for message 84.
        assertFalse(
            rig.outbound.stream().anyMatch(message -> message.messageId() == MavlinkProtocol.COMMAND_ACK),
            "an ACK for a setpoint would confuse the backend's command accounting"
        );
    }
}
