package com.vltbr.minidrone.world;

import com.vltbr.minidrone.MiniDroneMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;
import java.util.Optional;

/**
 * The training field in effect for this world, and the ways to define it.
 *
 * <p>It answers two questions for the rest of the mod: where the virtual world
 * origin is, and what field the motion-capture beacon advertises. Both come from
 * {@link #definition()}, which applies the precedence rule in
 * {@link TrainingFieldResolution}. Everything the operator sees in game - the
 * commands, the marker blocks and the selector item - goes through here, so the
 * reported information and the advertised field can never drift apart.
 */
public final class TrainingFieldController {
    private final MinecraftServer server;
    private final TrainingArenaController arenaController;
    private final TrainingFieldSavedData savedData;
    private final TrainingFieldDefinition instanceDefault;

    public TrainingFieldController(MinecraftServer server) {
        this(server, new TrainingArenaController(server));
    }

    public TrainingFieldController(MinecraftServer server, TrainingArenaController arenaController) {
        this.server = server;
        this.arenaController = arenaController;
        this.savedData = server.overworld().getDataStorage()
            .computeIfAbsent(TrainingFieldSavedData.factory(), TrainingFieldSavedData.DATA_ID);
        this.instanceDefault = TrainingFieldDefaults.fromSystemProperties().orElse(null);
    }

    public TrainingArenaController arena() {
        return arenaController;
    }

    /** The field the operator defined by hand, or null. */
    public TrainingFieldDefinition manualField() {
        TrainingFieldDefinition stored = savedData.field();
        return stored == null ? null : withGroundResolved(stored);
    }

    /** The field in effect: hand-made, else the generated arena, else the defaults. */
    public TrainingFieldDefinition definition() {
        return withGroundResolved(TrainingFieldResolution.resolve(
            savedData.field(),
            arenaController.info(),
            instanceDefault
        ));
    }

    public boolean hasField() {
        return definition() != null;
    }

    /** Records the operator's definition and returns it. */
    public TrainingFieldDefinition defineManually(TrainingFieldDefinition field) {
        TrainingFieldDefinition resolved = withGroundResolved(field);
        savedData.setField(resolved);
        MiniDroneMod.LOGGER.info("Training field defined by hand: {}", resolved.describe());
        return resolved;
    }

    /** Moves the origin of the field in effect; returns null when there is none. */
    public TrainingFieldDefinition setOrigin(
        TrainingFieldDefinition.OriginMode mode, Double explicitX, Double explicitZ
    ) {
        TrainingFieldDefinition current = definition();
        if (current == null) {
            return null;
        }
        TrainingFieldDefinition updated = switch (mode) {
            case CENTRE -> current.withOrigin(current.centreX(), current.centreZ(), mode);
            case CORNER -> current.withOriginAtCorner();
            case EXPLICIT -> current.withOrigin(explicitX, explicitZ, mode);
        };
        return defineManually(updated.withSource(current.source()));
    }

    /** Drops the hand-made field; the generated arena (if any) takes over again. */
    public boolean clearManual() {
        boolean had = savedData.hasField();
        savedData.clearField();
        if (had) {
            MiniDroneMod.LOGGER.info("Hand-made training field cleared");
        }
        return had;
    }

    /**
     * Fills in a "whatever the ground is" surface layer: the field plane is the top
     * solid block under the centre, so the origin one block above it is where a
     * drone sits after landing.
     */
    private TrainingFieldDefinition withGroundResolved(TrainingFieldDefinition field) {
        if (field == null || field.topY() != TrainingFieldDefaults.DERIVE_Y_FROM_GROUND) {
            return field;
        }
        int centerX = (int) Math.floor(field.centreX());
        int centerZ = (int) Math.floor(field.centreZ());
        int height = server.overworld().getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        return new TrainingFieldDefinition(
            field.minX(), field.minZ(), field.maxX(), field.maxZ(), height - 1,
            field.originX(), field.originZ(), field.originMode(), field.source());
    }

    /** The origin the virtual world should use, or null when there is no field. */
    public NedWorldTransform originTransform() {
        TrainingFieldDefinition field = definition();
        return field == null ? null : field.originTransform();
    }

    /** Builds a field from two corners of the block the operator pointed at. */
    public TrainingFieldDefinition defineFromCorners(BlockPos first, BlockPos second) {
        return defineManually(TrainingFieldDefinition.fromCorners(
            first.getX(), first.getZ(), second.getX(), second.getZ(),
            Math.min(first.getY(), second.getY()),
            TrainingFieldDefinition.Source.CORNERS
        ));
    }

    /** Builds a field from a centre block plus its size in metres. */
    public TrainingFieldDefinition defineFromCentreAndSize(
        BlockPos center, int widthM, int depthM
    ) {
        return defineManually(TrainingFieldDefinition.fromCentreAndSize(
            center.getX(), center.getZ(), widthM, depthM, center.getY(),
            TrainingFieldDefinition.Source.CENTRE_AND_SIZE
        ));
    }

    /** The field plane under a player, used by commands that only get a position. */
    public int groundTopY(ServerPlayer player) {
        return server.overworld().getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            player.getBlockX(), player.getBlockZ()) - 1;
    }

    /**
     * Everything the operator can ask about the field, in one message.
     *
     * <p>It reports what is advertised as well as what is defined, because the two
     * are what the monitoring side sees; a field that is defined but not advertised
     * would look identical on screen otherwise.
     */
    public String describe() {
        TrainingFieldDefinition field = definition();
        StringBuilder report = new StringBuilder();
        if (field == null) {
            report.append("Training field: none defined.");
            report.append(" Describe one with /minidrone field set corners|center,");
            report.append(" or place field markers, or start the instance with -D")
                .append(TrainingFieldDefaults.describeProperties()).append('.');
            report.append(" origin=").append(describeOrigin()).append('.');
            return report.toString();
        }
        report.append("Training field: ").append(field.describe()).append('.');
        double[] offset = field.centerOffsetM();
        report.append(String.format(
            Locale.ROOT,
            " advertised as field_size_m=[%d,%d] centred_at_origin=%s field_center_m=[%.2f,%.2f].",
            field.widthM(), field.depthM(), field.isCentred(), offset[0], offset[1]));
        report.append(String.format(
            Locale.ROOT,
            " NED origin: world (%.2f, %.2f, %.2f) = LOCAL_POSITION_NED (0, 0, 0).",
            field.originX(), field.topY() + ArenaOrigin.PAD_SURFACE_OFFSET_M, field.originZ()));
        report.append(" ").append(describeOrigin()).append('.');
        return report.toString();
    }

    private String describeOrigin() {
        TrainingFieldDefinition field = definition();
        if (field != null) {
            return "origin rule=field " + field.originMode();
        }
        if (arenaController.hasArena()) {
            return "origin rule=arena centre";
        }
        if (instanceDefault != null) {
            return "origin rule=instance default";
        }
        return "origin rule=in front of the player";
    }

    /** Short one-line summary for the arena/origin commands. */
    public String summary() {
        TrainingFieldDefinition field = definition();
        if (field == null) {
            return "no field";
        }
        return String.format(
            Locale.ROOT, "%dx%d m, origin=(%.1f, %.1f) %s, source=%s",
            field.widthM(), field.depthM(), field.originX(), field.originZ(),
            field.originMode(), field.source());
    }

    /** True when the field came from the operator rather than from the arena. */
    public boolean hasManualField() {
        return savedData.hasField();
    }

    /** The field the beacon would advertise, for logging and command output. */
    public Optional<TrainingFieldDefinition> advertised() {
        return Optional.ofNullable(definition());
    }
}
