#!/usr/bin/env node
// Contract verifier: exercises the mod's side of the main-project contract
// against a running isolated backend, without starting Minecraft.
//
// It plays a virtual drone and a virtual motion-capture source on the mod's own
// ports and walks the operator's session:
//
//   1. discovery lists the virtual source as an available candidate
//   2. the control endpoint answers the discovery probe (connect_mocap_source)
//   3. the mocap_relay_health_v1 beacon is accepted by the backend
//   4. the parameter answers complete the backend's fusion configuration
//   5. set_flight_mode reaches the flight controller
//   6. takeoff requests the origin, arms, and the vehicle climbs
//   7. a set_pva_target command arrives as MAVLink message 84
//   8. that frame matches what was requested
//   9. while flying, the beacon keeps the estimator/mocap cross-check inside its
//      window - this is what a too-slow beacon breaks
//  10. battery and attitude reach the published drone model the UI renders
//  11. the published pose follows the documented Local NED -> world mapping
//  12. land reaches the flight controller, which disarms on the ground
//  13. disconnecting the last link holds forwarding
//  14. reconnecting resumes forwarding
//
// Steps 1 and 2 are the frontend's first two clicks, and 6 is the first button an
// operator presses: the backend will not start a takeoff until it has matched the
// indoor Home and GPS_GLOBAL_ORIGIN telemetry, so this covers that exchange too.
//
// Everything here mirrors a Java source of the mod; each value names its origin
// so a change there is visibly a change here too.
//
// Requires Node 22+ (the built-in WebSocket). Start the backend first:
//
//   .\scripts\start-isolated-backend.ps1        # in another terminal
//   node .\scripts\verify-contract.mjs
//
// See docs/main-backend-compatibility.md for the contract and
// docs/live-run-guide.md for the human workflow this checks.

import dgram from 'node:dgram';

// ---------------------------------------------------------------- arguments
const DEFAULTS = {
    wsUrl: 'ws://127.0.0.1:18082',
    mavlinkPort: 14561,
    healthPort: 18151,
    controlPort: 18152,
    // MavlinkTransport.DEFAULT_LOCAL_PORT.
    localPort: 14601,
    // MavlinkTransport.MOCAP_HEALTH_PERIOD_MS.
    beaconMs: 50,
    // How fast the simulated drone flies north; 0 keeps it hovering.
    moveMps: 0,
    attempts: 20
};

function parseArgs(argv) {
    const options = { ...DEFAULTS };
    for (const arg of argv) {
        const split = arg.indexOf('=');
        const key = split === -1 ? arg : arg.slice(0, split);
        const value = split === -1 ? 'true' : arg.slice(split + 1);
        switch (key) {
        case '--ws-url': options.wsUrl = value; break;
        case '--mavlink-port': options.mavlinkPort = Number(value); break;
        case '--health-port': options.healthPort = Number(value); break;
        case '--control-port': options.controlPort = Number(value); break;
        case '--local-port': options.localPort = Number(value); break;
        case '--beacon-ms': options.beaconMs = Number(value); break;
        case '--move-mps': options.moveMps = Number(value); break;
        case '--attempts': options.attempts = Number(value); break;
        case '--help': options.help = true; break;
        default:
            throw new Error(`unknown option ${arg} (try --help)`);
        }
    }
    return options;
}

function usage() {
    console.log(`Usage: node scripts/verify-contract.mjs [options]

  --ws-url=<url>          backend WebSocket (default ${DEFAULTS.wsUrl})
  --mavlink-port=<port>   backend MAVLink udpin port (default ${DEFAULTS.mavlinkPort})
  --health-port=<port>    backend mocap health port (default ${DEFAULTS.healthPort})
  --control-port=<port>   mod control endpoint port (default ${DEFAULTS.controlPort})
  --local-port=<port>     mod MAVLink local port (default ${DEFAULTS.localPort})
  --beacon-ms=<ms>        beacon period to emulate (default ${DEFAULTS.beaconMs})
  --move-mps=<mps>        fly north at this speed (default ${DEFAULTS.moveMps})
  --attempts=<n>          set_pva_target attempts before giving up (default ${DEFAULTS.attempts})
  --help`);
}

// ------------------------------------------------------------ mod identities
// MavlinkTransport / VirtualAutopilot / MocapControlProtocol.
const SYSTEM_ID = 54;
const COMPONENT_ID = 1;
const DRONE_ID = 'minecraft_drone_01';
// VirtualAutopilot#registerParameters.
const PARAMETERS = [
    ['EK3_SRC1_POSXY', 6], ['EK3_SRC1_POSZ', 6], ['EK3_SRC1_VELXY', 6],
    ['EK3_SRC1_VELZ', 6], ['EK3_SRC1_YAW', 6], ['GUID_OPTIONS', 0],
    ['WPNAV_SPEED_UP', 100], ['WPNAV_SPEED_DN', 75]
];
// MocapControlProtocol: command -> [echoed action, resulting forwarding hold].
const CONTROL_ACTIONS = {
    VLT_RELAY_STATUS_V1: ['status', null],
    VLT_RELAY_RECONNECT_V1: ['reconnect', false],
    VLT_RELAY_HOLD_FORWARDING_V1: ['hold', true],
    VLT_RELAY_RESUME_FORWARDING_V1: ['resume', false]
};
// The backend refuses a disconnect while the vehicle is armed, airborne or busy,
// so the verifier lands and disarms before that phase, exactly as an operator
// would. Values are MAV_LANDED_STATE_*.
const LANDED_STATE_ON_GROUND = 1;
const LANDED_STATE_IN_AIR = 2;

