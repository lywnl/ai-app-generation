package com.lyw.appgeneration.ai.image;

import com.lyw.appgeneration.ai.image.model.ImageCategoryEnum;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan.ImageSearchTask;
import com.lyw.appgeneration.ai.image.model.ImageCollectionPlan.LogoTask;
import com.lyw.appgeneration.ai.image.model.ImageResource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 图片请求与模型素材共用的上限；去重状态只属于当前调用。 */
public final class ImageCollectionPolicy {

    public static final int MAX_SEARCH_TASKS = 6;
    public static final int MAX_RESULTS_PER_SEARCH = 3;
    public static final int MAX_SEARCH_IMAGES = MAX_SEARCH_TASKS * MAX_RESULTS_PER_SEARCH;
    public static final int MAX_LOGO_IMAGES = 1;
    public static final int MAX_DESCRIPTION_CODE_POINTS = 80;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern URL_BOUNDARY_WHITESPACE =
            Pattern.compile("^\\s+|\\s+$", Pattern.UNICODE_CHARACTER_CLASS);

    private ImageCollectionPolicy() {
    }

    public static List<ImageSearchTask> selectSearchTasks(List<ImageSearchTask> tasks) {
        if (tasks == null) {
            return List.of();
        }
        List<ImageSearchTask> selected = new ArrayList<>();
        Set<String> queries = new HashSet<>();
        for (ImageSearchTask task : tasks) {
            String query = normalizeWhitespace(task == null ? null : task.query());
            if (!query.isEmpty() && queries.add(query.toLowerCase(Locale.ROOT))) {
                selected.add(new ImageSearchTask(query));
                if (selected.size() == MAX_SEARCH_TASKS) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    public static List<LogoTask> selectLogoTasks(List<LogoTask> tasks) {
        if (tasks != null) {
            for (LogoTask task : tasks) {
                if (task != null && !normalizeWhitespace(task.description()).isEmpty()) {
                    // 生成器仍收到完整设计，80 字符限制只用于最终素材说明。
                    return List.of(task);
                }
            }
        }
        return List.of();
    }

    public static List<ImageResource> limitSearchResults(List<ImageResource> images) {
        return selectResources(images, MAX_RESULTS_PER_SEARCH, 0);
    }

    public static List<ImageResource> limitLogoResults(List<ImageResource> images) {
        return selectResources(images, 0, MAX_LOGO_IMAGES);
    }

    public static List<ImageResource> limitContextResources(List<ImageResource> images) {
        return selectResources(images, MAX_SEARCH_IMAGES, MAX_LOGO_IMAGES);
    }

    private static List<ImageResource> selectResources(List<ImageResource> images,
                                                       int contentLimit, int logoLimit) {
        if (images == null) {
            return List.of();
        }
        List<ImageResource> selected = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        int contentCount = 0;
        int logoCount = 0;
        for (ImageResource image : images) {
            if (image == null || image.getCategory() == null) {
                continue;
            }
            ImageCategoryEnum category = image.getCategory();
            if ((category == ImageCategoryEnum.CONTENT && contentCount >= contentLimit)
                    || (category == ImageCategoryEnum.LOGO && logoCount >= logoLimit)) {
                continue;
            }
            String url = trimUrl(image.getUrl());
            if (url.isEmpty() || !urls.add(url)) {
                continue;
            }
            selected.add(ImageResource.builder().category(category)
                    .description(image.getDescription()).url(url).build());
            if (category == ImageCategoryEnum.CONTENT) {
                contentCount++;
            } else {
                logoCount++;
            }
            if (contentCount == contentLimit && logoCount == logoLimit) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    public static String normalizeWhitespace(String value) {
        return value == null ? "" : WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    /** 只清理地址边界，不改写查询参数、编码或路径。 */
    public static String trimUrl(String url) {
        return url == null ? "" : URL_BOUNDARY_WHITESPACE.matcher(url).replaceAll("");
    }

    public static String conciseDescription(String description) {
        String normalized = normalizeWhitespace(description);
        if (normalized.codePointCount(0, normalized.length()) <= MAX_DESCRIPTION_CODE_POINTS) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, MAX_DESCRIPTION_CODE_POINTS - 1);
        return normalized.substring(0, end) + "…";
    }
}
