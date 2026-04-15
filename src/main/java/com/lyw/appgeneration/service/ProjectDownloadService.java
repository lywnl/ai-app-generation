package com.lyw.appgeneration.service;

import jakarta.servlet.http.HttpServletResponse;

public interface ProjectDownloadService {

    /**
     * 下载项目代码文件
     * @param projectPath 项目路径
     * @param downloadFileName 下载文件名
     * @param response 响应对象
     */
    void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response);
}
