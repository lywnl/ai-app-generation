package com.lyw.appgeneration.service;

import java.nio.file.Path;

/**
 * 在已解析的安全路径之间复制部署目录内容。
 *
 * <p>安全前提：源码根和部署根由本服务独占管理；同权限本地进程恶意替换
 * 私有存储根不属于当前单 JVM 操作租约模型。实现必须拒绝源码树和目标树中的
 * 符号链接，不支持安全目录句柄时固定失败。
 *
 * <p>复制沿用既有覆盖语义，不提供文件系统事务。失败时可能保留部分已覆盖内容，
 * 调用方必须跳过数据库部署状态更新，使该失败不会被记录为成功部署。
 */
public interface AppDeploymentFileService {

    void copyDirectory(Path sourceDirectory, Path deployDirectory);
}
