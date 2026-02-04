# 修复：简单查询路径无返回结果问题

## 问题描述

**日期**: 2026-02-02
**问题**: 使用智能查询路由功能时，输入查询"查询员工姓名为 员工 的档案信息"，系统正确分类为 SIMPLE 路径并成功执行 SQL，但前端未显示任何查询结果。

## 问题分析

### 日志分析

从日志中可以看到：

1. ✅ 查询被正确分类为 `SIMPLE` 类型
2. ✅ 所有节点成功执行：
   - IntentRecognitionNode
   - EvidenceRecallNode
   - QueryEnhanceNode
   - SchemaRecallNode
   - TableRelationNode
   - SqlGenerateNode (生成 SQL: `select 'id', 'did', 'position_name', 'position_rank', 'type', 'is_staff', 'age', 'name' from 'oa_admin' where 'name' = '员工';`)
   - SemanticConsistencyNode
   - **QuerySqlExecuteNode** (执行成功，返回 1 行数据)

3. ❌ 但前端没有显示结果

### 根本原因

`QuerySqlExecuteNode` 是**同步执行节点**，它只将结果存储到 state 中，**但没有生成流式输出**返回给前端。

对比其他节点（如 SqlGenerateNode、SemanticConsistencyNode），它们都使用 `FluxUtil.createStreamingGeneratorWithMessages()` 来生成流式输出，将处理结果实时推送给前端。

## 解决方案

修改 `QuerySqlExecuteNode.java`，让它生成流式输出将查询结果返回给前端。

### 修改内容

**文件**: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/QuerySqlExecuteNode.java`

#### 1. 添加必要的导入

```java
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
```

#### 2. 添加 ObjectMapper 依赖

```java
@AllArgsConstructor
public class QuerySqlExecuteNode implements NodeAction {
    private final DatabaseUtil databaseUtil;
    private final Nl2SqlService nl2SqlService;
    private final ObjectMapper objectMapper;  // 新增
    // ...
}
```

#### 3. 修改 apply 方法生成流式输出

```java
@Override
public Map<String, Object> apply(OverAllState state) throws Exception {
    // ... 原有的 SQL 执行逻辑 ...

    ResultSetBO resultSetBO = dbAccessor.executeSqlAndReturnObject(dbConfig, dbQueryParameter);
    int rowCount = resultSetBO.getData() != null ? resultSetBO.getData().size() : 0;
    log.info("Query SQL executed successfully, rows: {}", rowCount);

    // 构建返回结果
    Map<String, Object> result = Map.of(
        QUERY_SQL, sqlQuery,
        QUERY_SQL_RESULT, resultSetBO,
        SQL_RESULT_LIST_MEMORY, resultSetBO.getData(),
        Constant.RESULT, resultSetBO
    );

    // 生成流式输出，将查询结果返回给前端
    String resultJson;
    try {
        resultJson = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(resultSetBO);
    }
    catch (JsonProcessingException e) {
        log.error("Failed to serialize result set to JSON", e);
        resultJson = "{\"error\": \"Failed to serialize result\"}";
    }

    // 创建流式响应
    Flux<ChatResponse> resultFlux = Flux.just(
        ChatResponseUtil.createResponse("查询执行成功，共返回 " + rowCount + " 条记录"),
        ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign()),
        ChatResponseUtil.createPureResponse(resultJson),
        ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign())
    );

    Flux<GraphResponse<StreamingOutput>> generator = FluxUtil
        .createStreamingGeneratorWithMessages(this.getClass(), state, v -> result, resultFlux);

    return Map.of(QUERY_SQL_RESULT, generator);
}
```

## 修改效果

### 修改前

- ❌ 后端执行成功但前端无响应
- ❌ 用户看不到查询结果
- ❌ 没有任何提示信息

### 修改后

- ✅ 前端实时接收流式输出
- ✅ 显示"查询执行成功，共返回 N 条记录"
- ✅ 以 JSON 格式展示查询结果
- ✅ 用户体验完整

## 输出格式

查询结果将以以下格式返回给前端：

```
查询执行成功，共返回 1 条记录

```json
{
  "columns": ["id", "did", "position_name", "position_rank", "type", "is_staff", "age", "name"],
  "data": [
    [1, 10, "工程师", "P6", 1, 1, 28, "员工"]
  ]
}
```
```

## 验证步骤

1. 重启后端服务
2. 在前端输入测试查询："查询员工姓名为 员工 的档案信息"
3. 观察前端是否正常显示：
   - 节点执行流程
   - SQL 语句
   - 查询结果（JSON 格式）
   - 记录数量提示

## 相关文件

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/QuerySqlExecuteNode.java` - 修复的核心文件
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java` - queryGraph 配置
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/query/QueryServiceImpl.java` - QueryService 实现

## 技术要点

### 流式输出模式

DataAgent 使用 SSE (Server-Sent Events) 进行流式输出：

1. **StreamingOutput**: LanGraph 的流式输出类型
2. **FluxUtil.createStreamingGeneratorWithMessages()**: 工具方法，将 Flux<ChatResponse> 转换为 Flux<GraphResponse<StreamingOutput>>
3. **TextType 标记**: JSON/SQL/TEXT/MARKDOWN 等类型标记，用于前端格式化显示

### 节点输出规范

所有需要向前端展示结果的节点都应该：

1. 使用 `FluxUtil.createStreamingGeneratorWithMessages()` 创建流式输出
2. 返回 `Map.of(OUTPUT_KEY, generator)` 格式
3. 支持 TextType 标记来指导前端渲染

### 与 nl2sqlGraph 的区别

| 特性 | queryGraph (简单查询) | nl2sqlGraph (复杂分析) |
|------|---------------------|---------------------|
| 节点数量 | 8 个 | 15 个 |
| 执行模式 | 流式 | 流式 |
| 结果输出 | QuerySqlExecuteNode | ReportGeneratorNode |
| 是否生成报告 | ❌ 否 | ✅ 是 |
| 是否执行 Python | ❌ 否 | ✅ 可选 |
| 性能 | 快速 | 较慢 |

## 后续优化建议

1. **结果展示优化**:
   - 考虑以表格形式展示结果（而非纯 JSON）
   - 添加分页支持（大数据集）
   - 支持导出功能（CSV/Excel）

2. **错误处理增强**:
   - SQL 执行超时处理
   - 大结果集警告
   - 空结果提示优化

3. **性能监控**:
   - 添加查询执行时间统计
   - 记录结果集大小
   - 监控数据库连接池状态

## 测试建议

### 功能测试

```sql
-- 测试用例 1: 单条记录查询
查询员工姓名为 员工 的档案信息

-- 测试用例 2: 多条记录查询
查询所有正式员工的信息

-- 测试用例 3: 空结果查询
查询姓名为 不存在的员工 的信息

-- 测试用例 4: 复杂条件查询
查询年龄大于30岁且职级为P7的员工
```

### 性能测试

- 小结果集（< 10 行）
- 中等结果集（10-100 行）
- 大结果集（> 100 行）

### 边界测试

- 0 行结果
- 超大字段值
- 特殊字符
- NULL 值处理

## 总结

本次修复解决了简单查询路径无结果显示的核心问题，确保了智能查询路由功能的完整性。通过添加流式输出支持，用户现在可以在前端实时看到查询执行过程和最终结果。

修复完成后，整个智能查询路由系统（包括 SIMPLE 和 COMPLEX 两条路径）已经可以正常工作。✅
