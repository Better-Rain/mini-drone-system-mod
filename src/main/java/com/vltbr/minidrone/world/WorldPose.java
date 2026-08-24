package com.vltbr.minidrone.world;

public record WorldPose(
    double x,
    double y,
    double z,
    float yawDegrees,
    float pitchDegrees,
    float rollDegrees
) {}

