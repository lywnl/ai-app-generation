package com.lyw.appgeneration.manger;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * COS对象存储管理器
 *
 * @author yupi
 */
@Component
@Slf4j
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     * @return 上传结果
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 上传文件到 COS 并返回访问 URL
     *
     * @param key  COS对象键（完整路径）
     * @param file 要上传的文件
     * @return 文件的访问URL，失败返回null
     */
    public String uploadFile(String key, File file) {
        // 上传文件
        PutObjectResult result = putObject(key, file);
        if (result != null) {
            // 构建访问URL
            String url = buildPublicUrl(key);
            log.info("文件上传COS成功: {} -> {}", file.getName(), url);
            return url;
        } else {
            log.error("文件上传COS失败，返回结果为空");
            return null;
        }
    }

    /**
     * 规范化拼接 COS 公网访问 URL
     */
    private String buildPublicUrl(String key) {
        String host = StrUtil.trim(cosClientConfig.getHost());
        String normalizedKey = StrUtil.removePrefix(StrUtil.trim(key), "/");
        if (StrUtil.isBlank(host)) {
            return "/" + normalizedKey;
        }
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "https://" + host;
        }
        host = StrUtil.removeSuffix(host, "/");
        return host + "/" + normalizedKey;
    }
}
