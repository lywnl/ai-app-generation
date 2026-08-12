package com.lyw.appgeneration.core.builder;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/** 管理仅在当前服务实例内可信的 Vue 依赖目录。 */
public final class VueDependencyManager {

    private static final int PROTOCOL_VERSION = 1;
    private static final String STATE_FILE = ".ai-build-dependency-state.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final String serviceInstanceId;

    public VueDependencyManager() {
        this(UUID.randomUUID().toString());
    }

    VueDependencyManager(String serviceInstanceId) {
        this.serviceInstanceId = requireText(serviceInstanceId, "serviceInstanceId");
    }

    public DependencyDecision prepare(Path projectRoot, String packageFingerprint)
            throws IOException {
        Path root = requireProjectRoot(projectRoot);
        String fingerprint = requireText(packageFingerprint, "packageFingerprint");
        Path dependencies = root.resolve("node_modules");
        if (!Files.exists(dependencies, LinkOption.NOFOLLOW_LINKS)) {
            return DependencyDecision.INSTALL;
        }
        if (isReusable(root, fingerprint)) {
            return DependencyDecision.REUSE;
        }
        SafeBuildDirectoryCleaner.deleteDirectChild(root, "node_modules");
        return DependencyDecision.INSTALL;
    }

    public void markInstallationSucceeded(Path projectRoot, String packageFingerprint)
            throws IOException {
        Path root = requireProjectRoot(projectRoot);
        String fingerprint = requireText(packageFingerprint, "packageFingerprint");
        Path dependencies = root.resolve("node_modules");
        if (!Files.isDirectory(dependencies, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(dependencies)) {
            throw new IOException("node_modules 必须是项目根目录下的普通目录");
        }
        Path realDependencies = dependencies.toRealPath();
        if (!root.equals(realDependencies.getParent())) {
            throw new IOException("node_modules 规范路径超出项目根目录");
        }
        DependencyState state = new DependencyState(
                PROTOCOL_VERSION, serviceInstanceId, fingerprint);
        Path temporary = Files.createTempFile(dependencies, ".ai-build-state-", ".tmp");
        try {
            Files.writeString(temporary, OBJECT_MAPPER.writeValueAsString(state),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, dependencies.resolve(STATE_FILE),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, dependencies.resolve(STATE_FILE),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean isReusable(Path root, String fingerprint) {
        Path dependencies = root.resolve("node_modules");
        if (Files.isSymbolicLink(dependencies)
                || !Files.isDirectory(dependencies, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (!root.equals(dependencies.toRealPath().getParent())) {
                return false;
            }
            Path marker = dependencies.resolve(STATE_FILE);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(marker)) {
                return false;
            }
            DependencyState state = OBJECT_MAPPER.readValue(marker.toFile(), DependencyState.class);
            return state.protocolVersion() == PROTOCOL_VERSION
                    && serviceInstanceId.equals(state.serviceInstanceId())
                    && fingerprint.equals(state.packageFingerprint());
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private Path requireProjectRoot(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot 不能为空");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("项目根路径不是目录");
        }
        return root;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }

    public enum DependencyDecision {
        INSTALL,
        REUSE;

        public boolean requiresInstall() {
            return this == INSTALL;
        }
    }

    private record DependencyState(
            int protocolVersion,
            String serviceInstanceId,
            String packageFingerprint) {
    }
}
