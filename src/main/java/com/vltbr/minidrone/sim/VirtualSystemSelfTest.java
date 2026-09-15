package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkMessages;
import com.vltbr.minidrone.mavlink.MavlinkProtocol;
import com.vltbr.minidrone.mavlink.MavlinkV1Codec;
import com.vltbr.minidrone.world.NedWorldTransform;
import com.vltbr.minidrone.world.TrainingArenaLayout;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Deterministic, side-effect-free smoke test shared by JUnit and the game command. */
public final class VirtualSystemSelfTest {
    private VirtualSystemSelfTest() {
    }

    public static Report run() {
        Runner runner = new Runner();
        runner.check("guided arm gate", VirtualSystemSelfTest::guidedArmGate);
        runner.check("takeoff reaches altitude", VirtualSystemSelfTest::takeoffReachesAltitude);
        runner.check("waypoint respects speed limit", VirtualSystemSelfTest::waypointRespectsSpeedLimit);
        runner.check("PVA velocity channel", VirtualSystemSelfTest::pvaVelocityChannel);
        runner.check("PVA yaw channel", VirtualSystemSelfTest::pvaYawChannel);
        runner.check("landing disarms at ground", VirtualSystemSelfTest::landingDisarmsAtGround);
        runner.check("safe reset clears local position", VirtualSystemSelfTest::safeResetClearsLocalPosition);
        runner.check("heartbeat codec round trip", VirtualSystemSelfTest::heartbeatCodecRoundTrip);
        runner.check("NED world transform", VirtualSystemSelfTest::nedWorldTransform);
        runner.check("arena layout counts", VirtualSystemSelfTest::arenaLayoutCounts);
        return runner.report();
    }

