#!/usr/bin/env node
// Contract verifier: exercises the mod's side of the main-project contract
// against a running isolated backend, without starting Minecraft.
//
// It plays a virtual drone and a virtual motion-capture source on the mod's own
// ports and asserts, in order:
//
//   1. the control endpoint answers the discovery probe (connect_mocap_source)
//   2. the mocap_relay_health_v1 beacon is accepted by the backend
//   3. the parameter answers reach the backend's fusion configuration
//   4. a set_pva_target command arrives as MAVLink message 84
//   5. the emitted frame matches the request
//   6. while flying, the beacon keeps the estimator/mocap cross-check inside its
//      window - this is what a too-slow beacon breaks
//   7. after landing and disarming, disconnecting the last link holds forwarding
//   8. reconnecting resumes forwarding
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
    PARAM_REQUEST_READ: 20,
    PARAM_REQUEST_LIST: 21,
    PARAM_VALUE: 22,
    ATTITUDE: 30,
    LOCAL_POSITION_NED: 32,
    COMMAND_LONG: 76,
    COMMAND_ACK: 77,
    SET_POSITION_TARGET_LOCAL_NED: 84,
    EKF_STATUS_REPORT: 193,
    EXTENDED_SYS_STATE: 245
};
const CRC_EXTRA = {
    0: 50, 1: 124, 22: 220, 30: 39, 32: 185, 77: 143, 193: 71, 245: 130
};

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

function localPositionPayload(north, vehicle) {
    const payload = Buffer.alloc(28);
    payload.writeUInt32LE(bootTimeMs(), 0);
    payload.writeFloatLE(north, 4);
    payload.writeFloatLE(0, 8);
    payload.writeFloatLE(vehicle.down, 12);
    payload.writeFloatLE(vehicle.vx, 16);
    payload.writeFloatLE(0, 20);
    payload.writeFloatLE(0, 24);
    return payload;
}

