package com.lyw.appgeneration.ai.image.tools;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImageSearchToolTest {
    private ImageSearchHttpStub server;
    private ImageSearchTool tool;

    @BeforeEach
    void startServer() throws IOException {
        server = new ImageSearchHttpStub();
        tool = server.tool();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void 实际HTTP只请求第一页三张并规范化搜索词() {
        server.respond(200, """
                {"photos":[
                  {"alt":"办公空间","src":{"medium":"https://example.com/1"}},
                  {"src":{"medium":"https://example.com/2"}},
                  {"alt":"会议室","src":{"medium":"https://example.com/3"}},
                  {"alt":"不应交付","src":{"medium":"https://example.com/4"}}
                ]}
                """);

        List<ImageResource> images = tool.searchContentImages("  办公\n\t 空间\u3000");

        assertEquals(1, server.requests().size());
        var request = server.requests().getFirst();
        assertEquals("GET", request.method());
        assertEquals("test-key", request.authorization());
        assertEquals(Map.of("query", "办公 空间", "per_page", "3", "page", "1"), request.parameters());
        assertEquals(List.of("https://example.com/1", "https://example.com/2", "https://example.com/3"),
                images.stream().map(ImageResource::getUrl).toList());
        assertEquals("办公 空间", images.get(1).getDescription(), "没有 alt 时沿用关键词");
        assertTrue(images.stream().allMatch(image -> image.getCategory() == ImageCategoryEnum.CONTENT));
    }

    @Test
    void 空字段和重复图片不消耗三张配额且地址参数完整() {
        server.respond(200, """
                {"photos":[
                  null, "invalid", {}, {"src":null}, {"src":"invalid"},
                  {"src":{}}, {"src":{"medium":"  "}},
                  {"alt":"首张","src":{"medium":" https://example.com/photo?width=1&sig=%2F "}},
                  {"alt":"重复","src":{"medium":"https://example.com/photo?width=1&sig=%2F"}},
                  {"src":{"medium":"https://example.com/photo?width=2&sig=%2F"}},
                  {"src":{"medium":"https://example.com/third"}},
                  {"src":{"medium":"https://example.com/fourth"}}
                ]}
                """);

        List<ImageResource> images = tool.searchContentImages("照片");

        assertEquals(List.of("https://example.com/photo?width=1&sig=%2F",
                        "https://example.com/photo?width=2&sig=%2F", "https://example.com/third"),
                images.stream().map(ImageResource::getUrl).toList());
        assertEquals("首张", images.getFirst().getDescription());
    }

    @Test
    void 空关键词不发出请求() {
        assertTrue(tool.searchContentImages(null).isEmpty());
        assertTrue(tool.searchContentImages("").isEmpty());
        assertTrue(tool.searchContentImages(" \t\n\u3000\u00a0").isEmpty());
        assertTrue(server.requests().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"photos\":null}", "{\"photos\":[]}", "{\"photos\":42}", "invalid", ""})
    void 空或无法解析响应降级为空列表(String body) {
        server.respond(200, body);

        assertTrue(tool.searchContentImages("照片").isEmpty());
        assertEquals(1, server.requests().size());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void HTTP错误状态不交付图片(int status) {
        server.respond(status, "{\"photos\":[{\"src\":{\"medium\":\"https://example.com/error\"}}]}");

        assertTrue(tool.searchContentImages("照片").isEmpty());
        assertEquals(1, server.requests().size());
    }
}
