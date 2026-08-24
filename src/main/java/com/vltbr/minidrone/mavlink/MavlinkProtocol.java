package com.vltbr.minidrone.mavlink;

public final class MavlinkProtocol {
    public static final int V1_MAGIC = 0xFE;

    public static final int HEARTBEAT = 0;
    public static final int SYS_STATUS = 1;
    public static final int SET_MODE = 11;
    public static final int PARAM_REQUEST_READ = 20;
    public static final int PARAM_REQUEST_LIST = 21;
    public static final int PARAM_VALUE = 22;
    public static final int PARAM_SET = 23;
    public static final int ATTITUDE = 30;
    public static final int LOCAL_POSITION_NED = 32;
    public static final int SET_GPS_GLOBAL_ORIGIN = 48;
    public static final int GPS_GLOBAL_ORIGIN = 49;
    public static final int REQUEST_DATA_STREAM = 66;
    public static final int RC_CHANNELS_OVERRIDE = 70;
    public static final int COMMAND_LONG = 76;
    public static final int COMMAND_ACK = 77;
    public static final int SET_ATTITUDE_TARGET = 82;
    public static final int SET_POSITION_TARGET_LOCAL_NED = 84;
    public static final int EKF_STATUS_REPORT = 193;
    public static final int HOME_POSITION = 242;
    public static final int EXTENDED_SYS_STATE = 245;

    public static final int MAV_CMD_NAV_LAND = 21;
    public static final int MAV_CMD_NAV_TAKEOFF = 22;
    public static final int MAV_CMD_DO_SET_MODE = 176;
    public static final int MAV_CMD_DO_SET_HOME = 179;
    public static final int MAV_CMD_COMPONENT_ARM_DISARM = 400;
    public static final int MAV_CMD_SET_MESSAGE_INTERVAL = 511;
    public static final int MAV_CMD_REQUEST_MESSAGE = 512;

    public static final int MAV_RESULT_ACCEPTED = 0;
    public static final int MAV_RESULT_TEMPORARILY_REJECTED = 1;
    public static final int MAV_RESULT_DENIED = 2;
    public static final int MAV_RESULT_UNSUPPORTED = 3;

    public static final int MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 0x01;
    public static final int MAV_MODE_FLAG_GUIDED_ENABLED = 0x08;
    public static final int MAV_MODE_FLAG_SAFETY_ARMED = 0x80;
    public static final int ARDUCOPTER_MODE_GUIDED = 4;

    public static final int MAV_FRAME_LOCAL_NED = 1;
    public static final int POSITION_TARGET_TYPE_MASK_POSITION_ONLY = 0x0DF8;
    public static final int POSITION_TARGET_TYPE_MASK_POSITION_VELOCITY = 0x0DC0;

    private MavlinkProtocol() {}

    public static int crcExtra(int messageId) {
        return switch (messageId) {
            case HEARTBEAT -> 50;
            case SYS_STATUS -> 124;
            case SET_MODE -> 89;
            case PARAM_REQUEST_READ -> 214;
            case PARAM_REQUEST_LIST -> 159;
            case PARAM_VALUE -> 220;
            case PARAM_SET -> 168;
            case ATTITUDE -> 39;
            case LOCAL_POSITION_NED -> 185;
            case SET_GPS_GLOBAL_ORIGIN -> 41;
            case GPS_GLOBAL_ORIGIN -> 39;
            case REQUEST_DATA_STREAM -> 148;
            case RC_CHANNELS_OVERRIDE -> 124;
            case COMMAND_LONG -> 152;
            case COMMAND_ACK -> 143;
            case SET_ATTITUDE_TARGET -> 49;
            case SET_POSITION_TARGET_LOCAL_NED -> 143;
            case EKF_STATUS_REPORT -> 71;
            case HOME_POSITION -> 104;
            case EXTENDED_SYS_STATE -> 130;
            default -> -1;
        };
    }
}
