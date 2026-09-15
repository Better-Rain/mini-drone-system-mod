package com.vltbr.minidrone.sim;

/**
 * What touching something does to the vehicle, short of destroying it.
 *
 * <p>Before this, contact was all-or-nothing: either the vehicle stopped dead against
 * whatever it met, or a hard enough hit ended the flight. Neither is what a pilot sees.
 * A quadcopter that clips a gate frame keeps moving along it (and loses speed doing so),
 * and one that touches down a little fast hops before settling.
 *
 * <p>So the two effects are separate and both come from the airframe:
 *
 * <ul>
 *   <li>{@link #rebound}: the component into the surface comes back reversed, scaled by
 *       restitution. Reproducing that in the vehicle's rate loop is what turns a light
 *       touch into a bounce rather than a stop;</li>
 *   <li>{@link #slide}: the component along the surface decays at a constant
 *       deceleration, `friction * g`, which is how sliding friction behaves - unlike
 *       scaling the speed by a fraction, which would depend on the tick rate.</li>
 * </ul>
 *
 * <p>Both are pure arithmetic because getting them wrong is how a vehicle ends up
 * sticking to walls or being flung off them, and that should be caught by a test rather
 * than by flying into things.
 */
public final class ContactResponse {
    private ContactResponse() {
    }

    /**
     * The speed component perpendicular to a surface after the impact.
     *
     * <p>Callers pass this only for an axis whose motion the world actually blocked, so
     * the sign carries "the direction it was travelling" and the answer is the opposite
     * one. There is no separate "already moving away" case: an axis is blocked because
     * the vehicle ran into something along it.
     *
     * @param incomingSpeed the component into the surface
     * @param restitution   0 = dead stop against it, 1 = perfectly bouncy
     */
    public static double rebound(double incomingSpeed, double restitution) {
        return -incomingSpeed * Math.max(0.0, Math.min(1.0, restitution));
    }

    /**
     * The speed component along a surface after one tick of contact.
     *
     * @param speed    the tangential speed, either sign
     * @param friction the coefficient of sliding friction
     */
    public static double slide(double speed, double friction, double dt) {
        double deceleration = Math.max(0.0, friction) * VehicleModel.GRAVITY_MPS2;
        double remaining = Math.max(0.0, Math.abs(speed) - deceleration * dt);
        // Friction slows a slide to a stop; it never pushes the vehicle back.
        return Math.copySign(remaining, speed);
    }
}
