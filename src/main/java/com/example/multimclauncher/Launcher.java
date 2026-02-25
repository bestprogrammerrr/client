package com.example.multimclauncher;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Entry point and orchestration layer for the multi-version Minecraft launcher.
 *
 * <pre>
 * Architecture Diagram
 * ====================
 *
 * +---------------------------+       +---------------------------+
 * |        LauncherUI         | ----> |         Launcher          |
 * | (CLI/GUI user workflow)   |       | (startup orchestration)   |
 * +---------------------------+       +---------------------------+
 *               |                                   |
 *               v                                   v
 * +---------------------------+       +---------------------------+
 * |      VersionManager       | <---- |      ModIntegration       |
 * | (manifest, assets, jars,  |       | (Fabric/Forge hook model) |
 * |  cache, java compatibility)|       +---------------------------+
 * +---------------------------+                   |
 *               |                                 v
 *               |                     +---------------------------+
 *               +-------------------> |      NetworkManager       |
 *                                     | (protocol adapters, ping, |
 *                                     |  server compatibility)    |
 *                                     +---------------------------+
 *
 * Notes:
 * - This launcher never redistributes proprietary Minecraft binaries.
 * - It fetches metadata and files from user-authorized Mojang/Microsoft endpoints,
 *   validates checksums, and launches local files owned by the user.
 * </pre>
 */
public final class Launcher {

    private final VersionManager versionManager;
    private final ModIntegration modIntegration;
    private final NetworkManager networkManager;

    /**
     * Creates the launcher with default managers.
     *
     * @param gameDirectory game working directory (e.g. ~/.minecraft)
     */
    public Launcher(Path gameDirectory) {
        this.versionManager = new VersionManager(gameDirectory);
        this.modIntegration = new ModIntegration(gameDirectory);
        this.networkManager = new NetworkManager();
    }

    /**
     * Program entry point.
     *
     * @param args optional CLI arguments:
     *             --version <id>, --server <host:port>, --loader <none|fabric|forge>
     * @throws Exception if launch preparation fails
     */
    public static void main(String[] args) throws Exception {
        Launcher launcher = new Launcher(VersionManager.defaultGameDirectory());
        LauncherUI ui = new LauncherUI();
        LauncherUI.LaunchRequest request = ui.resolveLaunchRequest(args, launcher.versionManager.listKnownVersions());
        launcher.launch(request);
    }

    /**
     * Resolves version files, applies optional mod integration, probes server compatibility,
     * and starts the Minecraft client process.
     *
     * @param request user-selected launch request
     * @throws IOException          if network or disk operations fail
     * @throws InterruptedException if the operation is interrupted
     */
    public void launch(LauncherUI.LaunchRequest request) throws IOException, InterruptedException {
        System.out.println("[" + Instant.now() + "] Preparing version " + request.versionId());

        VersionManager.ResolvedVersion resolved = versionManager.resolveVersion(request.versionId());
        Path javaExecutable = versionManager.resolveJavaRuntime(resolved);

        ModIntegration.ModLaunchPlan modPlan = modIntegration.prepare(request.loaderType(), resolved);

        if (request.serverAddress().isPresent()) {
            String server = request.serverAddress().orElseThrow();
            NetworkManager.ServerProbe probe = networkManager.probeServer(server);
            Optional<String> compatibilityWarning = networkManager.checkCompatibility(resolved.protocolVersion(), probe);
            compatibilityWarning.ifPresent(msg -> System.out.println("Compatibility warning: " + msg));
        }

        List<String> command = versionManager.buildLaunchCommand(
                javaExecutable,
                resolved,
                modPlan,
                request.username().orElse("Player")
        );

        System.out.println("Launching with command: " + String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .directory(versionManager.gameDirectory().toFile())
                .inheritIO()
                .start();

        int exitCode = process.waitFor();
        System.out.println("Minecraft exited with code " + exitCode);
    }
}
