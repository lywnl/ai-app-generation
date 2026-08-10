package com.lyw.appgeneration.service.rag.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.chunk.KnowledgeChunkFactory;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.VueRagBudgetPolicy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Vue 模板知识目录，在构造阶段一次性完成加载和校验。
 */
public class TemplateCatalog {

    private static final List<String> REQUIRED_SKELETON_FILES = List.of(
            "package.json",
            "index.html",
            "vite.config.js",
            "src/main.js",
            "src/App.vue"
    );

    private final List<TemplateDoc> documents;
    private final Map<String, TemplateDoc> documentsById;
    private final List<KnowledgeChunk> chunks;
    private final String catalogVersion;

    /**
     * 加载指定 Vue 模板根目录。
     *
     * @param root Vue 模板根目录
     * @param objectMapper JSON 解析器
     */
    public TemplateCatalog(Path root, ObjectMapper objectMapper) {
        List<CatalogFile> catalogFiles = readCatalogFiles(root);
        this.catalogVersion = calculateCatalogVersion(catalogFiles);
        this.documentsById = new HashMap<>();
        this.documents = loadDocuments(catalogFiles, objectMapper);
        this.chunks = createChunks(documents);
    }

    public List<TemplateDoc> getDocuments() {
        return documents;
    }

    public Optional<TemplateDoc> findDocumentById(String documentId) {
        return Optional.ofNullable(documentsById.get(documentId));
    }

    public List<KnowledgeChunk> getChunks() {
        return chunks;
    }

    public String getCatalogVersion() {
        return catalogVersion;
    }

