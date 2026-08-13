package com.lyw.appgeneration.service;

import com.lyw.appgeneration.service.AppStoragePathResolver.FrozenAppPaths;

/**
 * 安全、幂等删除一次冻结后的应用源码与可选部署目录。
 *
 * <p>源码根和部署根由本服务独占管理；同权限本地进程恶意篡改私有存储根不属于
 * 当前单 JVM 租约模型。实现拒绝目录树中的符号链接或特殊文件，不支持安全目录
 * 句柄时固定失败。源码先于部署目录删除，跨目录不提供文件系统事务；任一步失败
 * 后调用方必须停止数据库删除，并允许之后按相同冻结语义重试。
 */
public interface AppDeletionFileService {

    void delete(FrozenAppPaths paths);
}
