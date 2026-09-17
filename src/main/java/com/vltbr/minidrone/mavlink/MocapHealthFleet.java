package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import com.vltbr.minidrone.sim.VirtualDroneState;
import java.util.List;
import java.util.Locale;

/**
 * Adds the fleet block to the health beacon when there is more than one drone.
 *
 * <p>The beacon has always described one vehicle: one {@code expected_drone_id}, one
 * {@code last_source_pose}, one {@code safety_latched}. Making the source multi-drone means
 * the monitoring side has to be able to tell which vehicle each of those belongs to, so a
 * {@code drones} array is appended carrying every drone's id, pose and latch.
 *
 * <p>Two rules make this safe to ship before the backend knows about it:
 *
 * <ul>
 *   <li>with one drone the payload is returned <em>byte for byte</em> as it was built. Every
 *       existing setup - and every existing parser - therefore sees exactly what it saw
 *       before, and the real mocap path never comes near this code at all;</li>
 *   <li>the block is appended inside the payload's closing brace rather than woven into the
 *       builder's format string, so the existing fields and their order are untouched.</li>
 * </ul>
 *
 * <p>The block is JSON, matching the rest of the beacon, and the source frame of each pose is
 * the same room-facing frame the single-drone {@code last_source_pose} uses: source
 * {@code (x, y, z)} is NED {@code (-east, -north, -down)}.
 */
public final class MocapHealthFleet {
    private MocapHealthFleet() {
    }

    /** The key the backend reads for the per-drone block. */
    public static final String FLEET_KEY = "drones";

    public static String appendFleetDrones(String payload, List<VirtualDroneState> fleet) {
        if (payload == null || fleet == null || fleet.size() <= 1) {
            return payload;
        }
        int closingBrace = payload.lastIndexOf('}');
        if (closingBrace < 0) {
            return payload;
        }
        String block = String.format(Locale.ROOT, "\"%s\":[%s]", FLEET_KEY, entries(fleet));
        return payload.substring(0, closingBrace) + "," + block + payload.substring(closingBrace);
    }

    private static String entries(List<VirtualDroneState> fleet) {
        StringBuilder builder = new StringBuilder();
        for (VirtualDroneState drone : fleet) {
            if (drone == null) {
                continue;
            }
            VirtualDroneSnapshot state = drone.snapshot();
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(String.format(
                Locale.ROOT,
                "{\"drone_id\":%s,\"system_id\":%d,\"component_id\":%d,"
                    + "\"safety_latched\":%s,\"armed\":%s,"
                    + "\"position_m\":[%.6f,%.6f,%.6f],"
                    + "\"roll_pitch_yaw_rad\":[%.6f,%.6f,%.6f]}",
                MavlinkTransport.jsonString(state.droneId()),
                state.systemId(),
                state.componentId(),
                drone.safetyLatched(),
                state.armed(),
                -state.eastM(),
                -state.northM(),
                state.downM(),
                state.rollRad(),
                state.pitchRad(),
                state.yawRad()
            ));
        }
        return builder.toString();
    }
}
