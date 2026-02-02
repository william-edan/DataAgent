# 智能查询路由设计方案

**日期**: 2026-02-02
**作者**: Claude Code
**状态**: 设计完成，待实施

## 1. 背景与目标

### 1.1 当前架构

DataAgent 系统目前有两条独立的查询工作流：

1. **简单查询流程 (queryGraph)** - 轻量级SQL查询，快速返回结构化数据
2. **完整报告流程 (nl2sqlGraph)** - 包含深度分析、Python计算、报告生成的完整工作流

当前实现需要前端手动选择调用 `/api/query` 或 `/api/stream/search`，用户体验不够智能。

### 1.2 设计目标

实现智能路由机制，自动判断用户问题应该使用简单查询还是完整报告生成，提供统一的API入口和一致的流式响应格式。

**核心价值**：
- 用户无需理解技术差异，提出问题即可获得最优路径的结果
- 简单查询走轻量级路径，提升响应速度
- 复杂分析走完整流程，提供深度洞察

## 2. 整体架构

### 2.1 核心组件

```
用户请求 → GraphController (/api/stream/search)
           ↓
    QueryTypeClassifier (LLM分类)
      ↓                    ↓
   SIMPLE             COMPLEX
      ↓                    ↓
QueryService.       GraphService.
queryStream()       graphStreamProcess()
  (queryGraph)       (nl2sqlGraph)
      ↓                    ↓
   SSE流 ←─────统一格式─────┘
```

### 2.2 新增组件

**QueryTypeClassifier**
- 位置: `service/classifier/QueryTypeClassifier.java`
- 职责: 使用快速LLM模型分类用户问题
- 输出: `QueryType.SIMPLE` 或 `QueryType.COMPLEX`

### 2.3 改造组件

**GraphController**
- 改造点: 在 `streamSearch()` 方法开始处调用分类器
- 根据分类结果路由到不同服务

**QueryService**
- 新增方法: `queryStream()` 支持流式输出
- 保持原有 `query()` 方法向后兼容

## 3. 分类逻辑设计

### 3.1 QueryType 枚举

```java
public enum QueryType {
    SIMPLE,   // 简单查询：直接数据检索
    COMPLEX   // 复杂分析：需要深度分析、报告生成
}
```

### 3.2 分类标准

**SIMPLE (简单查询)**
- 关键词: 查询、列出、显示、有多少、是什么
- 行为: 单表或简单JOIN查询、数据检索、条件过滤
- 输出期望: 结构化数据表格

**COMPLEX (复杂分析)**
- 关键词: 分析、趋势、对比、为什么、建议、报告、洞察
- 行为: 多维度分析、时间序列、聚合计算、业务解读
- 输出期望: 带有可视化图表、文字解读的完整报告

### 3.3 分类Prompt模板

```
你是一个查询分类专家。分析用户问题，判断是简单查询(SIMPLE)还是复杂分析(COMPLEX)。

用户问题：{userQuery}

判断标准：
- SIMPLE: 直接数据检索，返回表格即可（如"查询所有用户"、"显示销售额"）
- COMPLEX: 需要趋势分析、对比、洞察、报告（如"分析销售趋势"、"为什么业绩下降"）

仅返回JSON格式：
{
  "queryType": "SIMPLE" 或 "COMPLEX",
  "reason": "简短原因（30字内）"
}
```

### 3.4 LLM配置

- **模型选择**: 优先使用快速模型（如 qwen-turbo、claude-haiku）
- **超时设置**: 3秒
- **失败降级**: 默认使用 `COMPLEX`（保守策略，避免功能缺失）

## 4. 流式输出统一

### 4.1 SSE事件格式

两条路径都返回相同的 `ServerSentEvent<GraphNodeResponse>` 格式。

### 4.2 简单查询的SSE事件流

```
1. 开始事件
   event: message
   data: { "nodeName": "QueryStart", "text": "开始处理查询..." }

2. 意图识别
   event: message
   data: { "nodeName": "IntentRecognitionNode", "text": "..." }

3. SQL生成
   event: message
   data: { "nodeName": "SqlGenerateNode", "text": "SELECT ...", "textType": "CODE" }

4. 执行结果
   event: message
   data: { "nodeName": "QuerySqlExecuteNode", "text": "{...}", "textType": "JSON" }

5. 完成事件
   event: complete
   data: { "agentId": "...", "threadId": "..." }
```

### 4.3 实现方式

QueryService 内部调用 `queryGraph.compile().stream()`，保持与 GraphService 一致的流式输出机制。

## 5. 错误处理

### 5.1 分类失败处理

```java
try {
    QueryType queryType = classifier.classify(query);
} catch (Exception e) {
    log.warn("Query classification failed, fallback to COMPLEX", e);
    queryType = QueryType.COMPLEX; // 保守降级
}
```

### 5.2 超时处理

