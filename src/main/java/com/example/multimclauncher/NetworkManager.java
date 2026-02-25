package com.example.multimclauncher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Networking and multiplayer compatibility helper.
 *
 * <p>This module does not replace the game's internal networking. Instead, it provides:
 * server reachability checks, protocol compatibility hints, and extension hooks for
 * custom adapters (e.g., ViaVersion-like translation layers).</p>
 */
public final class NetworkManager {

    private static final int DEFAULT_PORT = 25565;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Coarse mapping between protocol versions and compatible translator stacks.
     * In a real client, this map can be generated from external protocol metadata.
     */
    private static final Map<Integer, String> PROTOCOL_ADAPTERS = Map.of(
            47, "legacy-1.8-adapter",
            340, "adapter-1.12.2",
            754, "adapter-1.16.5",
            763, "adapter-1.20.1",
            767, "adapter-1.21.x"
    );

    /**
     * Probes whether a multiplayer server endpoint is reachable.
     *
     * @param serverAddress host:port or host
     * @return probe metadata
     */
    public ServerProbe probeServer(String serverAddress) {
        HostPort hostPort = parse(serverAddress);
        long start = System.nanoTime();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPort.host(), hostPort.port()), (int) CONNECT_TIMEOUT.toMillis());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new ServerProbe(serverAddress, true, elapsedMs, Optional.empty());
        } catch (IOException ex) {
            return new ServerProbe(serverAddress, false, -1, Optional.of(ex.getMessage()));
        }
    }

    /**
     * Checks game/client protocol against known adapter capabilities.
     *
     * @param clientProtocol resolved version protocol
     * @param probe          network probe result
     * @return optional warning message
     */
    public Optional<String> checkCompatibility(int clientProtocol, ServerProbe probe) {
        if (!probe.reachable()) {
            return Optional.of("Server is unreachable: " + probe.error().orElse("unknown"));
        }
        if (clientProtocol < 0) {
            return Optional.of("Unknown client protocol. Launch may still work if server is same version.");
        }

        String adapter = PROTOCOL_ADAPTERS.get(clientProtocol);
        if (adapter == null) {
            return Optional.of("No protocol adapter known for protocol " + clientProtocol
                    + ". Consider exact version match.");
        }
        return Optional.empty();
    }

    private HostPort parse(String serverAddress) {
        String[] split = serverAddress.split(":", 2);
        if (split.length == 1) {
            return new HostPort(split[0], DEFAULT_PORT);
        }
        return new HostPort(split[0], Integer.parseInt(split[1]));
    }

    private record HostPort(String host, int port) {}

    /**
     * Result of a pre-launch server connectivity check.
     */
    public record ServerProbe(
            String endpoint,
            boolean reachable,
            long latencyMs,
            Optional<String> error
    ) {}
}
