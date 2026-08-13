package com.lyw.appgeneration.service;

/** 在独立数据库事务中永久删除应用范围数据。 */
public interface AppDeletionPersistenceService {

    void deleteAppData(Long appId);
}
