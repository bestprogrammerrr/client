package com.example.multimclauncher;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

/**
 * User interaction layer.
 *
 * <p>This sample uses a CLI for portability and scriptability. You can replace it
 * with JavaFX/Swing without changing the core launcher contracts.</p>
 */
public final class LauncherUI {

    /**
     * Resolves user launch options from arguments or interactive prompts.
     *
     * @param args              command-line arguments
     * @param availableVersions versions discovered from the manifest
     * @return fully resolved launch request
     * @throws IOException if user input fails
     */
    public LaunchRequest resolveLaunchRequest(String[] args, List<String> availableVersions) throws IOException {
        if (args != null && args.length > 0) {
            return parseArgs(args);
        }

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("=== Multi-Version Minecraft Launcher ===");
            System.out.println("Type a version id (examples):");
            availableVersions.stream().limit(20).forEach(v -> System.out.println("  - " + v));

            System.out.print("Version: ");
            String version = scanner.nextLine().trim();

            System.out.print("Mod loader (none/fabric/forge): ");
            String loaderInput = scanner.nextLine().trim();
            ModIntegration.LoaderType loader = ModIntegration.LoaderType.fromCli(loaderInput);

            System.out.print("Server (optional host:port, blank to skip): ");
            String server = scanner.nextLine().trim();

            System.out.print("Username (optional, default Player): ");
            String username = scanner.nextLine().trim();

            return new LaunchRequest(
                    version,
                    loader,
                    server.isBlank() ? Optional.empty() : Optional.of(server),
                    username.isBlank() ? Optional.empty() : Optional.of(username)
            );
        }
    }

    private LaunchRequest parseArgs(String[] args) {
        String version = null;
        String server = null;
        String username = null;
        ModIntegration.LoaderType loader = ModIntegration.LoaderType.NONE;

        List<String> tokens = Arrays.asList(args);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            switch (token) {
                case "--version" -> version = readValue(tokens, ++i, "--version");
                case "--server" -> server = readValue(tokens, ++i, "--server");
                case "--username" -> username = readValue(tokens, ++i, "--username");
                case "--loader" -> loader = ModIntegration.LoaderType.fromCli(readValue(tokens, ++i, "--loader"));
                default -> {
                    // ignore unknown args in this sample
                }
            }
        }

        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Missing required --version argument");
        }

        return new LaunchRequest(
                version,
                loader,
                Optional.ofNullable(server),
                Optional.ofNullable(username)
        );
    }

    private String readValue(List<String> tokens, int index, String option) {
        if (index >= tokens.size()) {
            throw new IllegalArgumentException("Missing value after " + option);
        }
        return tokens.get(index);
    }

    /**
     * User-selected settings required for a launch operation.
     */
    public record LaunchRequest(
            String versionId,
            ModIntegration.LoaderType loaderType,
            Optional<String> serverAddress,
            Optional<String> username
    ) {}
}