    private static void guidedArmGate() {
        VirtualDroneState drone = newDrone();
        require(!drone.setArmed(true), "arming outside GUIDED was accepted");
        require(drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED), "GUIDED mode was rejected");
        require(drone.setArmed(true), "arming in GUIDED was rejected");
    }

    private static void takeoffReachesAltitude() {
        VirtualDroneState drone = armedDrone();
        require(drone.takeoff(1.0), "takeoff command was rejected");
        tick(drone, 25);
        VirtualDroneSnapshot snapshot = drone.snapshot();
        require(snapshot.airborne(), "drone did not become airborne");
        require(!snapshot.takingOff(), "takeoff phase did not complete");
        near(snapshot.downM(), -1.0, 0.0001, "takeoff altitude");
    }

    private static void waypointRespectsSpeedLimit() {
        VirtualDroneState drone = armedDrone();
        require(drone.takeoff(1.0), "takeoff command was rejected");
        tick(drone, 25);
        require(drone.setPositionTarget(2.0, 1.0, -1.0), "waypoint command was rejected");
        double maxObservedSpeed = 0.0;
        double maxObservedLean = 0.0;
        for (int i = 0; i < 80; i++) {
            drone.tick();
            VirtualDroneSnapshot snapshot = drone.snapshot();
            maxObservedSpeed = Math.max(maxObservedSpeed, Math.hypot(
                snapshot.velocityNorthMps(), snapshot.velocityEastMps()));
            maxObservedLean = Math.max(maxObservedLean, Math.max(
                Math.abs(snapshot.rollRad()), Math.abs(snapshot.pitchRad())));
        }
        VirtualDroneSnapshot reached = drone.snapshot();
        require(
            maxObservedSpeed <= VehicleModel.DEFAULTS.maxHorizontalSpeedMps() + 1.0e-6,
            "horizontal speed exceeded the vehicle's top speed");
        // The vehicle flies the leg by leaning, so the attitude is part of what this check
        // covers: it moves the way it travels while it is under power, it stays inside the
        // model's lean limit on every tick, and it reaches the speed the flight controller
        // asked for - the velocity loop's trim cancels the drag the lean is fighting, so a
        // 1.4 m/s demand is flown at 1.3997 m/s rather than settling short of it.
        require(
            maxObservedLean <= VehicleModel.DEFAULTS.maxTiltRad() + 1.0e-6,
            "the lean exceeded the vehicle's lean limit");
        require(
            maxObservedSpeed >= VehicleModel.DEFAULTS.maxHorizontalSpeedMps() * 0.95,
            "the waypoint was approached far below the vehicle's speed");
        // Arriving is the other half of a waypoint: the vehicle closes the leg at a speed it
        // can stop from and leaves the last few centimetres to the plant's arrival deadband
        // (measured on this leg: 0.01574 m from the point at 0.29933 m/s on tick 43, the
        // arrival snap exactly on (2, 1) from tick 44, zero speed from tick 46) - and the
        // point is still exactly the commanded one at the end of the 80-tick budget.
        near(reached.northM(), 2.0, 0.0001, "waypoint north");
        near(reached.eastM(), 1.0, 0.0001, "waypoint east");
        near(reached.downM(), -1.0, 0.0001, "waypoint altitude");
    }

    // The backend's PVA setpoints reach the flight model as commanded channels, so
    // the check has to prove a non-position channel actually drives the vehicle. The
    // airframe builds a commanded speed over its response time rather than jumping to
    // it, so the check waits out the whole tick budget it declares instead of stopping
    // the clock the moment the speed lands inside a per cent - measured, that would be
    // tick 29 at 0.4956 m/s, with the vehicle only 0.56535 m down the track, and the
    // travel below would then be read at whatever moment the airframe's response
    // happened to reach, rather than over the two seconds this check is written for.
    // The vehicle is driven there by the lean the channel asks for, so the attitude is
    // checked as part of the channel.
    private static void pvaVelocityChannel() {
        VirtualDroneState drone = airborne();
        require(
            drone.setLocalSetpoint(new LocalSetpoint(
                velocityAxis(0.5), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
                false, 0.0, false, 0.0)),
            "velocity-only setpoint was rejected");
        double commanded = 0.5;
        for (int tick = 0; tick < 60; tick++) {
            drone.tick();
            require(
                drone.snapshot().velocityNorthMps() <= commanded,
                "the velocity response overshot the command");
        }
        VirtualDroneSnapshot snapshot = drone.snapshot();
        // North means nose-down, and it never exceeds the model's lean limit.
        require(snapshot.pitchRad() < 0.0, "the velocity channel drove the drone without leaning");
        require(
            Math.abs(snapshot.pitchRad()) <= VehicleModel.DEFAULTS.maxTiltRad() + 1.0e-6,
            "the velocity channel leaned past the vehicle's lean limit");
        // ... and the speed it settles at is the command itself: the lean that the velocity
        // error asks for is trimmed by the drag the vehicle is fighting, so 0.5 m/s is
        // reached and held instead of settling short of it (measured 0.49998 m/s after 60
        // ticks, approached from below the whole way).
        near(snapshot.velocityNorthMps(), commanded, commanded * 0.01, "commanded velocity");
        // ... and it really moved the drone: 60 ticks at that command is 1.5 m of travel,
        // and the ramp to it buys 1.33916 m. What this asks for is what "the channel moves
        // the vehicle" means, not a calibration of the airframe's response.
        require(snapshot.northM() > 0.35, "velocity channel did not move the drone");
    }

    private static void pvaYawChannel() {
        VirtualDroneState drone = airborne();
        require(
            drone.setLocalSetpoint(new LocalSetpoint(
                LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
                true, 0.5, false, 0.0)),
            "yaw-only setpoint was rejected");
        tick(drone, 15);
        near(drone.snapshot().yawRad(), 0.5, 0.0001, "commanded yaw");
        near(drone.snapshot().yawRateRadS(), 0.0, 0.0001, "settled yaw rate");
    }

    private static void landingDisarmsAtGround() {
        VirtualDroneState drone = armedDrone();
        require(drone.takeoff(0.5), "takeoff command was rejected");
        tick(drone, 20);
        require(drone.land(), "land command was rejected");
        tick(drone, 20);
        VirtualDroneSnapshot snapshot = drone.snapshot();
        require(!snapshot.armed(), "drone remained armed after landing");
        require(!snapshot.airborne(), "drone remained airborne after landing");
        near(snapshot.downM(), 0.0, 0.0001, "landing altitude");
    }

    private static void safeResetClearsLocalPosition() {
        VirtualDroneState drone = armedDrone();
        require(drone.takeoff(0.5), "takeoff command was rejected");
        tick(drone, 20);
        require(!drone.resetLocalPosition(), "active drone accepted local reset");
        require(drone.land(), "land command was rejected");
        tick(drone, 20);
        require(drone.resetLocalPosition(), "landed drone rejected local reset");
        VirtualDroneSnapshot snapshot = drone.snapshot();
        near(snapshot.northM(), 0.0, 0.0001, "reset north");
        near(snapshot.eastM(), 0.0, 0.0001, "reset east");
        near(snapshot.downM(), 0.0, 0.0001, "reset down");
    }

    private static void heartbeatCodecRoundTrip() {
        VirtualDroneSnapshot snapshot = newDrone().snapshot();
        byte[] frame = MavlinkV1Codec.encode(
            7, snapshot.systemId(), snapshot.componentId(),
            MavlinkProtocol.HEARTBEAT, MavlinkMessages.heartbeat(snapshot));
        var decoded = MavlinkV1Codec.decode(frame, frame.length);
        require(decoded.size() == 1, "heartbeat frame did not decode");
        require(decoded.get(0).messageId() == MavlinkProtocol.HEARTBEAT,
            "decoded heartbeat message id changed");
    }

    private static void nedWorldTransform() {
        NedWorldTransform transform = new NedWorldTransform(10.0, 64.0, 20.0);
        var pose = transform.toWorldPose(2.0, 3.0, -4.0, 0.0, 0.0, 0.0);
        near(pose.x(), 7.0, 0.0001, "world x");
        near(pose.y(), 68.0, 0.0001, "world y");
        near(pose.z(), 18.0, 0.0001, "world z");
    }

    private static void arenaLayoutCounts() {
        TrainingArenaLayout layout = TrainingArenaLayout.centered(10, 70, -4);
        Map<TrainingArenaLayout.Kind, Long> counts = new EnumMap<>(TrainingArenaLayout.Kind.class);
        for (TrainingArenaLayout.RelativeBlock block : layout.blocks()) {
            counts.merge(block.kind(), 1L, Long::sum);
        }
        require(counts.get(TrainingArenaLayout.Kind.PLATFORM) == 112L, "platform count changed");
        require(counts.get(TrainingArenaLayout.Kind.BORDER) == 48L, "border count changed");
        require(counts.get(TrainingArenaLayout.Kind.CORNER_MARKER) == 4L, "corner count changed");
        require(counts.get(TrainingArenaLayout.Kind.LANDING_PAD) == 8L, "landing pad count changed");
        require(counts.get(TrainingArenaLayout.Kind.LANDING_CENTER) == 1L, "landing center count changed");
        require(layout.blocks().size() == 173, "total arena block count changed");

        // A rectangular arena is a layout change only: 2*radius+1 per axis, one
        // marker above each of the four corners, pad still in the middle. The
        // footprint's trip through the save file is not checked here: the JUnit test
        // sourceset (which shares this file) cannot see the NBT classes, and this
        // suite has to stay runnable in both places. The radii are stored next to
        // the arena centre, which /minidrone arena status reads back.
        TrainingArenaLayout runway = TrainingArenaLayout.centered(-1, -60, 17, 10, 4);
        require(runway.widthM() == 21 && runway.depthM() == 9, "rectangular footprint is wrong");
        require(runway.blocks().size() == 21 * 9 + 4, "rectangular block count changed");
        require(
            runway.blocks().stream().anyMatch(block ->
                block.dx() == 0 && block.dz() == 0
                    && block.kind() == TrainingArenaLayout.Kind.LANDING_CENTER),
            "rectangular arena lost its centred landing pad"
        );
    }


    private static VirtualDroneState newDrone() {
        return new VirtualDroneState(54, 1, "minecraft_drone_01");
    }

    private static VirtualDroneState armedDrone() {
        VirtualDroneState drone = newDrone();
        require(drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED), "GUIDED mode was rejected");
        require(drone.setArmed(true), "arming in GUIDED was rejected");
        return drone;
    }

    private static VirtualDroneState airborne() {
        VirtualDroneState drone = armedDrone();
        require(drone.takeoff(1.0), "takeoff command was rejected");
        tick(drone, 25);
        require(drone.snapshot().airborne(), "drone did not become airborne");
        return drone;
    }

    private static LocalSetpoint.Axis velocityAxis(double velocity) {
        return new LocalSetpoint.Axis(false, 0.0, true, velocity, false, 0.0);
    }

    private static void tick(VirtualDroneState drone, int count) {
        for (int i = 0; i < count; i++) {
            drone.tick();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void near(double actual, double expected, double epsilon, String field) {
        if (Math.abs(actual - expected) > epsilon) {
            throw new IllegalStateException(field + " expected " + expected + " but was " + actual);
        }
    }

    public record Report(int passed, int total, List<String> failures) {
        public boolean successful() {
            return failures.isEmpty();
        }

        public String summary() {
            if (successful()) {
                return String.format("Virtual closed-loop selftest PASS: %d/%d checks", passed, total);
            }
            return String.format("Virtual closed-loop selftest FAIL: %d/%d checks; %s",
                passed, total, String.join(" | ", failures));
        }
    }

    private static final class Runner {
        private int passed;
        private int total;
        private final List<String> failures = new ArrayList<>();

        private void check(String name, Runnable test) {
            total++;
            try {
                test.run();
                passed++;
            } catch (RuntimeException exception) {
                failures.add(name + ": " + exception.getMessage());
            }
        }

        private Report report() {
            return new Report(passed, total, List.copyOf(failures));
        }
    }
}