// ------------------------------------------------------------ MAVLink v1 wire
const MESSAGE = {
    HEARTBEAT: 0,
    SYS_STATUS: 1,
    SET_MODE: 11,
    PARAM_REQUEST_READ: 20,
    PARAM_REQUEST_LIST: 21,
    PARAM_VALUE: 22,
    ATTITUDE: 30,
    LOCAL_POSITION_NED: 32,
    SET_GPS_GLOBAL_ORIGIN: 48,
    GPS_GLOBAL_ORIGIN: 49,
    COMMAND_LONG: 76,
    COMMAND_ACK: 77,
    SET_POSITION_TARGET_LOCAL_NED: 84,
    EKF_STATUS_REPORT: 193,
    HOME_POSITION: 242,
    EXTENDED_SYS_STATE: 245
};
const CRC_EXTRA = {
    0: 50, 1: 124, 11: 89, 22: 220, 30: 39, 32: 185, 39: 49, 49: 39,
    77: 143, 104: 16, 193: 71, 242: 104, 245: 130
};
// MAV_CMD and ArduCopter custom modes the backend drives the sequence with.
const MAV_CMD_NAV_LAND = 21;
const MAV_CMD_NAV_TAKEOFF = 22;
const MAV_CMD_DO_SET_MODE = 176;
const MAV_CMD_COMPONENT_ARM_DISARM = 400;
const MODE_GUIDED = 4;
// MavlinkMessages indoor origin constants: the backend compares lat/lon to
// +/-1000 e7 and altitude to +/-250 mm of these.
const INDOOR_LATITUDE_E7 = 455000000;
const INDOOR_LONGITUDE_E7 = 1275000000;
const INDOOR_ALTITUDE_MM = 50000;

function crcAccumulate(byte, crc) {
    let tmp = byte ^ (crc & 0xff);
    tmp = (tmp ^ (tmp << 4)) & 0xff;
    return ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xffff;
}

function crcCalculate(bytes, extraCrc) {
    let crc = 0xffff;
    for (const byte of bytes) crc = crcAccumulate(byte, crc);
    return crcAccumulate(extraCrc, crc);
}

function buildFrame(messageId, payload, sequence) {
    const header = Buffer.from([
        0xfe, payload.length & 0xff, sequence & 0xff,
        SYSTEM_ID, COMPONENT_ID, messageId & 0xff
    ]);
    const crc = crcCalculate(
        Buffer.concat([header.subarray(1), payload]), CRC_EXTRA[messageId]);
    return Buffer.concat([header, payload, Buffer.from([crc & 0xff, (crc >> 8) & 0xff])]);
}

// Payload layouts mirror MavlinkMessages; MAVLink v1 truncates trailing
// extension fields, which is what the mod sends.
function heartbeatPayload(vehicle) {
    const payload = Buffer.alloc(9);
    payload.writeUInt32LE(vehicle.customMode >>> 0, 0);
    payload.writeUInt8(2, 4);                    // MAV_TYPE_QUADROTOR
    payload.writeUInt8(3, 5);                    // MAV_AUTOPILOT_ARDUPILOTMEGA
    let baseMode = 0x01;                         // MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
    if (vehicle.guided) baseMode |= 0x08;        // ..._GUIDED_ENABLED
    if (vehicle.armed) baseMode |= 0x80;         // ..._SAFETY_ARMED
    payload.writeUInt8(baseMode, 6);
    payload.writeUInt8(vehicle.armed ? 4 : 3, 7);
    payload.writeUInt8(3, 8);
    return payload;
}

function sysStatusPayload(vehicle) {
    const payload = Buffer.alloc(31);
    const sensorMask = 0x00200000;
    payload.writeUInt32LE(sensorMask, 0);
    payload.writeUInt32LE(sensorMask, 4);
    payload.writeUInt32LE(sensorMask, 8);
    payload.writeUInt16LE(100, 12);                                          // load
    payload.writeUInt16LE(Math.round(7400.0 * 100.0 / 100.0), 14);           // voltage
    payload.writeUInt16LE(vehicle.armed ? 150 : 40, 16);                     // current
    payload.writeUInt8(100, 30);                                            // remaining %
    return payload;
}

// MAVLink time_boot_ms is a 32-bit wrapping counter; `>>> 0` keeps it unsigned
// because JavaScript's bitwise operators are signed.
function bootTimeMs() {
    return Date.now() >>> 0;
}

function attitudePayload(vehicle) {
    const payload = Buffer.alloc(28);
    payload.writeUInt32LE(bootTimeMs(), 0);
    payload.writeFloatLE(0, 4);
    payload.writeFloatLE(0, 8);
    payload.writeFloatLE(vehicle.yaw, 12);
    return payload;
}

function localPositionPayload(north, east, vx, vehicle) {
    const payload = Buffer.alloc(28);
    payload.writeUInt32LE(bootTimeMs(), 0);
    payload.writeFloatLE(north, 4);
    payload.writeFloatLE(east, 8);
    payload.writeFloatLE(vehicle.down, 12);
    payload.writeFloatLE(vx, 16);
    payload.writeFloatLE(0, 20);
    payload.writeFloatLE(0, 24);
    return payload;
}

function extendedSysStatePayload(vehicle) {
    return Buffer.from([0, vehicle.landedState & 0xff]);
}

