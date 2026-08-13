package com.lyw.appgeneration.service;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.ThrowUtils;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public interface ProjectDownloadService {

    /**
     * 下载项目代码文件
     * @param projectPath 项目路径
     * @param downloadFileName 下载文件名
     * @param response 响应对象
     */
    default void downloadProjectAsZip(
            String projectPath, String downloadFileName, HttpServletResponse response) {
        ThrowUtils.throwIf(StrUtil.isBlank(projectPath),
                ErrorCode.PARAMS_ERROR, "项目路径不能为空");
        try {
            downloadProjectAsZip(Path.of(projectPath), downloadFileName, response);
        } catch (InvalidPathException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "项目路径格式无效");
        }
    }

    void downloadProjectAsZip(
            Path projectPath, String downloadFileName, HttpServletResponse response);
}
