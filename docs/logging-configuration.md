# 日志配置说明

## 概述

DataAgent 使用 Logback 作为日志框架，提供完整的日志管理方案，支持日志轮转、压缩、分级输出和异步写入。

## 日志文件结构

日志文件位于 `logs` 目录下（可通过 `application.yml` 中的 `logging.file.path` 配置）：

```
logs/
├── data-agent-all.log        # 所有级别的日志
├── data-agent-info.log       # INFO 及以上级别日志
├── data-agent-warn.log       # WARN 及以上级别日志
├── data-agent-error.log      # ERROR 级别日志
├── data-agent-sql.log        # SQL 执行日志
└── archive/                  # 归档目录（压缩后的历史日志）
    ├── data-agent-all-2026-02-02.0.log.gz
    ├── data-agent-info-2026-02-02.0.log.gz
    └── ...
```

## 日志级别配置

### 在 application.yml 中配置

```yaml
logging:
  level:
    root: INFO                                              # 根日志级别
    com.alibaba.cloud.ai.dataagent: INFO                    # DataAgent 整体日志
    com.alibaba.cloud.ai.dataagent.workflow.node: DEBUG     # 工作流节点详细日志
    com.alibaba.cloud.ai.dataagent.service.classifier: INFO # 查询分类日志
```

### 日志级别说明

- **TRACE**: 最详细的调试信息
- **DEBUG**: 调试信息，开发阶段使用
- **INFO**: 一般信息，记录关键业务流程
- **WARN**: 警告信息，需要关注但不影响运行
- **ERROR**: 错误信息，需要立即处理

## 日志轮转策略

### 按大小和时间轮转

- **data-agent-all.log**: 每个文件最大 100MB，保留 30 天，总大小不超过 10GB
- **data-agent-info.log**: 每个文件最大 100MB，保留 30 天，总大小不超过 5GB
- **data-agent-warn.log**: 每个文件最大 100MB，保留 60 天，总大小不超过 3GB
- **data-agent-error.log**: 每个文件最大 50MB，保留 90 天，总大小不超过 2GB
- **data-agent-sql.log**: 每个文件最大 100MB，保留 7 天，总大小不超过 1GB

### 压缩归档

- 日志轮转后自动压缩为 `.gz` 格式
- 存储在 `logs/archive/` 目录下
- 文件命名格式: `{app-name}-{level}-{date}.{index}.log.gz`

## 不同环境配置

### 开发环境 (dev)

```bash
java -jar -Dspring.profiles.active=dev data-agent-management.jar
```

- 日志级别: DEBUG
- 输出: 控制台 + 文件
- 详细的 workflow 执行日志

### 测试环境 (test)

```bash
java -jar -Dspring.profiles.active=test data-agent-management.jar
```

- 日志级别: INFO
- 输出: 控制台 + 异步文件
- 记录所有日志和错误日志

### 生产环境 (prod)

```bash
java -jar -Dspring.profiles.active=prod data-agent-management.jar
```

- 日志级别: INFO
- 输出: 控制台 + 异步文件（分级）
- 完整的日志分级管理
- Spring 框架日志降级为 WARN

## 异步日志配置

系统使用异步 Appender 提升性能：

- **队列大小**: 512（ALL/INFO），256（WARN/ERROR）
- **丢弃阈值**: 0（不丢弃任何日志）
- **调用者信息**: ERROR/WARN 级别保留，INFO/DEBUG 不保留（提升性能）

## 特殊日志配置

### SQL 日志

```yaml
logging:
  level:
    com.alibaba.cloud.ai.dataagent.mapper: DEBUG
```

独立的 SQL 日志文件 `data-agent-sql.log`，记录：
- MyBatis SQL 语句
- 参数绑定
- 执行结果

### 查询路由日志

```yaml
logging:
  level:
    com.alibaba.cloud.ai.dataagent.service.classifier: INFO
```

记录智能查询路由的决策过程：
- 查询分类结果（SIMPLE/COMPLEX）
- 分类原因
- 分类耗时

