package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.exception.RerankException;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagRerankServiceVueTest {

    @Test
    void nativeRerankTextUsesSemanticsAndNeverReadsSourceCode() {
        RagProperties properties = new RagProperties();
        RagRerankService service = new RagRerankService(properties, "test-key");
        TemplateDoc document = new TemplateDoc();
        document.setTitle("安全 Markdown 编辑器");
        document.setDescription("实时预览与协议过滤");
        document.setEmbedText("Markdown 编辑器 链接 安全");
        document.setFramework("none");
        document.setLanguage("html-css-javascript");
        document.setBuildTool("none");
        document.setTech(List.of("vanilla-js"));
        TemplateDoc.TemplateFile file = new TemplateDoc.TemplateFile();
        file.setPath("script.js");
        file.setContent("SOURCE_SECRET_NEVER_SEND");
        document.setFiles(List.of(file));
        RetrievedSnippet snippet = RetrievedSnippet.builder()
                .id("multi-markdown").document(document).build();

        String text = service.buildNativeDocumentText(snippet);

        assertTrue(text.contains("安全 Markdown 编辑器"));
        assertTrue(text.contains("Markdown 编辑器 链接 安全"));
        assertTrue(text.contains("script.js"));
        assertFalse(text.contains("SOURCE_SECRET_NEVER_SEND"));
    }

    @Test
    void returnsCompleteUniqueVueRerankResults() throws IOException {
        String response = """
                {"output":{"results":[
                  {"index":1,"relevance_score":0.9},
                  {"index":0,"relevance_score":0.8}
                ]}}
                """;

        List<TemplateDoc> reranked = rerankVue(
                response, List.of(document("first"), document("second")), 2);

        assertEquals(List.of("second", "first"), reranked.stream()
                .map(TemplateDoc::getId).toList());
    }

    @Test
    void rejectsPartialVueRerankResults() throws IOException {
        String response = """
                {"output":{"results":[{"index":0,"relevance_score":0.9}]}}
                """;

        assertInvalidVueResponse(response, List.of(document("first"), document("second")), 2);
    }

    @Test
    void rejectsDuplicateVueRerankIndexes() throws IOException {
        String response = """
                {"output":{"results":[
                  {"index":0,"relevance_score":0.9},
                  {"index":0,"relevance_score":0.8}
                ]}}
                """;

        assertInvalidVueResponse(response, List.of(document("first"), document("second")), 2);
    }

    @Test
    void rejectsMissingVueRerankIndex() throws IOException {
        String response = """
                {"output":{"results":[{"relevance_score":0.9}]}}
                """;

        assertInvalidVueResponse(response, List.of(document("first")), 1);
    }

    @Test
    void rejectsMissingVueRerankScore() throws IOException {
        String response = """
                {"output":{"results":[{"index":0}]}}
                """;

        assertInvalidVueResponse(response, List.of(document("first")), 1);
    }

    @Test
    void rejectsNonFiniteVueRerankScore() throws IOException {
        String response = """
                {"output":{"results":[{"index":0,"relevance_score":"NaN"}]}}
                """;

        assertInvalidVueResponse(response, List.of(document("first")), 1);
    }

    private void assertInvalidVueResponse(String response,
                                          List<TemplateDoc> candidates,
                                          int topK) throws IOException {
        assertThrows(RerankException.class,
                () -> rerankVue(response, candidates, topK));
    }

    private List<TemplateDoc> rerankVue(String response,
                                        List<TemplateDoc> candidates,
                                        int topK) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/rerank", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RagProperties properties = new RagProperties();
            properties.getRerank().setBaseUrl(
                    "http://" + server.getAddress().getHostString() + ":"
                            + server.getAddress().getPort() + "/rerank");
            RagRerankService service = new RagRerankService(properties, "test-key");
            return service.rerankVue("Vue 需求", candidates, topK);
        } finally {
            server.stop(0);
        }
    }

    private TemplateDoc document(String id) {
        TemplateDoc document = new TemplateDoc();
        document.setId(id);
        document.setFiles(List.of());
        return document;
    }
}
