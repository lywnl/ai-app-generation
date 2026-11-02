# L0 计划恢复编码的停写升级与回退

新版值编码为 formatVersion=2，Redis 键仍是 `chat-memory:l0:v2:<appId>`。不要让新旧后端同时写同一应用窗口。升级前停止旧写入进程；新程序读取 v1，首次成功写入以 CAS 升至 v2，旧数组继续现有冷重建。

回退前停止该应用的全部写入进程，保留新版构建与依赖。将沿用的 Redis 配置设置为环境变量 `L0_REDIS_HOST`、`L0_REDIS_PORT`、`L0_REDIS_DATABASE`、可选 `L0_REDIS_USERNAME`/`L0_REDIS_PASSWORD`，不要把密码放进命令参数或日志。在项目根完成新版编译后，先生成运行依赖 classpath：

```sh
mvn org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=runtime \
  -Dmdep.outputFile=.codex/plan-restore-l0/runtime-classpath.txt
```

命令沿用部署环境既有 Maven 设置；本工作区已使用项目内离线缓存验证该目标。随后显式指定一个应用 ID：

```sh
java -cp "target/classes:$(cat .codex/plan-restore-l0/runtime-classpath.txt)" \
  com.lyw.appgeneration.ai.memory.L0PlanRestoreDowngrade \
  --writers-stopped --app-id <应用ID>
```

入口不启动后端或 Spring 容器，只读取指定键。它校验来源、移除 PLAN_RESTORE、回写 v1，保留所有原文、sequence、epoch、摘要依赖和剩余 TTL。旧值/缺失键无操作，未知/损坏格式拒绝。精确值 CAS 失败返回 CONFLICT 并非零退出，先排查仍在写入的进程再重试。确认 DOWNGRADED 或 ALREADY_OLD_FORMAT 后才启动旧后端。工具没有全库扫描、清空或自动枚举应用功能。

本次开发只在隔离测试键执行过回退；业务键需运维按实际应用 ID 操作。
