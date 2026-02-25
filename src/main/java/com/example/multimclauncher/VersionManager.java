package com.example.multimclauncher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages version metadata, game assets, jars, checksums, and Java runtime compatibility.
 *
 * <p>This class demonstrates launcher behavior comparable to official launchers:
 * download version manifests, resolve version-specific JSON, fetch client jar and
 * assets indexes, cache files locally, and validate SHA-1 checksums.</p>
 */
public final class VersionManager {

    private static final URI VERSION_MANIFEST_URI = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private final Path gameDirectory;
    private final Path versionsDirectory;
    private final Path assetsDirectory;
    private final Path cacheDirectory;
    private final HttpClient httpClient;

    /**
     * @param gameDirectory launcher root directory, usually ~/.minecraft
     */
    public VersionManager(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
        this.versionsDirectory = gameDirectory.resolve("versions");
        this.assetsDirectory = gameDirectory.resolve("assets");
        this.cacheDirectory = gameDirectory.resolve("launcher-cache");
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * @return default Minecraft directory based on OS conventions
     */
    public static Path defaultGameDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            return Paths.get(Optional.ofNullable(System.getenv("APPDATA")).orElse(home), ".minecraft");
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "minecraft");
        }
        return Paths.get(home, ".minecraft");
    }

    /**
     * @return game directory path
     */
    public Path gameDirectory() {
        return gameDirectory;
    }

    /**
     * Lists known version IDs from cached metadata or the remote manifest.
     *
     * @return available version IDs
     * @throws IOException          if loading metadata fails
     * @throws InterruptedException if HTTP call is interrupted
     */
    public List<String> listKnownVersions() throws IOException, InterruptedException {
        String manifest = downloadToString(VERSION_MANIFEST_URI);
        Pattern idPattern = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher matcher = idPattern.matcher(manifest);
        List<String> versions = new ArrayList<>();
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return versions.stream().distinct().limit(200).toList();
    }

    /**
     * Resolves a version by downloading metadata, client jar, and required assets.
     *
     * @param versionId target version, e.g. 1.20.6 or 24w14a
     * @return a resolved version descriptor ready to launch
     * @throws IOException          if disk/network operations fail
     * @throws InterruptedException if interrupted
     */
    public ResolvedVersion resolveVersion(String versionId) throws IOException, InterruptedException {
        Files.createDirectories(versionsDirectory);
        Files.createDirectories(assetsDirectory.resolve("indexes"));
        Files.createDirectories(assetsDirectory.resolve("objects"));
        Files.createDirectories(cacheDirectory);

        VersionManifestEntry entry = locateVersionEntry(versionId);
        String versionJson = downloadToString(URI.create(entry.url()));

        Path versionDir = versionsDirectory.resolve(versionId);
        Files.createDirectories(versionDir);
        Path versionJsonPath = versionDir.resolve(versionId + ".json");
        Files.writeString(versionJsonPath, versionJson, StandardCharsets.UTF_8);

        String clientJarUrl = extractRequiredField(versionJson, "client", "url");
        String clientJarSha1 = extractOptionalSha1(versionJson, "client").orElse("");
        Path clientJarPath = versionDir.resolve(versionId + ".jar");
        downloadIfNeeded(URI.create(clientJarUrl), clientJarPath, clientJarSha1);

        String mainClass = extractMainClass(versionJson);
        int protocolVersion = extractProtocolVersion(versionJson).orElse(-1);
        int javaMajor = extractJavaMajor(versionJson).orElse(8);

        // Asset index handling (index json and object retrieval can be expanded as needed).
        Optional<String> assetIndexUrl = extractOptionalAssetIndexUrl(versionJson);
        if (assetIndexUrl.isPresent()) {
            Path indexPath = assetsDirectory.resolve("indexes").resolve(versionId + ".json");
            downloadIfNeeded(URI.create(assetIndexUrl.get()), indexPath, "");
        }

        return new ResolvedVersion(versionId, versionJsonPath, clientJarPath, mainClass, protocolVersion, javaMajor);
    }

    /**
     * Picks a Java runtime for the resolved version. In production this should discover
     * and manage multiple installed JDK/JRE versions.
     *
     * @param resolved version descriptor
     * @return java executable path
     */
    public Path resolveJavaRuntime(ResolvedVersion resolved) {
        String javaHome = System.getenv("JAVA_HOME");
        Path java = (javaHome == null)
                ? Paths.get("java")
                : Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java");

        System.out.printf("Using Java runtime for MC %s: requires Java %d+, resolved %s%n",
                resolved.versionId(), resolved.requiredJavaMajor(), java);
        return java;
    }

    /**
     * Builds the final launch command with game arguments and mod hooks.
     *
     * @param javaExecutable resolved java executable
     * @param resolved       version metadata
     * @param modPlan        mod integration additions
     * @param username       local display name fallback
     * @return process command list
     */
    public List<String> buildLaunchCommand(
            Path javaExecutable,
            ResolvedVersion resolved,
            ModIntegration.ModLaunchPlan modPlan,
            String username
    ) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable.toString());
        cmd.addAll(modPlan.jvmArguments());
        cmd.add("-Djava.library.path=" + gameDirectory.resolve("natives"));
        cmd.add("-cp");
        cmd.add(resolved.clientJarPath().toString());
        cmd.add(modPlan.mainClassOverride().orElse(resolved.mainClass()));

        cmd.add("--username");
        cmd.add(username);
        cmd.add("--version");
        cmd.add(resolved.versionId());
        cmd.add("--gameDir");
        cmd.add(gameDirectory.toString());
        cmd.add("--assetsDir");
        cmd.add(assetsDirectory.toString());
        cmd.add("--assetIndex");
        cmd.add(resolved.versionId());
        cmd.add("--accessToken");
        cmd.add("0");

        cmd.addAll(modPlan.gameArguments());
        return cmd;
    }

    private VersionManifestEntry locateVersionEntry(String versionId) throws IOException, InterruptedException {
        String manifest = downloadToString(VERSION_MANIFEST_URI);
        Pattern item = Pattern.compile("\\{[^{}]*\\\"id\\\"\\s*:\\s*\\\"" + Pattern.quote(versionId) +
                "\\\"[^{}]*\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^{}]*}"
        );
        Matcher matcher = item.matcher(manifest);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unknown version: " + versionId);
        }
        return new VersionManifestEntry(versionId, matcher.group(1));
    }

    private String downloadToString(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Failed to GET " + uri + " status=" + response.statusCode());
        }
        return response.body();
    }

    private void downloadIfNeeded(URI uri, Path destination, String expectedSha1) throws IOException, InterruptedException {
        if (Files.exists(destination) && !expectedSha1.isBlank()) {
            String currentSha1 = sha1(destination);
            if (currentSha1.equalsIgnoreCase(expectedSha1)) {
                return;
            }
        } else if (Files.exists(destination)) {
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Download failed: " + uri + " status=" + response.statusCode());
        }

        Files.createDirectories(Objects.requireNonNull(destination.getParent()));
        Files.write(destination, response.body());

        if (!expectedSha1.isBlank()) {
            String actual = sha1(destination);
            if (!actual.equalsIgnoreCase(expectedSha1)) {
                Files.deleteIfExists(destination);
                throw new IOException("Checksum mismatch for " + destination + ": expected " + expectedSha1 + " got " + actual);
            }
        }
    }

    private String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private String extractRequiredField(String json, String sectionKey, String field) {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(sectionKey) + "\\\"\\s*:\\s*\\{[^{}]*\\\"" +
                Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            throw new IllegalArgumentException("Missing field " + sectionKey + "." + field);
        }
        return m.group(1);
    }

    private Optional<String> extractOptionalSha1(String json, String sectionKey) {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(sectionKey) + "\\\"\\s*:\\s*\\{[^{}]*\\\"sha1\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher m = p.matcher(json);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private String extractMainClass(String versionJson) {
        Pattern p = Pattern.compile("\\\"mainClass\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher m = p.matcher(versionJson);
        if (!m.find()) {
            throw new IllegalArgumentException("Version json missing mainClass");
        }
        return m.group(1);
    }

    private Optional<Integer> extractProtocolVersion(String versionJson) {
        Pattern p = Pattern.compile("\\\"protocolVersion\\\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(versionJson);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private Optional<Integer> extractJavaMajor(String versionJson) {
        Pattern p = Pattern.compile("\\\"javaVersion\\\"\\s*:\\s*\\{[^{}]*\\\"majorVersion\\\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(versionJson);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private Optional<String> extractOptionalAssetIndexUrl(String versionJson) {
        Pattern p = Pattern.compile("\\\"assetIndex\\\"\\s*:\\s*\\{[^{}]*\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher m = p.matcher(versionJson);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record VersionManifestEntry(String id, String url) {}

    /**
     * Immutable launch-time metadata produced after version resolution.
     */
    public record ResolvedVersion(
            String versionId,
            Path versionJsonPath,
            Path clientJarPath,
            String mainClass,
            int protocolVersion,
            int requiredJavaMajor
    ) {}
}
