package com.lyw.appgeneration.service;

import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.utils.SecureFileAccess;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 公开预览仅允许应用内普通资源，计划旁车与路径别名不得经过此入口。 */
@Component
public final class StaticPreviewResourceResolver {
    private static final Pattern RESIDUAL_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");
    private final Path outputRoot;

    public StaticPreviewResourceResolver() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR));
    }

    public StaticPreviewResourceResolver(Path outputRoot) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    public PreviewResource resolve(String mappedKey, String rawUri, String contextPath) throws IOException {
        String prefix = contextPath + "/static/";
        if (!rawUri.startsWith(prefix)) throw new IOException("静态路径无效");
        String rawPath = rawUri.substring(prefix.length());
        String[] segments = rawPath.split("/", -1);
        List<String> decoded = new ArrayList<>();
        for (int index = 0; index < segments.length; index++) {
            if (index == 1 && segments.length == 2 && segments[index].isEmpty()) break;
            decoded.add(decodeSegment(segments[index]));
        }
        if (decoded.isEmpty() || !decoded.getFirst().equals(mappedKey)) {
            throw new IOException("静态应用路径不匹配");
        }
        if (segments.length == 1) return new PreviewResource(true, null);
        Path project = outputRoot.resolve(decoded.getFirst());
        Path target = project;
        for (int index = 1; index < decoded.size(); index++) target = target.resolve(decoded.get(index));
        if (decoded.size() == 1) target = target.resolve("index.html");
        if (!target.normalize().startsWith(project)) throw new IOException("静态路径越界");
        SecurePreviewResource resource = new SecurePreviewResource(target);
        resource.attributes();
        return new PreviewResource(false, resource);
    }

    private String decodeSegment(String raw) throws IOException {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(bytes.length);
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] != '%') {
                decoded.write(bytes[index]);
                continue;
            }
            if (index + 2 >= bytes.length) throw new IOException("不完整的路径编码");
            int high = Character.digit((char) bytes[++index], 16);
            int low = Character.digit((char) bytes[++index], 16);
            if (high < 0 || low < 0) throw new IOException("非法路径编码");
            decoded.write(high * 16 + low);
        }
        String name = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
        String folded = name.toLowerCase(Locale.ROOT);
        if (name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains(";")
                || name.codePoints().anyMatch(Character::isISOControl)
                || RESIDUAL_ESCAPE.matcher(name).find() || folded.equals(".plan.json")
                || (folded.startsWith(".plan-") && folded.endsWith(".tmp"))) {
            throw new IOException("静态资源路径不可访问");
        }
        return name;
    }

    public record PreviewResource(boolean directoryRedirect, Resource resource) {
    }

    /** 不预持有文件流；每次响应写出及元数据读取都重新通过安全目录边界。 */
    private static final class SecurePreviewResource extends AbstractResource {
        private final Path target;

        private SecurePreviewResource(Path target) {
            this.target = target;
        }

        private BasicFileAttributes attributes() throws IOException {
            return SecureFileAccess.withDirectory(target.getParent(),
                    directory -> SecureFileAccess.regularAttributes(directory, target.getFileName()));
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return SecureFileAccess.withDirectory(target.getParent(), directory ->
                    Channels.newInputStream(SecureFileAccess.openRegularFile(directory, target.getFileName())));
        }

        @Override
        public long contentLength() throws IOException {
            return attributes().size();
        }

        @Override
        public long lastModified() throws IOException {
            return attributes().lastModifiedTime().toMillis();
        }

        @Override
        public String getFilename() {
            return target.getFileName().toString();
        }

        @Override
        public String getDescription() {
            return "受限静态预览资源";
        }
    }
}
