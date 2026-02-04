# 业务友好字段过滤使用指南

## 功能概述

业务友好字段过滤器会自动优化查询结果，让展示的数据更符合业务人员的需求：

1. **自动隐藏技术字段** - 隐藏 `id`, `created_at` 等技术字段
2. **字段优先级排序** - 重要字段排在前面
3. **时间戳格式化** - 将 `1768838400` 转换为 `2026-01-19 00:00:00`
4. **枚举值转换** - 将 `1` 转换为 `男`

## 使用方法

### 方法1：自动规则（零配置）

系统会自动：
- 隐藏以下字段：`id`, `created_at`, `updated_at`, `deleted_at`, `version`, `tenant_id` 等
- 隐藏以 `_id`, `_time`, `_at` 结尾的字段
- 格式化包含 `time` 或 `date` 的数字类型字段为时间格式

**示例**：
```sql
-- 原始字段
id, name, did, created_at, entry_time

-- 自动过滤后
name, entry_time (已格式化为 2026-01-19 00:00:00)
```

### 方法2：COMMENT配置（推荐）

在数据库COMMENT中添加标记来控制字段行为：

```sql
-- 1. 显示隐藏控制
ALTER TABLE oa_admin MODIFY COLUMN id INT COMMENT '员工ID|hidden:true';
ALTER TABLE oa_admin MODIFY COLUMN name VARCHAR(50) COMMENT '员工姓名|hidden:false';

-- 2. 优先级排序（数字越小优先级越高）
ALTER TABLE oa_admin MODIFY COLUMN name VARCHAR(50) COMMENT '员工姓名|priority:1';
ALTER TABLE oa_admin MODIFY COLUMN mobile VARCHAR(20) COMMENT '手机号码|priority:2';
ALTER TABLE oa_admin MODIFY COLUMN email VARCHAR(100) COMMENT '电子邮箱|priority:3';

-- 3. 时间戳格式化
ALTER TABLE oa_admin MODIFY COLUMN entry_time INT COMMENT '员工入职日期的时间戳|formatTimestamp:true';

-- 4. 组合使用
ALTER TABLE oa_admin MODIFY COLUMN name VARCHAR(50) COMMENT '员工姓名|priority:1|hidden:false';
```

## 配置语法

### 标记格式

```
字段说明|标记1:值1|标记2:值2
```

### 支持的标记

| 标记 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `hidden` | boolean | 是否隐藏字段 | `hidden:true` |
| `priority` | int | 显示优先级（越小越靠前） | `priority:1` |
| `formatTimestamp` | boolean | 是否格式化时间戳 | `formatTimestamp:true` |

## 实际案例

### 员工档案表优化

**优化前**（25个字段）:
```
id, name, mobile, email, sex, age, idcard, did, position_id, position_rank,
type, is_staff, status, entry_time, created_at, updated_at, creator, updater,
profile_id, types, title, major, degree, position, organization, ...
```

**优化后**（8个核心字段）:
```
员工姓名, 手机号码, 员工性别, 年龄, 员工类型, 身份类型, 状态, 入职日期
```

**配置SQL**:
```sql
-- 核心字段设置优先级
ALTER TABLE oa_admin MODIFY COLUMN name VARCHAR(50) COMMENT '员工姓名|priority:1';
ALTER TABLE oa_admin MODIFY COLUMN mobile VARCHAR(20) COMMENT '手机号码|priority:2';
ALTER TABLE oa_admin MODIFY COLUMN sex TINYINT COMMENT '员工性别：0女,1男|priority:3';
ALTER TABLE oa_admin MODIFY COLUMN age INT COMMENT '年龄|priority:4';
ALTER TABLE oa_admin MODIFY COLUMN type TINYINT COMMENT '员工类型：0未设置,1正式员工,2试用员工,3实习生|priority:5';
ALTER TABLE oa_admin MODIFY COLUMN is_staff TINYINT COMMENT '身份类型：1企业员工,2劳务派遣,3兼职员工|priority:6';
ALTER TABLE oa_admin MODIFY COLUMN status TINYINT COMMENT '状态：-1待入职,0禁止登录,1正常,2离职|priority:7';
ALTER TABLE oa_admin MODIFY COLUMN entry_time INT COMMENT '员工入职日期的时间戳|priority:8|formatTimestamp:true';

-- 技术字段隐藏（会被自动隐藏，无需配置）
-- id, did, position_id, position_rank, created_at, updated_at, creator, updater

-- 或者强制显示某个技术字段
ALTER TABLE oa_admin MODIFY COLUMN did INT COMMENT '记录员工所属的主部门的id|hidden:false|priority:99';
```

## 开关控制

在代码中可以控制是否启用业务友好过滤：

```java
// 启用业务友好过滤（默认）
ResultSetBO result = ResultSetEnricherUtil.enrichResultSet(
    resultSetBO, columnMetadataMap, columnToTableMap, true
);

// 禁用业务友好过滤
ResultSetBO result = ResultSetEnricherUtil.enrichResultSet(
    resultSetBO, columnMetadataMap, columnToTableMap, false
);
```

## 默认隐藏规则

以下字段名称会被自动隐藏：

### 精确匹配
- `id`
- `created_at`, `create_time`
- `updated_at`, `update_time`
- `deleted_at`, `delete_time`
- `is_deleted`
- `version`
- `tenant_id`
- `creator`, `updater`
- `remark`

### 后缀匹配
- 以 `_id` 结尾（如 `user_id`, `dept_id`）
- 以 `_time` 结尾（如 `login_time`）
- 以 `_at` 结尾（如 `verified_at`）

## 时间戳格式化

### 自动识别
字段名包含 `time` 或 `date` 且类型为 `INT/BIGINT/LONG` 会自动格式化

### 格式化输出
- **输入**: `1768838400` (秒级时间戳)
- **输出**: `2026-01-19 00:00:00`

### 手动控制
```sql
-- 禁用自动格式化
ALTER TABLE oa_admin MODIFY COLUMN some_time INT COMMENT '某个时间|formatTimestamp:false';

-- 启用格式化
ALTER TABLE oa_admin MODIFY COLUMN some_number BIGINT COMMENT '某个数字|formatTimestamp:true';
```

## 最佳实践

1. **重要字段设置优先级 1-10**
   ```sql
   员工姓名|priority:1
   手机号码|priority:2
   ```

2. **次要字段设置优先级 11-50**
   ```sql
   备注|priority:50
   ```

3. **技术字段让系统自动隐藏**
   - 不需要手动配置 `hidden:true`

4. **业务相关的ID字段强制显示**
   ```sql
   工号|hidden:false|priority:2
   ```

5. **时间戳字段明确标记**
   ```sql
   入职时间|formatTimestamp:true|priority:10
   ```

## 常见问题

### Q: 所有字段都被隐藏了？
A: 检查字段名是否都以 `_id` 结尾。可以用 `hidden:false` 强制显示。

### Q: 时间戳没有格式化？
A: 检查字段类型是否是数字类型（INT/BIGINT）。如果是字符串类型，需要手动添加 `formatTimestamp:true`。

### Q: 字段顺序不对？
A: 使用 `priority` 标记调整顺序，数字越小越靠前。

### Q: 如何临时关闭过滤？
A: 在代码中调用时传入 `false` 参数：
```java
enrichResultSet(resultSetBO, columnMetadataMap, columnToTableMap, false)
```