function extendedSysStatePayload(vehicle) {
    return Buffer.from([0, vehicle.landedState & 0xff]);
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

// ----------------------------------------------------------------- the run
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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

    const startedAtMs = Date.now();
    const northNow = () => options.moveMps * (Date.now() - startedAtMs) / 1000;
    const vehicle = {
        armed: true,
        guided: true,
        customMode: 4,                 // ARDUCOPTER_MODE_GUIDED
        down: -1,
        landedState: LANDED_STATE_IN_AIR,
        yaw: 0,
        vx: options.moveMps
    };

    let held = false;
    const controlCommands = [];
    let sequence = 0;
    let pvaFrame = null;
    let latestFusion = null;
    let beaconsSent = 0;

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
            send(MESSAGE.ATTITUDE, attitudePayload(vehicle));
            send(MESSAGE.LOCAL_POSITION_NED, localPositionPayload(northNow(), vehicle));
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

    // VirtualAutopilot: answer what the backend asks for.
    mavlinkSocket.on('message', (message) => {
        for (const frame of scanFrames(message)) {
            const pva = frame.messageId === MESSAGE.SET_POSITION_TARGET_LOCAL_NED
                ? parsePositionTarget(frame)
                : null;
            if (pva) {
                pvaFrame = pva;
                continue;
            }
            if (frame.messageId === MESSAGE.PARAM_REQUEST_LIST) {
                PARAMETERS.forEach(([name, value], index) =>
                    send(MESSAGE.PARAM_VALUE, paramValuePayload(name, value, index)));
            }
            if (frame.messageId === MESSAGE.PARAM_REQUEST_READ) {
                const name = Buffer.from(frame.payload.subarray(4, 20))
                    .toString('ascii').replace(/\0.*$/, '');
                const index = PARAMETERS.findIndex(([id]) => id === name);
                if (index >= 0) {
                    send(MESSAGE.PARAM_VALUE,
                        paramValuePayload(name, PARAMETERS[index][1], index));
                }
            }
            if (frame.messageId === MESSAGE.COMMAND_LONG) {
                send(MESSAGE.COMMAND_ACK, commandAckPayload(frame.payload.readUInt16LE(28)));
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
                // Each command first gets a gateway ack that carries no status.
                const resolvePending = pending.get(payload.command_id);
                if (resolvePending && payload.status !== undefined) {
                    pending.delete(payload.command_id);
                    resolvePending(payload);
                }
            });
        });

        const command = (commandType, params, target, timeoutMs = 20000) => {
            const commandId = `verify-${commandType}-${++commandCounter}`;
            return new Promise((resolve, reject) => {
                const timer = setTimeout(() => {
                    pending.delete(commandId);
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

        // 1. the control endpoint, as the frontend selects the source
        const sourceResult = await command('connect_mocap_source', {
            mode: 'virtual',
            profile_id: 'minecraft_virtual_mocap',
            source_host: '127.0.0.1',
            source_port: 15150,
            health_host: '127.0.0.1',
            health_port: options.healthPort,
            control_host: '127.0.0.1',
            control_port: options.controlPort
        }, { scope: 'system', ids: [] }, 10000);
        record(
            'control endpoint answers the discovery probe',
            sourceResult.status === 'source_connected',
            `${sourceResult.status}: ${sourceResult.message}`
        );

        // 2. the beacon, before anything can be commanded
        await sleep(1500);
        record(
            'health beacon accepted',
            latestFusion?.beacon_received === true,
            latestFusion?.beacon_received === true
                ? `expected_drone_id ${DRONE_ID}, ${beaconsSent} beacons sent`
                : 'the backend reports no beacon: check expected_drone_id and the port'
        );

        // 3+4. parameter inventory, fusion confirmation, then the PVA frame
        const admissionStartedAtMs = Date.now();
        let attemptsUsed = 0;
        let lastStatus = 'none';
        for (let attempt = 0; attempt < options.attempts && !pvaFrame; attempt++) {
            attemptsUsed = attempt + 1;
            try {
                const result = await command('set_pva_target', {
                    reference_frame: 'local_ned',
                    position: { x: 0, y: 0, z: -1 },
                    velocity: { x: 0, y: 0, z: 0 },
                    acceleration: { x: 0, y: 0, z: 0 }
                }, { scope: 'drone', ids: [DRONE_ID] }, 8000);
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

        const sources = latestFusion?.fusion_configuration;
        record(
            'parameter answers reached the backend fusion configuration',
            sources?.position_xy_source === 6 && sources?.yaw_source === 6,
            sources
                ? `EK3_SRC1_POSXY=${sources.position_xy_source}, `
                    + `POSZ=${sources.position_z_source}, YAW=${sources.yaw_source}`
                : 'the backend published no fusion configuration'
        );
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
                    && Math.abs(pvaFrame.z + 1) < 1e-3,
                `type_mask ${pvaFrame.typeMask}, frame ${pvaFrame.coordinateFrame}, `
                    + `target ${pvaFrame.targetSystem}/${pvaFrame.targetComponent}`
            );
        }

        // 5. the cross-check the beacon period has to stay inside
        const horizontalError = Math.abs(latestFusion?.estimator_error_m?.horizontal ?? NaN);
        record(
            `estimator/mocap cross-check stays inside its window at ${options.moveMps} m/s`,
            options.moveMps === 0
                || (latestFusion?.horizontal_stable === true && horizontalError < 0.10),
            options.moveMps === 0
                ? 'stationary, so not exercised (pass --move-mps=1.4 to exercise it)'
                : `error ${horizontalError.toFixed(4)} m of 0.10 m, beacon ${options.beaconMs} ms`
        );

        // 6+7. the hold the backend asks for around a disconnect
        vehicle.armed = false;
        vehicle.customMode = 9;                 // the mod's LAND mode
        vehicle.down = 0;
        vehicle.vx = 0;
        vehicle.landedState = LANDED_STATE_ON_GROUND;
        await sleep(2500);                      // let the cached binding state settle

        const disconnect = await command('disconnect_mavlink_drone', {
            endpoint_url: `udpin://127.0.0.1:${options.mavlinkPort}`
        }, { scope: 'system', ids: [] }, 15000);
        const holdRequested = controlCommands.some(
            (entry) => entry.action === 'hold' && entry.forwarding_held === true);
        record(
            'disconnecting the last link holds forwarding',
            disconnect.data?.forwarding_hold?.status === 'held' && holdRequested,
            `backend reported ${disconnect.data?.forwarding_hold?.status}; control endpoint saw `
                + `${controlCommands.map((entry) => entry.action).join(' -> ')}`
        );

        await command('connect_mavlink_drone', {
            endpoint_url: `udpin://127.0.0.1:${options.mavlinkPort}`
        }, { scope: 'system', ids: [] }, 15000).catch(() => null);
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
        control_commands: controlCommands,
        fusion: latestFusion,
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
