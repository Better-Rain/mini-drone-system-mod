package com.vltbr.minidrone.mavlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/** Loopback-only UDP endpoint used by the backend to discover the virtual mocap source. */
final class MocapControlServer {
    private static final InetAddress IPV4_LOOPBACK = ipv4Loopback();
    private static final int RECEIVE_BUFFER_BYTES = 512;
    private static final int RECEIVE_TIMEOUT_MS = 250;

    private final int configuredPort;
    private final ForwardingHold forwardingHold;
    private volatile DatagramSocket socket;
    private volatile boolean bound;
    private volatile int boundPort;

    MocapControlServer(int configuredPort, ForwardingHold forwardingHold) {
        this.configuredPort = configuredPort;
        this.forwardingHold = forwardingHold;
    }

    void run(AtomicBoolean running, IntConsumer onBound) throws IOException {
        try (DatagramSocket candidate = new DatagramSocket(null)) {
            candidate.bind(new InetSocketAddress(IPV4_LOOPBACK, configuredPort));
            candidate.setSoTimeout(RECEIVE_TIMEOUT_MS);
            socket = candidate;
            boundPort = candidate.getLocalPort();
            bound = true;
            onBound.accept(boundPort);

            byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
            while (running.get()) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                try {
                    candidate.receive(request);
                    MocapControlProtocol.commandFor(
                        request.getData(),
                        request.getOffset(),
                        request.getLength()
                    ).ifPresent(command -> sendResponse(
                        candidate,
                        request,
                        MocapControlProtocol.response(
                            command,
                            forwardingHold.apply(command.holdChange())
                        )
                    ));
                } catch (SocketTimeoutException ignored) {
                    // The timeout allows the owning transport to stop this endpoint promptly.
                } catch (SocketException exception) {
                    if (running.get()) {
                        throw exception;
                    }
                }
            }
        } finally {
            bound = false;
            boundPort = 0;
            socket = null;
        }
    }

    boolean isBound() {
        return bound;
    }

    int boundPort() {
        return boundPort;
    }

    void close() {
        DatagramSocket activeSocket = socket;
        if (activeSocket != null) {
            activeSocket.close();
        }
    }

    private static void sendResponse(DatagramSocket socket, DatagramPacket request, byte[] response) {
        try {
            socket.send(new DatagramPacket(
                response,
                response.length,
                request.getAddress(),
                request.getPort()
            ));
        } catch (IOException ignored) {
            // A probe client may close its ephemeral socket before it receives the reply.
        }
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
