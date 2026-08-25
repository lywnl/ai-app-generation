package com.lyw.appgeneration.service.rag.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;

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
 * HTML 与原生三文件应用的受校验模板目录。
 */
public class NativeTemplateCatalog {

    private static final Map<CodeGenTypeEnum, CatalogContract> CONTRACTS = Map.of(
            CodeGenTypeEnum.HTML,
            new CatalogContract("html", "html-", RagDocumentKind.PAGE_SECTION,
                    "html", 9, List.of("index.html")),
            CodeGenTypeEnum.MULTI_FILE,
            new CatalogContract("multi-file", "multi-", RagDocumentKind.SINGLE_PAGE_APP,
                    "html-css-javascript", 8,
                    List.of("index.html", "style.css", "script.js"))
    );

    private static final NativeTemplateQualityGate QUALITY_GATE =
            new NativeTemplateQualityGate();

    private final CodeGenTypeEnum codeGenType;
    private final List<TemplateDoc> documents;
    private final Map<String, TemplateDoc> documentsById;
    private final String catalogVersion;

    public NativeTemplateCatalog(Path root, CodeGenTypeEnum codeGenType, ObjectMapper objectMapper) {
        CatalogContract contract = contract(codeGenType);
        List<CatalogFile> catalogFiles = readCatalogFiles(root);
        if (catalogFiles.size() != contract.documentCount()) {
            throw new IllegalArgumentException("%s 模板数量必须为 %d，实际为 %d"
                    .formatted(contract.type(), contract.documentCount(), catalogFiles.size()));
        }
        this.codeGenType = codeGenType;
        this.catalogVersion = calculateCatalogVersion(catalogFiles);
        this.documentsById = new HashMap<>();
        this.documents = loadDocuments(catalogFiles, objectMapper, contract);
    }

    public CodeGenTypeEnum getCodeGenType() {
        return codeGenType;
    }

    public List<TemplateDoc> getDocuments() {
        return documents;
    }

    public Optional<TemplateDoc> findDocumentById(String documentId) {
        return Optional.ofNullable(documentsById.get(documentId));
    }

    public String getCatalogVersion() {
        return catalogVersion;
    }

    private CatalogContract contract(CodeGenTypeEnum type) {
        CatalogContract contract = CONTRACTS.get(type);
        if (contract == null) {
            throw new IllegalArgumentException("原生模板目录不支持生成类型: " + type);
        }
        return contract;
    }