### Workflow 节点日志

```yaml
logging:
  level:
    com.alibaba.cloud.ai.dataagent.workflow.node: INFO  # 或 DEBUG
```

记录工作流节点的执行：
- 节点启动/完成
- 节点输入/输出
- 节点异常

## 日志格式

### 控制台格式

```
2026-02-02 18:10:08.123  INFO 12345 --- [nio-8066-exec-1] c.a.c.a.d.controller.GraphController    : Routing to SIMPLE query path for query: '查询员工信息'
```

### 文件格式

```
2026-02-02 18:10:08.123 INFO 12345 --- [nio-8066-exec-1] c.a.c.a.d.controller.GraphController : Routing to SIMPLE query path for query: '查询员工信息'
```

## 日志查看命令

### 实时查看日志

```bash
# 查看所有日志
tail -f logs/data-agent-all.log

# 查看错误日志
tail -f logs/data-agent-error.log

# 查看 SQL 日志
tail -f logs/data-agent-sql.log

# 多个日志文件同时查看
tail -f logs/data-agent-all.log logs/data-agent-error.log
```

### 搜索日志

```bash
# 搜索特定关键词
grep "QueryTypeClassifier" logs/data-agent-all.log

# 搜索错误信息
grep "ERROR" logs/data-agent-all.log

# 搜索 SQL 执行
grep "SqlGenerateNode" logs/data-agent-all.log

# 搜索并显示前后 5 行
grep -C 5 "IllegalStateException" logs/data-agent-error.log
```

### 查看归档日志

```bash
# 解压查看
gunzip -c logs/archive/data-agent-error-2026-02-01.0.log.gz | less

# 直接搜索压缩文件
zgrep "ERROR" logs/archive/data-agent-error-2026-02-01.0.log.gz
```

## 性能优化建议

### 生产环境优化

1. **使用异步日志**: 已默认启用，避免阻塞业务线程
2. **调整日志级别**:
   - 业务代码: INFO
   - 框架代码: WARN
   - 仅调试时使用 DEBUG
3. **合理设置保留时间**: 根据磁盘空间和法规要求调整
4. **监控日志文件大小**: 防止磁盘空间耗尽

### 调试时优化

```yaml
# application-dev.yml
logging:
  level:
    com.alibaba.cloud.ai.dataagent: DEBUG
    com.alibaba.cloud.ai.dataagent.workflow: TRACE
```

## 常见问题

### Q: 如何修改日志存储路径？

A: 在 `application.yml` 中修改:

```yaml
logging:
  file:
    path: /var/log/data-agent  # 自定义路径
```

### Q: 如何增加日志保留时间？

A: 修改 `logback-spring.xml` 中的 `maxHistory` 参数:

```xml
<rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
    <maxHistory>90</maxHistory>  <!-- 改为 90 天 -->
</rollingPolicy>
```

### Q: 如何禁用某个日志文件？

A: 在 `logback-spring.xml` 中注释掉对应的 appender-ref:

```xml
<root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <!-- <appender-ref ref="ASYNC_FILE_WARN"/> -->  注释掉不需要的
</root>
```

### Q: 如何查看当前日志配置？

A: 启动应用时，Logback 会在控制台输出配置信息，或访问 Spring Boot Actuator 端点:

```bash
curl http://localhost:8066/actuator/loggers
```

## 日志监控建议

生产环境建议集成日志监控系统：

- **ELK Stack**: Elasticsearch + Logstash + Kibana
- **Loki**: Grafana Loki + Grafana
- **Splunk**: 企业级日志分析平台
- **阿里云日志服务**: SLS

## 相关文件

- `data-agent-management/src/main/resources/logback-spring.xml` - Logback 配置
- `data-agent-management/src/main/resources/application.yml` - 日志级别配置
- `.gitignore` - 忽略日志文件提交

## 参考资料

- [Logback 官方文档](http://logback.qos.ch/manual/)
- [Spring Boot Logging 文档](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.logging)