// VirtualAutopilot#handleSetGpsGlobalOrigin answers with both origin messages;
// the backend will not start a takeoff until it has matched them. Both carry the
// MAVLink 1 body only - no time_usec extension - matching the mod and the
// backend's own encoder.
function gpsGlobalOriginPayload() {
    const payload = Buffer.alloc(12);
    payload.writeInt32LE(INDOOR_LATITUDE_E7, 0);
    payload.writeInt32LE(INDOOR_LONGITUDE_E7, 4);
    payload.writeInt32LE(INDOOR_ALTITUDE_MM, 8);
    return payload;
}

function homePositionPayload() {
    const payload = Buffer.alloc(52);
    payload.writeInt32LE(INDOOR_LATITUDE_E7, 0);
    payload.writeInt32LE(INDOOR_LONGITUDE_E7, 4);
    payload.writeInt32LE(INDOOR_ALTITUDE_MM, 8);
    payload.writeFloatLE(0, 12);
    payload.writeFloatLE(0, 16);
    payload.writeFloatLE(0, 20);
    payload.writeFloatLE(1, 24);      // q[0]
    payload.writeFloatLE(0, 28);
    payload.writeFloatLE(0, 32);
    payload.writeFloatLE(0, 36);
    payload.writeFloatLE(0, 40);
    payload.writeFloatLE(0, 44);
    payload.writeFloatLE(0, 48);
    return payload;
}

function ekfStatusPayload() {
    const payload = Buffer.alloc(22);
    for (let offset = 0; offset < 20; offset += 4) payload.writeFloatLE(0.001, offset);
    payload.writeUInt16LE(47, 20);               // 1 | 2 | 4 | 8 | 32
    return payload;
}

function paramValuePayload(name, value, index) {
    const payload = Buffer.alloc(25);
    payload.writeFloatLE(value, 0);
    payload.writeUInt16LE(PARAMETERS.length, 4);
    payload.writeUInt16LE(index, 6);
    Buffer.from(name, 'ascii').copy(payload, 8, 0, Math.min(name.length, 16));
    payload.writeUInt8(9, 24);                   // MAV_PARAM_TYPE_REAL32
    return payload;
}

function commandAckPayload(command) {
    const payload = Buffer.alloc(3);
    payload.writeUInt16LE(command & 0xffff, 0);
    payload.writeUInt8(0, 2);                    // MAV_RESULT_ACCEPTED
    return payload;
}

// Scanning raw frames keeps this independent of whichever message ids a library
// happens to know about (the shared probe helper drops PARAM_REQUEST_READ).
function scanFrames(buffer) {
    const frames = [];
    let offset = 0;
    while (offset + 8 <= buffer.length) {
        if (buffer[offset] !== 0xfe) {
            offset += 1;
            continue;
        }
        const length = buffer[offset + 1];
        const end = offset + 8 + length;
        if (end > buffer.length) break;
        frames.push({
            messageId: buffer[offset + 5],
            payload: buffer.subarray(offset + 6, offset + 6 + length)
        });
        offset = end;
    }
    return frames;
}

function parsePositionTarget(frame) {
    if (frame.messageId !== MESSAGE.SET_POSITION_TARGET_LOCAL_NED
        || frame.payload.length < 53) {
        return null;
    }
    const payload = frame.payload;
    return {
        x: payload.readFloatLE(4),
        y: payload.readFloatLE(8),
        z: payload.readFloatLE(12),
        vx: payload.readFloatLE(16),
        vy: payload.readFloatLE(20),
        vz: payload.readFloatLE(24),
        afx: payload.readFloatLE(28),
        afy: payload.readFloatLE(32),
        afz: payload.readFloatLE(36),
        yaw: payload.readFloatLE(40),
        yawRate: payload.readFloatLE(44),
        typeMask: payload.readUInt16LE(48),
        targetSystem: payload.readUInt8(50),
        targetComponent: payload.readUInt8(51),
        coordinateFrame: payload.readUInt8(52)
    };
}

// The mod's own golden frames, asserted byte for byte by MavlinkV1CodecTest
// ("MatchesMainProjectCodec"). Reproducing them here proves this verifier speaks
// the mod's exact wire format rather than a lookalike: everything else it
// reports would be worthless if its encoders disagreed with the shipped ones.
const MOD_GOLDEN_FRAMES = [
    {
        name: 'HEARTBEAT',
        hex: 'fe0901360100000000000203010303b70d',
        build: () => buildFrame(
            MESSAGE.HEARTBEAT,
            heartbeatPayload({ armed: false, guided: false, customMode: 0 }),
            1
        )
    },
    {
        name: 'COMMAND_ACK',
        hex: 'fe030a36014d900100da41',
        build: () => buildFrame(MESSAGE.COMMAND_ACK, commandAckPayload(400), 10)
    }
];

// ----------------------------------------------------------------- the run
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// A published drone model, wherever the backend nests it: the first object that
// carries both a battery and a pose.
function findDroneState(value, depth = 0) {
    if (depth > 6 || value === null || typeof value !== 'object') {
        return null;
    }
    if (!Array.isArray(value) && value.battery && value.pose) {
        return value;
    }
    for (const child of Object.values(value)) {
        const found = findDroneState(child, depth + 1);
        if (found) return found;
    }
    return null;
}

