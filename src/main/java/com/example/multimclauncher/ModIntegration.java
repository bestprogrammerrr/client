package com.example.multimclauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mod loader integration adapter.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>Expose hooks for Fabric, Forge, or no-loader launches.</li>
 *   <li>Keep launcher core independent from loader-specific internals.</li>
 *   <li>Allow adding future loaders by implementing a new strategy branch.</li>
 * </ul>
 */
public final class ModIntegration {

    private final Path gameDirectory;

    /**
     * @param gameDirectory root game folder
     */
    public ModIntegration(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    /**
     * Produces mod-aware launch adjustments (main class override, JVM args, game args).
     *
     * @param loaderType selected loader
     * @param version    resolved vanilla version metadata
     * @return a launch plan to merge into the final process command
     */
    public ModLaunchPlan prepare(LoaderType loaderType, VersionManager.ResolvedVersion version) {
        return switch (loaderType) {
            case NONE -> ModLaunchPlan.empty();
            case FABRIC -> prepareFabric(version);
            case FORGE -> prepareForge(version);
        };
    }

    private ModLaunchPlan prepareFabric(VersionManager.ResolvedVersion version) {
        Path fabricProfile = gameDirectory.resolve("versions").resolve("fabric-loader-" + version.versionId());
        if (!Files.exists(fabricProfile)) {
            throw new IllegalStateException("Fabric profile not installed for " + version.versionId()
                    + ". Install via Fabric installer API before launch.");
        }

        List<String> jvmArgs = List.of("-Dfabric.skipMcProvider=true");
        List<String> gameArgs = List.of("--fabric.debug.disableClassPathIsolation=false");
        return new ModLaunchPlan(jvmArgs, gameArgs, Optional.of("net.fabricmc.loader.impl.launch.knot.KnotClient"));
    }

    private ModLaunchPlan prepareForge(VersionManager.ResolvedVersion version) {
        Path forgeLib = gameDirectory.resolve("libraries").resolve("net").resolve("minecraftforge");
        if (!Files.exists(forgeLib)) {
            throw new IllegalStateException("Forge libraries not found. Run Forge installer for " + version.versionId());
        }

        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Dfml.ignoreInvalidMinecraftCertificates=true");
        jvmArgs.add("-Dfml.ignorePatchDiscrepancies=true");

        // For modern forge, main class is typically cpw.mods.bootstraplauncher.BootstrapLauncher.
        return new ModLaunchPlan(jvmArgs, List.of(), Optional.of("cpw.mods.bootstraplauncher.BootstrapLauncher"));
    }

    /**
     * Loader choices supported by this sample.
     */
    public enum LoaderType {
        NONE,
        FABRIC,
        FORGE;

        /**
         * Parses CLI values into loader enum.
         *
         * @param value user input
         * @return corresponding loader type
         */
        public static LoaderType fromCli(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            return switch (value.trim().toLowerCase()) {
                case "none" -> NONE;
                case "fabric" -> FABRIC;
                case "forge" -> FORGE;
                default -> throw new IllegalArgumentException("Unsupported loader: " + value);
            };
        }
    }

    /**
     * Immutable mod integration output consumed by {@link VersionManager#buildLaunchCommand}.
     */
    public record ModLaunchPlan(
            List<String> jvmArguments,
            List<String> gameArguments,
            Optional<String> mainClassOverride
    ) {
        /**
         * @return a no-op launch plan
         */
        public static ModLaunchPlan empty() {
            return new ModLaunchPlan(List.of(), List.of(), Optional.empty());
        }
    }
}