    private List<CatalogFile> readCatalogFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Vue 模板根目录不存在或不是目录: " + root);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> readCatalogFile(root, path))
                    .sorted(Comparator.comparing(CatalogFile::relativePath))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取 Vue 模板目录失败: " + root, exception);
        }
    }

    private CatalogFile readCatalogFile(Path root, Path file) {
        String relativePath = toRelativePath(root, file);
        try {
            return new CatalogFile(relativePath, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw invalidFile(relativePath, "无法按 UTF-8 读取", exception);
        }
    }

    private String toRelativePath(Path root, Path file) {
        StringBuilder relativePath = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (!relativePath.isEmpty()) {
                relativePath.append('/');
            }
            relativePath.append(part);
        }
        return relativePath.toString();
    }

    private List<TemplateDoc> loadDocuments(List<CatalogFile> catalogFiles, ObjectMapper objectMapper) {
        List<TemplateDoc> loadedDocuments = new ArrayList<>();
        for (CatalogFile catalogFile : catalogFiles) {
            TemplateDoc document = parseDocument(catalogFile, objectMapper);
            validateDocument(document, catalogFile.relativePath(), objectMapper);
            if (documentsById.putIfAbsent(document.getId(), document) != null) {
                throw invalidFile(catalogFile.relativePath(), "重复文档 ID: " + document.getId());
            }
            loadedDocuments.add(document);
        }
        return List.copyOf(loadedDocuments);
    }

    private TemplateDoc parseDocument(CatalogFile catalogFile, ObjectMapper objectMapper) {
        try {
            JsonNode rootNode = objectMapper.readTree(catalogFile.content());
            if (rootNode == null || !rootNode.isObject()) {
                throw invalidFile(catalogFile.relativePath(), "模板必须是 JSON 对象");
            }
            JsonNode documentKindNode = rootNode.get("documentKind");
            if (documentKindNode != null && documentKindNode.isTextual()
                    && documentKindNode.textValue().isBlank()) {
                throw invalidFile(catalogFile.relativePath(), "documentKind 为空");
            }
            return objectMapper.readValue(catalogFile.content(), TemplateDoc.class);
        } catch (JsonProcessingException exception) {
            String reason = exception.getOriginalMessage();
            if (reason != null && reason.contains("RagDocumentKind")) {
                reason = "documentKind 未知: " + reason;
            } else {
                reason = "模板 JSON 非法: " + reason;
            }
            throw invalidFile(catalogFile.relativePath(), reason, exception);
        }
    }

    private void validateDocument(TemplateDoc document, String sourcePath, ObjectMapper objectMapper) {
        requireText(document.getId(), sourcePath, "文档 ID 为空");
        if (document.getDocumentKind() == null) {
            throw invalidFile(sourcePath, "documentKind 为空");
        }
        requireText(document.getEmbedText(), sourcePath, "embedText 为空");
        if (document.getFiles() == null || document.getFiles().isEmpty()) {
            throw invalidFile(sourcePath, "files 为空");
        }
        validateFileCount(document, sourcePath);
        validateQualityScore(document.getQualityScore(), sourcePath);
        validateFilePaths(document.getFiles(), sourcePath);
        if (document.getDocumentKind() == RagDocumentKind.PROJECT_SKELETON) {
            validateSkeleton(document, sourcePath, objectMapper);
        }
    }

    private void validateFileCount(TemplateDoc document, String sourcePath) {
        int actualCount = document.getFiles().size();
        int maxCount = VueRagBudgetPolicy.maxFiles(document.getDocumentKind());
        if (actualCount > maxCount) {
            throw invalidFile(sourcePath, "%s 文件数量超限: 实际 %d，上限 %d"
                    .formatted(document.getDocumentKind(), actualCount, maxCount));
        }
    }

    private void validateQualityScore(Double qualityScore, String sourcePath) {
        if (qualityScore == null || !Double.isFinite(qualityScore)
                || qualityScore < 0 || qualityScore > 1) {
            throw invalidFile(sourcePath, "qualityScore 必须在 [0,1] 范围内");
        }
    }

    private void validateFilePaths(List<TemplateDoc.TemplateFile> files, String sourcePath) {
        for (TemplateDoc.TemplateFile file : files) {
            if (file == null || file.getPath() == null || file.getPath().isBlank()) {
                throw invalidFile(sourcePath, "文件路径为空");
            }
            String path = file.getPath().replace('\\', '/');
            if (path.startsWith("/") || path.startsWith("//") || path.matches("^[A-Za-z]:/.*")) {
                throw invalidFile(sourcePath, "文件路径不能是绝对路径: " + file.getPath());
            }
            if (path.contains("..")) {
                throw invalidFile(sourcePath, "文件路径不能包含 ..: " + file.getPath());
            }
        }
    }

    private void validateSkeleton(TemplateDoc document, String sourcePath, ObjectMapper objectMapper) {
        Map<String, TemplateDoc.TemplateFile> filesByPath = new HashMap<>();
        for (TemplateDoc.TemplateFile file : document.getFiles()) {
            filesByPath.put(file.getPath(), file);
        }
        for (String requiredFile : REQUIRED_SKELETON_FILES) {
            if (!filesByPath.containsKey(requiredFile)) {
                throw invalidFile(sourcePath, "PROJECT_SKELETON 缺少关键文件: " + requiredFile);
            }
        }
        validatePackageJson(document, filesByPath.get("package.json"), sourcePath, objectMapper);
    }

    private void validatePackageJson(TemplateDoc document,
                                     TemplateDoc.TemplateFile packageFile,
                                     String sourcePath,
                                     ObjectMapper objectMapper) {
        JsonNode packageJson;
        if (packageFile.getContent() == null || packageFile.getContent().isBlank()) {
            throw invalidFile(sourcePath, "package.json 内容为空");
        }
        try {
            packageJson = objectMapper.readTree(packageFile.getContent());
        } catch (JsonProcessingException exception) {
            throw invalidFile(sourcePath, "package.json 必须是合法 JSON", exception);
        }
        if (packageJson == null || !packageJson.isObject()) {
            throw invalidFile(sourcePath, "package.json 必须是合法 JSON 对象");
        }
        Map<String, String> packageDependencies = stringMap(packageJson.get("dependencies"), objectMapper);
        Map<String, String> packageDevDependencies = stringMap(packageJson.get("devDependencies"), objectMapper);
        if (!emptyIfNull(document.getDependencies()).equals(packageDependencies)) {
            throw invalidFile(sourcePath, "package.json dependencies 声明不一致");
        }
        if (!emptyIfNull(document.getDevDependencies()).equals(packageDevDependencies)) {
            throw invalidFile(sourcePath, "package.json devDependencies 声明不一致");
        }
    }

    private Map<String, String> stringMap(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            return Map.of("<invalid>", node.toString());
        }
        return objectMapper.convertValue(node, objectMapper.getTypeFactory()
                .constructMapType(Map.class, String.class, String.class));
    }

    private Map<String, String> emptyIfNull(Map<String, String> values) {
        return values == null ? Map.of() : values;
    }

    private List<KnowledgeChunk> createChunks(List<TemplateDoc> loadedDocuments) {
        KnowledgeChunkFactory chunkFactory = new KnowledgeChunkFactory();
        List<KnowledgeChunk> loadedChunks = loadedDocuments.stream()
                .flatMap(document -> chunkFactory.createChunks(document).stream())
                .toList();
        Set<String> chunkIds = new HashSet<>();
        for (KnowledgeChunk chunk : loadedChunks) {
            if (chunk.chunkId() == null || chunk.chunkId().isBlank()) {
                throw new IllegalArgumentException("知识目录包含空 chunk ID");
            }
            if (!chunkIds.add(chunk.chunkId())) {
                throw new IllegalArgumentException("知识目录包含重复 chunk ID: " + chunk.chunkId());
            }
        }
        return List.copyOf(loadedChunks);
    }

    private String calculateCatalogVersion(List<CatalogFile> catalogFiles) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CatalogFile catalogFile : catalogFiles) {
                updateDigest(digest, catalogFile.relativePath());
                updateDigest(digest, catalogFile.content());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private void requireText(String value, String sourcePath, String reason) {
        if (value == null || value.isBlank()) {
            throw invalidFile(sourcePath, reason);
        }
    }

    private IllegalArgumentException invalidFile(String sourcePath, String reason) {
        return new IllegalArgumentException("Vue 模板文件 [" + sourcePath + "] 非法: " + reason);
    }

    private IllegalArgumentException invalidFile(String sourcePath, String reason, Exception cause) {
        return new IllegalArgumentException("Vue 模板文件 [" + sourcePath + "] 非法: " + reason, cause);
    }

    private record CatalogFile(String relativePath, String content) {
    }
}
