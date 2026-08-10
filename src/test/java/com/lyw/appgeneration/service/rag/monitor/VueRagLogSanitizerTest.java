package com.lyw.appgeneration.service.rag.monitor;

import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueRagLogSanitizerTest {

    @Test
    void queryHashUsesUtf8Sha256AndReturnsTwelveLowercaseHexCharacters() {
        String hash = VueRagLogSanitizer.queryHash("原始中文查询🔒");

        assertEquals("f4742335b9c4", hash);
        assertTrue(hash.matches("[0-9a-f]{12}"));
        assertEquals("e3b0c44298fc", VueRagLogSanitizer.queryHash(null));
    }

    @Test
    void candidateIdsNeverContainSourceOrOtherParentFields() {
        TemplateDoc document = new TemplateDoc();
        document.setId("feature-login-001");
        document.setTitle("标题不允许进入候选日志");
        TemplateDoc.TemplateFile file = new TemplateDoc.TemplateFile();
        file.setPath("src/Login.vue");
        file.setContent("SECRET_SOURCE_MARKER");
        document.setFiles(List.of(file));
        List<RankedCandidate> candidates = List.of(
                new RankedCandidate(document.getId(), RagDocumentKind.FEATURE_SNIPPET, 1, 0.9),
                new RankedCandidate("feature-table-002", RagDocumentKind.FEATURE_SNIPPET, 2, 0.8));

        List<String> ids = VueRagLogSanitizer.candidateIds(candidates);
        String logData = ids.toString();

        assertEquals(List.of("feature-login-001", "feature-table-002"), ids);
        assertFalse(logData.contains("SECRET_SOURCE_MARKER"));
        assertFalse(logData.contains("标题不允许进入候选日志"));
        assertFalse(logData.contains("src/Login.vue"));
    }
}