    private List<CatalogFile> readCatalogFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("原生模板根目录不存在或不是目录: " + root);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> readCatalogFile(root, path))
                    .sorted(Comparator.comparing(CatalogFile::relativePath))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取原生模板目录失败: " + root, exception);
        }
    }

    private CatalogFile readCatalogFile(Path root, Path file) {
        String relativePath = root.relativize(file).toString().replace('\\', '/');
        try {
            return new CatalogFile(relativePath, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw invalidFile(relativePath, "无法按 UTF-8 读取", exception);
        }
    }

    private List<TemplateDoc> loadDocuments(
            List<CatalogFile> catalogFiles,
            ObjectMapper objectMapper,
            CatalogContract contract) {
        List<TemplateDoc> loaded = new ArrayList<>(catalogFiles.size());
        for (CatalogFile catalogFile : catalogFiles) {
            TemplateDoc document = parseDocument(catalogFile, objectMapper);
            validateDocument(document, catalogFile.relativePath(), contract);
            if (documentsById.putIfAbsent(document.getId(), document) != null) {
                throw invalidFile(catalogFile.relativePath(), "重复文档 ID: " + document.getId());
            }
            loaded.add(document);
        }
        return List.copyOf(loaded);
    }

    private TemplateDoc parseDocument(CatalogFile catalogFile, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(catalogFile.content());
            if (root == null || !root.isObject()) {
                throw invalidFile(catalogFile.relativePath(), "模板必须是 JSON 对象");
            }
            JsonNode schemaVersion = root.get("schemaVersion");
            if (schemaVersion == null || !schemaVersion.isInt() || schemaVersion.intValue() != 1) {
                throw invalidFile(catalogFile.relativePath(), "schemaVersion 只支持整数 1");
            }
            return objectMapper.readValue(catalogFile.content(), TemplateDoc.class);
        } catch (JsonProcessingException exception) {
            throw invalidFile(catalogFile.relativePath(), "模板 JSON 非法", exception);
        }
    }

    private void validateDocument(
            TemplateDoc document,
            String sourcePath,
            CatalogContract contract) {
        requireText(document.getId(), sourcePath, "文档 ID 为空");
        if (!document.getId().startsWith(contract.idPrefix())) {
            throw invalidFile(sourcePath, "id 必须使用前缀 " + contract.idPrefix());
        }
        if (!contract.type().equals(document.getType())) {
            throw invalidFile(sourcePath, "type 必须为 " + contract.type());
        }
        if (document.getDocumentKind() != contract.documentKind()) {
            throw invalidFile(sourcePath, "documentKind 必须为 " + contract.documentKind());
        }
        requireText(document.getVersion(), sourcePath, "version 为空");
        requireText(document.getFramework(), sourcePath, "framework 为空");
        if (!"none".equals(document.getFramework())) {
            throw invalidFile(sourcePath, "framework 必须为 none");
        }
        requireText(document.getLanguage(), sourcePath, "language 为空");
        if (!contract.language().equals(document.getLanguage())) {
            throw invalidFile(sourcePath, "language 必须为 " + contract.language());
        }
        requireText(document.getBuildTool(), sourcePath, "buildTool 为空");
        if (!"none".equals(document.getBuildTool())) {
            throw invalidFile(sourcePath, "buildTool 必须为 none");
        }
        validateDependencies(document.getDependencies(), sourcePath, "dependencies");
        validateDependencies(document.getDevDependencies(), sourcePath, "devDependencies");
        if (!document.getDependencies().isEmpty() || !document.getDevDependencies().isEmpty()) {
            throw invalidFile(sourcePath, "原生模板不得声明工程依赖");
        }
        document.setDependencies(Map.copyOf(document.getDependencies()));
        document.setDevDependencies(Map.copyOf(document.getDevDependencies()));
        requireText(document.getCategory(), sourcePath, "category 为空");
        requireText(document.getTitle(), sourcePath, "title 为空");
        requireText(document.getEmbedText(), sourcePath, "embedText 为空");
        requireText(document.getDescription(), sourcePath, "description 为空");
        validateTags(document.getStyle(), sourcePath, "style");
        validateTags(document.getTech(), sourcePath, "tech");
        validateQualityScore(document.getQualityScore(), sourcePath);
        validateFiles(document.getFiles(), sourcePath, contract);
        QUALITY_GATE.validate(document, codeGenType, sourcePath);
    }

    private void validateTags(List<String> values, String sourcePath, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw invalidFile(sourcePath, fieldName + " 为空");
        }
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw invalidFile(sourcePath, fieldName + " 包含空白标签");
        }
    }

    private void validateDependencies(
            Map<String, String> dependencies,
            String sourcePath,
            String fieldName) {
        if (dependencies == null) {
            throw invalidFile(sourcePath, fieldName + " 为空");
        }
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw invalidFile(sourcePath, fieldName + " 包含空白依赖名或版本");
            }
        }
    }

    private void validateQualityScore(Double qualityScore, String sourcePath) {
        if (qualityScore == null || !Double.isFinite(qualityScore)
                || qualityScore < 0 || qualityScore > 1) {
            throw invalidFile(sourcePath, "qualityScore 必须在 [0,1] 范围内");
        }
    }

    private void validateFiles(
            List<TemplateDoc.TemplateFile> files,
            String sourcePath,
            CatalogContract contract) {
        if (files == null) {
            throw invalidFile(sourcePath, "files 为空");
        }
        List<String> actualPaths = new ArrayList<>(files.size());
        Set<String> uniquePaths = new HashSet<>();
        for (TemplateDoc.TemplateFile file : files) {
            if (file == null || file.getPath() == null || file.getPath().isBlank()) {
                throw invalidFile(sourcePath, "文件路径为空");
            }
            String path = file.getPath();
            validateFilePath(path, sourcePath);
            if (!uniquePaths.add(path)) {
                throw invalidFile(sourcePath, "文件路径重复: " + path);
            }
            if (file.getContent() == null || file.getContent().isBlank()) {
                throw invalidFile(sourcePath, "文件内容为空: " + path);
            }
            actualPaths.add(path);
        }
        if (!actualPaths.equals(contract.requiredFiles())) {
            throw invalidFile(sourcePath, "必须恰好包含且按顺序声明文件: "
                    + String.join(", ", contract.requiredFiles()));
        }
    }

    private void validateFilePath(String path, String sourcePath) {
        if (path.indexOf('\\') >= 0 || path.startsWith("/") || path.startsWith("//")
                || path.matches("^[A-Za-z]:.*")) {
            throw invalidFile(sourcePath, "文件路径必须是安全相对路径: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw invalidFile(sourcePath, "文件路径包含非法路径段: " + path);
            }
        }
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
        return new IllegalArgumentException("原生模板文件 [" + sourcePath + "] 非法: " + reason);
    }

    private IllegalArgumentException invalidFile(String sourcePath, String reason, Exception cause) {
        return new IllegalArgumentException("原生模板文件 [" + sourcePath + "] 非法: " + reason, cause);
    }

    private record CatalogContract(
            String type,
            String idPrefix,
            RagDocumentKind documentKind,
            String language,
            int documentCount,
            List<String> requiredFiles) {
    }

    private record CatalogFile(String relativePath, String content) {
    }
}
