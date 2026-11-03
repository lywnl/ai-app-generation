package com.lyw.appgeneration.ai.image;

/** 本轮显式图片配额；每主题和 Logo 上限沿用统一策略。 */
public record ImageCollectionQuota(int maxSearchTasks) {
    public static final ImageCollectionQuota INITIAL = new ImageCollectionQuota(6);
    public static final ImageCollectionQuota FOLLOW_UP = new ImageCollectionQuota(2);
    public ImageCollectionQuota {
        if (maxSearchTasks < 1 || maxSearchTasks > ImageCollectionPolicy.MAX_SEARCH_TASKS) {
            throw new IllegalArgumentException("图片主题配额必须介于1与6之间");
        }
    }
    public int maxSearchImages() { return maxSearchTasks * ImageCollectionPolicy.MAX_RESULTS_PER_SEARCH; }
}