async function main() {
    const options = parseArgs(process.argv.slice(2));
    if (options.help) {
        usage();
        return 0;
    }
    if (typeof WebSocket !== 'function') {
        throw new Error('this verifier needs Node 22+ for the built-in WebSocket');
    }

    const checks = [];
    const record = (name, ok, detail) => {
        checks.push({ name, ok, detail });
        console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
    };

    // Offline self-check first: if these disagree, nothing below can be trusted.
    for (const golden of MOD_GOLDEN_FRAMES) {
        const produced = golden.build().toString('hex');
        record(
            `the verifier's ${golden.name} frame matches the mod's golden frame`,
            produced === golden.hex,
            produced === golden.hex
                ? golden.hex
                : `produced ${produced}, the mod sends ${golden.hex}`
        );
    }

    const startedAtMs = Date.now();
    // The cruise only starts once the vehicle is airborne: a vehicle that is
    // already translating cannot pass the takeoff preflight, which is correct
    // behaviour rather than something the verifier should fight.
    let motionStartedAtMs = null;
    // A fixed NED offset used to check the coordinate chain, instead of the cruise.
    let parkedNed = null;
    const northNow = () => (parkedNed !== null
        ? parkedNed.north
        : (motionStartedAtMs === null
            ? 0
            : options.moveMps * (Date.now() - motionStartedAtMs) / 1000));
    const eastNow = () => parkedNed?.east ?? 0;
    const currentVelocity = () => (motionStartedAtMs === null ? 0 : options.moveMps);
    // VirtualDroneState: LANDED -> TAKING_OFF -> FLYING -> LANDING, with the
    // plant's climb and descent rates.
    const vehicle = {
        armed: false,
        guided: false,
        customMode: 0,
        down: 0,
        landedState: LANDED_STATE_ON_GROUND,
        yaw: 0,
        vx: 0,
        climbTargetDown: null,
        landing: false
    };
    const advanceFlight = (deltaMs) => {
        if (vehicle.climbTargetDown !== null) {
            vehicle.down = Math.max(
                vehicle.climbTargetDown, vehicle.down - 0.8 * deltaMs / 1000);
            vehicle.landedState = 3;                       // MAV_LANDED_STATE_TAKEOFF
            if (vehicle.down <= vehicle.climbTargetDown + 0.01) {
                vehicle.down = vehicle.climbTargetDown;
                vehicle.climbTargetDown = null;
                vehicle.landedState = LANDED_STATE_IN_AIR;
            }
        } else if (vehicle.landing) {
            vehicle.down = Math.min(0, vehicle.down + 0.6 * deltaMs / 1000);
            vehicle.landedState = 4;                       // MAV_LANDED_STATE_LANDING
            if (vehicle.down >= -0.01) {
                vehicle.down = 0;
                vehicle.landing = false;
                vehicle.armed = false;
                vehicle.landedState = LANDED_STATE_ON_GROUND;
            }
        }
    };

    let held = false;
    const controlCommands = [];
    const flightCommands = [];
    let sequence = 0;
    let pvaFrame = null;
    let latestFusion = null;
    let latestDroneState = null;
    let lastTakeoffOutcome = null;
    let lastDisconnectOutcome = null;
    let beaconsSent = 0;
    const commandResults = new Map();

    const mavlinkSocket = dgram.createSocket('udp4');
    const healthSocket = dgram.createSocket('udp4');
    const controlSocket = dgram.createSocket('udp4');
    const closers = [
        () => mavlinkSocket.close(),
        () => healthSocket.close(),
        () => controlSocket.close()
    ];

    // MavlinkTransport.mocapHealthPayload, field for field.
    const healthBeacon = () => '{"schema":"mocap_relay_health_v1",'
        + '"source_mode":"minecraft_virtual",'
        + `"wall_time_unix_us":${Date.now() * 1000},`
        + '"healthy":true,"safety_latched":false,'
        + '"position_only":false,"attitude_source":"hybrid",'
        + '"fusion_mode":"flight_controller_roll_pitch_external_nav_position_yaw",'
        + '"roll_pitch_source":"flight_controller",'
        + '"yaw_source":"motion_capture_external_nav",'
        + `"expected_drone_id":"${DRONE_ID}","tracking_age_ms":0.0,`
        + `"forward_rate_hz":${(1000 / options.beaconMs).toFixed(1)},"orientation_held":false,`
        + '"tracking_holdover_active":false,'
        + `"forwarding_held":${held},"forwarding_hold_reason":"${held ? 'backend_request' : ''}",`
        + `"last_forwarded_pose":{"position_m":[${northNow().toFixed(6)},0.000000,`
        + `${vehicle.down.toFixed(6)}],`
        + '"roll_pitch_yaw_rad":[0.000000,0.000000,0.000000]}}';

    const send = (messageId, payload) => mavlinkSocket.send(
        buildFrame(messageId, payload, sequence = (sequence + 1) & 0xff),
        options.mavlinkPort,
        '127.0.0.1'
    );

    // Every timer is registered so the finally block can stop it.
    const every = (periodMs, action) => {
        const handle = setInterval(action, periodMs);
        closers.push(() => clearInterval(handle));
    };

    // The mod's own cadences: MavlinkTransport periods.
    const startTelemetry = () => {
        // FAST_TELEMETRY_PERIOD_MS
        every(50, () => {
            advanceFlight(50);
            send(MESSAGE.ATTITUDE, attitudePayload(vehicle));
            send(MESSAGE.LOCAL_POSITION_NED,
                localPositionPayload(northNow(), eastNow(), currentVelocity(), vehicle));
        });
        every(200, () => send(                                    // EXTENDED_STATE_PERIOD_MS
            MESSAGE.EXTENDED_SYS_STATE, extendedSysStatePayload(vehicle)));
        // SLOW_TELEMETRY_PERIOD_MS
        every(500, () => {
            send(MESSAGE.SYS_STATUS, sysStatusPayload(vehicle));
            send(MESSAGE.EKF_STATUS_REPORT, ekfStatusPayload());
        });
        every(1000, () => send(                                   // HEARTBEAT_PERIOD_MS
            MESSAGE.HEARTBEAT, heartbeatPayload(vehicle)));
        every(options.beaconMs, () => {                           // MOCAP_HEALTH_PERIOD_MS
            healthSocket.send(
                Buffer.from(healthBeacon(), 'utf8'), options.healthPort, '127.0.0.1');
            beaconsSent += 1;
        });
    };

    // The mod's control endpoint.
    controlSocket.on('message', (request, rinfo) => {
        const commandText = Buffer.from(request).toString('ascii').replace(/[\r\n]+$/, '');
        const entry = CONTROL_ACTIONS[commandText];
        if (!entry) return;
        const [action, holdChange] = entry;
        if (holdChange !== null) held = holdChange;
        controlCommands.push({ command: commandText, action, forwarding_held: held });
        controlSocket.send(
            Buffer.from('{"schema":"mocap_relay_control_v1","ok":true,'
                + `"action":"${action}",`
                + '"message":"virtual motion-capture source is online",'
                + `"source_packet_age_ms":0,"safety_latched":false,"forwarding_held":${held}}`),
            rinfo.port,
            rinfo.address
        );
    });

    // VirtualAutopilot: answer what the backend asks for. Replies mirror
    // VirtualAutopilot#handleCommandLong / handleSetMode / handleSetGpsGlobalOrigin.
    mavlinkSocket.on('message', (message) => {
        for (const frame of scanFrames(message)) {
            if (frame.messageId === MESSAGE.SET_POSITION_TARGET_LOCAL_NED) {
                pvaFrame = parsePositionTarget(frame);
                continue;
            }
            if (frame.messageId === MESSAGE.PARAM_REQUEST_LIST) {
                PARAMETERS.forEach(([name, value], index) =>
                    send(MESSAGE.PARAM_VALUE, paramValuePayload(name, value, index)));
                continue;
            }
            if (frame.messageId === MESSAGE.PARAM_REQUEST_READ) {
                const name = Buffer.from(frame.payload.subarray(4, 20))
                    .toString('ascii').replace(/\0.*$/, '');
                const index = PARAMETERS.findIndex(([id]) => id === name);
                if (index >= 0) {
                    send(MESSAGE.PARAM_VALUE,
                        paramValuePayload(name, PARAMETERS[index][1], index));
                }
                continue;
            }
            // The backend asks for the indoor origin before it will take off.
            if (frame.messageId === MESSAGE.SET_GPS_GLOBAL_ORIGIN) {
                flightCommands.push('set_gps_global_origin');
                send(MESSAGE.GPS_GLOBAL_ORIGIN, gpsGlobalOriginPayload());
                send(MESSAGE.HOME_POSITION, homePositionPayload());
                continue;
            }
            if (frame.messageId === MESSAGE.SET_MODE && frame.payload.length >= 6) {
                const customMode = frame.payload.readUInt32LE(0);
                flightCommands.push(`set_mode:${customMode}`);
                if (customMode === MODE_GUIDED) {
                    vehicle.guided = true;
                    vehicle.customMode = MODE_GUIDED;
                }
                continue;
            }
            if (frame.messageId === MESSAGE.COMMAND_LONG && frame.payload.length >= 33) {
                const command = frame.payload.readUInt16LE(28);
                const params = [];
                for (let index = 0; index < 7; index++) {
                    params.push(frame.payload.readFloatLE(index * 4));
                }
                flightCommands.push(`command_long:${command}`);
                switch (command) {
                case MAV_CMD_DO_SET_MODE:
                    if (Math.round(params[1]) === MODE_GUIDED) {
                        vehicle.guided = true;
                        vehicle.customMode = MODE_GUIDED;
                    }
                    break;
                case MAV_CMD_COMPONENT_ARM_DISARM:
                    vehicle.armed = params[0] >= 0.5;
                    break;
                case MAV_CMD_NAV_TAKEOFF: {
                    const altitude = params[6] > 0.3 ? params[6] : 2.0;
                    vehicle.climbTargetDown = -altitude;
                    break;
                }
                case MAV_CMD_NAV_LAND:
                    vehicle.customMode = 9;
                    if (!vehicle.armed && vehicle.down >= 0) {
                        vehicle.landing = false;
                    } else {
                        vehicle.landing = true;
                    }
                    break;
                default:
                    break;
                }
                send(MESSAGE.COMMAND_ACK, commandAckPayload(command));
            }
        }
    });

    const listen = (socket, port) => new Promise((resolve, reject) => {
        socket.once('error', reject);
        socket.bind(port, '127.0.0.1', resolve);
    });

    try {
        await listen(mavlinkSocket, options.localPort).catch((error) => {
            throw new Error(`cannot bind the mod's MAVLink local port ${options.localPort}: `
                + `${error.message}. Stop Minecraft, or pass --local-port=0.`);
        });
        await listen(healthSocket, 0);
        await listen(controlSocket, options.controlPort).catch((error) => {
            throw new Error(`cannot bind the control port ${options.controlPort}: ${error.message}`);
        });
        startTelemetry();

        // ---- the backend
        let socket = null;
        const pending = new Map();
        let commandCounter = 0;

        await new Promise((resolve, reject) => {
            socket = new WebSocket(options.wsUrl);
            closers.push(() => socket.close());
            socket.addEventListener('open', resolve);
            socket.addEventListener('error', () => reject(new Error(
                `cannot connect to ${options.wsUrl}; start the isolated backend first`)));
            socket.addEventListener('message', (event) => {
                let message;
                try { message = JSON.parse(String(event.data)); } catch { return; }
                const stability = message?.payload?.data?.adapter?.takeoff_stability;
                if (stability) {
                    latestFusion = {
                        beacon_received: stability.mocap?.beacon_received,
                        horizontal_stable:
                            stability.mocap?.external_nav_fusion?.horizontal_stable,
                        blocking: stability.mocap?.external_nav_fusion?.horizontal_blocking,
                        estimator_error_m: stability.mocap?.estimator_consistency?.error_m,
                        fusion_configuration: stability.mocap?.fusion_configuration
                    };
                }
                const payload = message.payload || {};
                // The published drone model is what the UI renders; find it
                // wherever the backend nests it.
                const drone = findDroneState(message.payload?.data) ?? findDroneState(payload);
                if (drone) {
                    latestDroneState = drone;
                }
                // A long-running command (takeoff) reports intermediate updates
                // and may finish with a failure, so keep every result.
                const entry = commandResults.get(payload.command_id);
                if (entry && payload.status !== undefined) {
                    entry.results.push(payload);
                }
                // Each command first gets a gateway ack that carries no status.
                const resolvePending = pending.get(payload.command_id);
                if (resolvePending && payload.status !== undefined) {
                    pending.delete(payload.command_id);
                    resolvePending(payload);
                }
            });
        });

        const command = (commandType, params, target, timeoutMs = 20000, soft = false) => {
            const commandId = `verify-${commandType}-${++commandCounter}`;
            commandResults.set(commandId, { results: [] });
            return new Promise((resolve, reject) => {
                const timer = setTimeout(() => {
                    pending.delete(commandId);
                    if (soft) {
                        resolve({ commandId, status: 'no_result', message: 'no result before the timeout' });
                        return;
                    }
                    reject(new Error(`${commandType} produced no result in ${timeoutMs} ms`));
                }, timeoutMs);
                pending.set(commandId, (payload) => {
                    clearTimeout(timer);
                    resolve(payload);
                });
                socket.send(JSON.stringify({
                    schema_version: 'v1',
                    message_type: 'command',
                    meta: {
                        frame_id: 0,
                        trace_id: commandId,
                        source_id: 'mini-drone-system-mod.verify-contract',
                        sim_time_us: 0,
                        wall_time_unix_us: Date.now() * 1000
                    },
                    payload: {
                        command_id: commandId,
                        target,
                        command_type: commandType,
                        params,
                        priority: 'normal',
                        issuer: 'mini-drone-system-mod.verify-contract',
                        expect_ack: true
                    }
                }));
            });
        };

        // The last thing the backend said about a command, not the first.
        const outcome = (result) => {
            const results = commandResults.get(result?.commandId)?.results ?? [];
            return results[results.length - 1] ?? result ?? {};
        };

        // A command can be refused with slot_busy while the previous one is still
        // waiting for heartbeat confirmation, which is what an operator resolves
        // by clicking again.
        const commandWithRetries = async (
            commandType, params, target,
            { timeoutMs = 30000, attempts = 3, retryStatuses = ['slot_busy'] } = {}
        ) => {
            let result = null;
            for (let attempt = 0; attempt < attempts; attempt++) {
                result = await command(commandType, params, target, timeoutMs, true);
                if (!retryStatuses.includes(outcome(result).status)) return result;
                await sleep(2000);
            }
            return result;
        };

        const droneTarget = { scope: 'drone', ids: [DRONE_ID] };
        const systemTarget = { scope: 'system', ids: [] };
        const waitUntil = async (predicate, timeoutMs, label) => {
            const deadline = Date.now() + timeoutMs;
            while (Date.now() < deadline) {
                if (predicate()) return true;
                await sleep(100);
            }
            throw new Error(`timed out after ${timeoutMs} ms waiting for ${label}`);
        };

        // 1. discovery, which is what puts the candidate in front of the operator.
        // Without it there is nothing to click, however healthy the source is.
        const discovery = await command(
            'discover_mocap_sources', {}, systemTarget, 15000, true);
        const candidates = discovery.data?.candidates ?? [];
        const virtualCandidate = candidates.find(
            (candidate) => candidate.profile_id === 'minecraft_virtual_mocap');
        record(
            'discovery lists the virtual source as an available candidate',
            virtualCandidate?.available === true
                && virtualCandidate?.mode === 'virtual'
                && virtualCandidate?.control_port === options.controlPort,
            virtualCandidate
                ? `${virtualCandidate.profile_id} (${virtualCandidate.origin}) available=`
                    + `${virtualCandidate.available}, control=`
                    + `${virtualCandidate.control_host}:${virtualCandidate.control_port}`
                : `no minecraft_virtual_mocap candidate among ${candidates.length}; `
                    + `saw ${candidates.map((candidate) => candidate.profile_id).join(', ')}`
        );

        // 2. selecting it, as the frontend's "connect" does
        const sourceResult = await command('connect_mocap_source', {
            mode: 'virtual',
            profile_id: 'minecraft_virtual_mocap',
            source_host: '127.0.0.1',
            source_port: 15150,
            health_host: '127.0.0.1',
            health_port: options.healthPort,
            control_host: '127.0.0.1',
            control_port: options.controlPort
        }, systemTarget, 10000);
        record(
            'control endpoint answers the discovery probe',
            sourceResult.status === 'source_connected',
            `${sourceResult.status}: ${sourceResult.message}`
        );

        // 3. the beacon, before anything can be commanded
        await sleep(1500);
        record(
            'health beacon accepted',
            latestFusion?.beacon_received === true,
            latestFusion?.beacon_received === true
                ? `expected_drone_id ${DRONE_ID}, ${beaconsSent} beacons sent`
                : 'the backend reports no beacon: check expected_drone_id and the port'
        );

        // 4. the parameter inventory. The backend drains 69 queued reads at
        // 300 ms each, and both the PVA admission and the takeoff preflight need
        // EK3_SRC1_POSXY, POSZ and YAW before they will let anything through.
        const inventoryStartedAtMs = Date.now();
        const inventoryComplete = await waitUntil(
            () => {
                const sources = latestFusion?.fusion_configuration;
                return sources?.position_xy_source === 6
                    && sources?.position_z_source === 6
                    && sources?.yaw_source === 6;
            },
            60000,
            'the backend to confirm EK3_SRC1_POSXY/POSZ/YAW from the parameter answers'
        ).then(() => true).catch(() => false);
        const sources = latestFusion?.fusion_configuration;
        record(
            'parameter answers complete the backend fusion configuration',
            inventoryComplete,
            sources
                ? `EK3_SRC1_POSXY=${sources.position_xy_source}, `
                    + `POSZ=${sources.position_z_source}, YAW=${sources.yaw_source}; `
                    + `took ${((Date.now() - inventoryStartedAtMs) / 1000).toFixed(1)} s`
                : 'the backend published no fusion configuration'
        );

        // 5. the operator switches the vehicle to GUIDED
        const guidedResult = await command('set_flight_mode', { mode: 'GUIDED' }, droneTarget, 15000);
        const guidedOk = await waitUntil(
            () => vehicle.guided, 10000, 'the backend to request GUIDED')
            .then(() => true).catch(() => false);
        record(
            'set_flight_mode reaches the flight controller',
            guidedOk && vehicle.guided,
            `${outcome(guidedResult).status}: ${outcome(guidedResult).message}; `
                + `mode commands seen: ${flightCommands.filter((entry) => entry.startsWith('set_mode')).join(', ') || 'none'}`
        );

        // 6. takeoff: the backend asks for the indoor origin first, then drives
        // GUIDED -> ARM -> TAKEOFF as one sequence.
        const takeoffResult = await commandWithRetries(
            'takeoff', {}, droneTarget, { timeoutMs: 45000 });
        const airborne = await waitUntil(
            () => vehicle.down <= -0.5, 30000, 'the vehicle to become airborne')
            .then(() => true).catch(() => false);
        const sawArm = flightCommands.includes(`command_long:${MAV_CMD_COMPONENT_ARM_DISARM}`);
        const sawTakeoff = flightCommands.includes(`command_long:${MAV_CMD_NAV_TAKEOFF}`);
        const sawOrigin = flightCommands.includes('set_gps_global_origin');
        lastTakeoffOutcome = outcome(takeoffResult);
        record(
            'takeoff requests the origin, arms, and the vehicle climbs',
            airborne && sawArm && sawTakeoff && sawOrigin,
            `climbed to ${vehicle.down.toFixed(2)} m; last result ${outcome(takeoffResult).status}: `
                + `${outcome(takeoffResult).message}`
        );
        if (airborne && options.moveMps !== 0) {
            motionStartedAtMs = Date.now();
        }

        // 7+8. a PVA setpoint while airborne, then the emitted frame
        const admissionStartedAtMs = Date.now();
        // Hold the reference level: the vehicle keeps climbing while the command
        // is in flight, so the frame has to be compared with what was sent.
        const requestedDown = vehicle.down;
        let attemptsUsed = 0;
        let lastStatus = 'none';
        for (let attempt = 0; attempt < options.attempts && !pvaFrame; attempt++) {
            attemptsUsed = attempt + 1;
            try {
                const result = await command('set_pva_target', {
                    reference_frame: 'local_ned',
                    position: { x: 0, y: 0, z: requestedDown },
                    velocity: { x: 0, y: 0, z: 0 },
                    acceleration: { x: 0, y: 0, z: 0 }
                }, droneTarget, 8000, true);
                lastStatus = `${result.status}: ${result.message}`;
            } catch (error) {
                lastStatus = error.message;
            }
            if (!pvaFrame) await sleep(500);
        }
        // The backend drains its parameter inventory at 300 ms per request before
        // it can confirm external-navigation fusion, so the first seconds after a
        // link comes up are refused by design (see the compatibility doc, 6.2).
        const admissionSeconds = (Date.now() - admissionStartedAtMs) / 1000;

        record(
            'set_pva_target admitted and emitted as MAVLink message 84',
            pvaFrame !== null,
            pvaFrame
                ? `after ${admissionSeconds.toFixed(1)} s and ${attemptsUsed} attempt(s), `
                    + `type_mask ${pvaFrame.typeMask}`
                : `last result was ${lastStatus}`
        );
        if (pvaFrame) {
            record(
                'the emitted frame matches the request',
                pvaFrame.typeMask === 3072
                    && pvaFrame.coordinateFrame === 1
                    && pvaFrame.targetSystem === SYSTEM_ID
                    && pvaFrame.targetComponent === COMPONENT_ID
                    && Math.abs(pvaFrame.z - requestedDown) < 1e-3,
                `type_mask ${pvaFrame.typeMask}, frame ${pvaFrame.coordinateFrame}, `
                    + `target ${pvaFrame.targetSystem}/${pvaFrame.targetComponent}`
            );
        }

        // 9. the cross-check the beacon period has to stay inside
        const horizontalError = Math.abs(latestFusion?.estimator_error_m?.horizontal ?? NaN);
        const verticalError = Math.abs(latestFusion?.estimator_error_m?.vertical ?? NaN);
        record(
            `estimator/mocap cross-check stays inside its window at ${options.moveMps} m/s`,
            options.moveMps === 0
                || (latestFusion?.horizontal_stable === true && horizontalError < 0.10),
            options.moveMps === 0
                ? 'stationary, so the horizontal window is not exercised '
                    + '(pass --move-mps=1.4 to exercise it)'
                : `horizontal ${horizontalError.toFixed(4)} m of 0.10 m, `
                    + `vertical ${verticalError.toFixed(4)} m of 0.08 m, beacon ${options.beaconMs} ms`
        );

        // 10+11. park at a known NED offset and heading, then check what the UI model
        // receives. The backend publishes its world frame, which the mod documents
        // as world.x = -east, world.y = -down, world.z = -north.
        parkedNed = { north: 2.0, east: 1.0 };
        vehicle.down = -1.5;
        vehicle.climbTargetDown = null;
        vehicle.yaw = Math.PI / 2;
        await sleep(2500);

        const batteryPercent = latestDroneState?.battery?.percent;
        const batteryVolts = latestDroneState?.battery?.voltage;
        const orientation = latestDroneState?.pose?.orientation;
        // Euler (0, 0, pi/2) in NED is q = (0, 0, sin(pi/4), cos(pi/4)).
        const expectedQuaternionComponent = Math.sin(Math.PI / 4);
        record(
            'battery and attitude reach the published drone model',
            Math.abs(batteryPercent - 100) < 1e-6
                && Math.abs(batteryVolts - 7.4) < 0.01
                && Math.abs(orientation?.z - expectedQuaternionComponent) < 1e-3
                && Math.abs(orientation?.w - expectedQuaternionComponent) < 1e-3,
            `battery ${batteryPercent}% / ${batteryVolts} V from SYS_STATUS; `
                + `orientation (x=${orientation?.x?.toFixed(4)}, y=${orientation?.y?.toFixed(4)}, `
                + `z=${orientation?.z?.toFixed(4)}, w=${orientation?.w?.toFixed(4)}) for yaw 90 deg`
        );

        const published = latestDroneState?.pose?.position;
        const mappingOk = Math.abs(published?.x - (-1.0)) < 1e-3
            && Math.abs(published?.y - 1.5) < 1e-3
            && Math.abs(published?.z - (-2.0)) < 1e-3;
        record(
            'the published pose follows the documented NED mapping',
            mappingOk,
            `NED (north 2, east 1, down -1.5) -> world (${published?.x?.toFixed(3)}, `
                + `${published?.y?.toFixed(3)}, ${published?.z?.toFixed(3)}), expected (-1, 1.5, -2)`
        );

        // 12. land, which also leaves the vehicle in the state a disconnect needs
        const landResult = await command('land', {}, droneTarget, 30000, true);
        const landed = await waitUntil(
            () => !vehicle.armed && vehicle.down >= -0.01, 20000, 'the vehicle to land and disarm')
            .then(() => true).catch(() => false);
        record(
            'land reaches the flight controller and the vehicle disarms on the ground',
            landed,
            `down ${vehicle.down.toFixed(2)} m, armed ${vehicle.armed}; `
                + `last result ${landResult.status}`
        );

        // 13+14. the hold the backend asks for around a disconnect. Give it a
        // moment first: a disconnect is refused while a command is still in flight.
        await sleep(2500);
        const disconnect = await command('disconnect_mavlink_drone', {
            endpoint_url: `udpin://127.0.0.1:${options.mavlinkPort}`
        }, systemTarget, 15000);
        lastDisconnectOutcome = outcome(disconnect);
        const holdRequested = controlCommands.some(
            (entry) => entry.action === 'hold' && entry.forwarding_held === true);
        record(
            'disconnecting the last link holds forwarding',
            disconnect.data?.forwarding_hold?.status === 'held' && holdRequested,
            `backend reported ${disconnect.data?.forwarding_hold?.status ?? lastDisconnectOutcome.status}; `
                + `control endpoint saw ${controlCommands.map((entry) => entry.action).join(' -> ')}`
        );

        await command('connect_mavlink_drone', {
            endpoint_url: `udpin://127.0.0.1:${options.mavlinkPort}`
        }, systemTarget, 15000, true);
        await sleep(1500);
        record(
            'reconnecting resumes forwarding',
            controlCommands.some((entry) => entry.action === 'resume'),
            `control endpoint saw ${controlCommands.map((entry) => entry.action).join(' -> ')}`
        );
    } finally {
        for (const close of closers.reverse()) {
            try { close(); } catch { /* already closed */ }
        }
    }

        const failed = checks.filter((check) => !check.ok);
    console.log('');
    console.log(JSON.stringify({
        ok: failed.length === 0,
        checks: checks.length,
        failed: failed.map((check) => check.name),
        beacons_sent: beaconsSent,
        flight_commands: flightCommands,
        control_commands: controlCommands,
        fusion: latestFusion,
        takeoff: lastTakeoffOutcome,
        disconnect: lastDisconnectOutcome,
        frame: pvaFrame
    }, null, 2));
    return failed.length === 0 ? 0 : 1;
}

main()
    .then((code) => process.exit(code))
    .catch((error) => {
        console.error(`verify-contract: ${error.message}`);
        process.exit(2);
    });
