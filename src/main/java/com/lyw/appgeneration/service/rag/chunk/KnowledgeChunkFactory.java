package com.lyw.appgeneration.service.rag.chunk;

import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把 Vue RAG 父文档转换为检索子块，不复制源码内容。
 */
public class KnowledgeChunkFactory {

    /**
     * 按父文档类型生成顺序稳定的检索子块。
     *
     * @param document 已校验的父文档
     * @return 检索子块
     */
    public List<KnowledgeChunk> createChunks(TemplateDoc document) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        chunks.add(createChunk(document, RagChunkKind.OVERVIEW, overviewText(document)));
        if (document.getDocumentKind() == RagDocumentKind.PROJECT_SKELETON) {
            chunks.add(createChunk(document, RagChunkKind.ENGINEERING, engineeringText(document)));
        }
        return List.copyOf(chunks);
    }

    private KnowledgeChunk createChunk(TemplateDoc document, RagChunkKind chunkKind, String searchText) {
        String suffix = chunkKind.name().toLowerCase(Locale.ROOT);
        return new KnowledgeChunk(
                document.getId() + ":" + suffix,
                document.getId(),
                document.getDocumentKind(),
                chunkKind,
                searchText
        );
    }

    private String overviewText(TemplateDoc document) {
        return String.join("\n",
                field("标题", document.getTitle()),
                field("分类", document.getCategory()),
                field("描述", document.getDescription()),
                field("检索描述", document.getEmbedText()),
                field("风格", join(document.getStyle())),
                field("技术栈", join(document.getTech()))
        );
    }

    private String engineeringText(TemplateDoc document) {
        return String.join("\n",
                field("框架", document.getFramework()),
                field("语言", document.getLanguage()),
                field("构建工具", document.getBuildTool()),
                field("依赖", join(document.getDependencies())),
                field("开发依赖", join(document.getDevDependencies())),
                field("文件路径", document.getFiles().stream()
                        .map(TemplateDoc.TemplateFile::getPath)
                        .sorted()
                        .collect(Collectors.joining(", ")))
        );
    }

    private String field(String name, String value) {
        return name + ": " + (value == null ? "" : value);
    }

    private String join(List<String> values) {
        return values == null ? "" : String.join(", ", values);
    }

    private String join(Map<String, String> values) {
        if (values == null) {
            return "";
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