- 分类超时（3秒）→ 自动降级为 `COMPLEX`
- 保证用户请求不会因分类失败而中断

### 5.3 错误传递

两条路径的错误都通过统一的 SSE error 事件返回，保持格式一致性。

## 6. 监控与日志

### 6.1 关键日志

```java
// 记录每次分类决策
log.info("Query classified: type={}, query='{}', reason='{}'",
         type, query, reason);

// 记录路由性能
log.info("Route to {}, classification_latency={}ms, total_latency={}ms",
         serviceName, classifyDuration, totalDuration);
```

### 6.2 建议收集的指标

1. 分类结果分布（SIMPLE vs COMPLEX 比例）
2. 分类耗时统计（P50、P95、P99）
3. 两条路径的执行时间对比
4. 分类准确率（通过后续用户行为推断）

### 6.3 后续优化方向

- 根据日志分析，优化分类 prompt
- 识别常见误判模式，建立规则白名单
- 收集用户反馈，持续改进分类逻辑

## 7. 实施计划

### 7.1 需要新增的文件

```
data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/
├── service/classifier/
│   ├── QueryTypeClassifier.java          # 分类器接口
│   └── QueryTypeClassifierImpl.java      # 分类器实现
├── enums/
│   └── QueryType.java                     # 分类枚举
├── dto/
│   └── QueryTypeResult.java               # 分类结果DTO
└── prompt/
    └── QueryTypeClassifierPrompt.java     # 分类Prompt模板
```

### 7.2 需要修改的文件

**GraphController.java**
```java
// 在 streamSearch 方法开始处添加分类逻辑
QueryTypeResult result = queryTypeClassifier.classify(query);

if (result.getQueryType() == QueryType.SIMPLE) {
    queryService.queryStream(sink, agentId, query);
} else {
    graphService.graphStreamProcess(sink, request);
}
```

**QueryService.java & QueryServiceImpl.java**
```java
// 新增流式方法
void queryStream(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink,
                 String agentId, String naturalQuery);
```

**application.yml（可选配置）**
```yaml
data-agent:
  classifier:
    enabled: true
    model: qwen-turbo        # 快速分类模型
    timeout: 3000            # 超时时间（毫秒）
    default-type: COMPLEX    # 失败时默认类型
```

### 7.3 实施步骤

**Phase 1: 基础实现（不影响现有功能）**
1. 实现 `QueryTypeClassifier` 和相关DTO
2. 添加单元测试验证分类逻辑

**Phase 2: 流式改造（向后兼容）**
3. 改造 `QueryService` 支持 `queryStream()` 方法
4. 保持原有 `query()` 方法不变

**Phase 3: 集成路由**
5. 在 `GraphController` 中集成分类逻辑
6. 添加分类日志和监控埋点

**Phase 4: 测试与上线**
7. 端到端测试（简单查询、复杂分析、边界场景）
8. 灰度发布，收集数据
9. 根据监控数据优化分类 prompt

## 8. 测试场景

### 8.1 简单查询示例

- "查询所有用户"
- "显示上个月的销售额"
- "列出库存不足的商品"
- "有多少订单处于待发货状态"

### 8.2 复杂分析示例

- "分析过去一年的销售趋势"
- "对比不同区域的业绩表现"
- "为什么上个月销售额下降了"
- "给我一份用户增长报告"

### 8.3 边界场景

- 分类器超时
- 分类器返回无效结果
- 用户问题为空或过短
- 同时包含简单和复杂特征的混合问题

## 9. 风险与缓解

### 9.1 分类准确率风险

**风险**: 分类错误导致用户体验不佳
**缓解**:
- 第一版使用保守策略（不确定时选COMPLEX）
- 持续收集日志优化分类逻辑
- 后续可添加用户手动切换功能

### 9.2 性能开销风险

**风险**: 额外的LLM调用增加延迟
**缓解**:
- 使用快速模型（< 100ms）
- 设置严格超时（3秒）
- 简单查询节省的时间远大于分类开销

### 9.3 兼容性风险

**风险**: 改动影响现有API
**缓解**:
- 保持 `/api/query` 和 `/api/stream/search` 原有行为
- 分阶段实施，逐步迁移
- 充分的测试覆盖

## 10. 成功标准

### 10.1 功能指标

- ✅ 分类准确率 > 85%（通过人工抽样验证）
- ✅ 分类延迟 P95 < 500ms
- ✅ 简单查询平均耗时降低 > 30%

### 10.2 体验指标

- ✅ 用户无需手动选择查询类型
- ✅ 统一的流式响应格式
- ✅ 错误场景有合理降级

### 10.3 稳定性指标

- ✅ 分类器失败不影响功能可用性
- ✅ 无现有功能回归问题

---

**设计完成日期**: 2026-02-02
**预计实施周期**: 1-2周
**下一步行动**: 创建实施计划，准备开发环境
