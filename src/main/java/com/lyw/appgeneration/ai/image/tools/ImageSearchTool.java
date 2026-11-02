package com.lyw.appgeneration.ai.image.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.image.ImageCollectionPolicy;
import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 图片搜索工具(根据关键词搜索内容图片)
 */
@Slf4j
@Component
public class ImageSearchTool {

    private static final String PEXELS_API_URL = "https://api.pexels.com/v1/search";
    private final String apiUrl;

    @Value("${pexels.api-key}")
    private String pexelsApiKey;

    public ImageSearchTool() {
        this(PEXELS_API_URL);
    }

    /** 包内测试可接入本机 HTTP 替身，生产入口仍使用 Pexels。 */
    ImageSearchTool(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    @Tool("搜索内容相关的图片，用于网站内容展示")
    public List<ImageResource> searchContentImages(@P("搜索关键词") String query) {
        String normalizedQuery = ImageCollectionPolicy.normalizeWhitespace(query);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }
        List<ImageResource> imageList = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        try (HttpResponse response = HttpRequest.get(apiUrl)
                .header("Authorization", pexelsApiKey)
                .form("query", normalizedQuery)
                .form("per_page", ImageCollectionPolicy.MAX_RESULTS_PER_SEARCH)
                .form("page", 1)
                .execute()) {
            if (response.isOk()) {
                JSONObject result = JSONUtil.parseObj(response.body());
                JSONArray photos = result.getJSONArray("photos");
                if (photos == null) {
                    return List.of();
                }
                for (int i = 0; i < photos.size()
                        && imageList.size() < ImageCollectionPolicy.MAX_RESULTS_PER_SEARCH; i++) {
                    if (!(photos.get(i) instanceof JSONObject photo)
                            || !(photo.get("src") instanceof JSONObject src)) {
                        continue;
                    }
                    String url = ImageCollectionPolicy.trimUrl(src.getStr("medium"));
                    if (url.isEmpty() || !urls.add(url)) {
                        continue;
                    }
                    imageList.add(ImageResource.builder()
                            .category(ImageCategoryEnum.CONTENT)
                            .description(photo.getStr("alt", normalizedQuery))
                            .url(url)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Pexels API 调用失败: {}", e.getMessage(), e);
        }
        return imageList;
    }
}
