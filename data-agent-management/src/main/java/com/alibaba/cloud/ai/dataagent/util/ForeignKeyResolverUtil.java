/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 外键解析工具：将结果集中的FK ID值批量转换为可读名称。
 * <p>
 * 在enrichResultSetWithMetadata之前调用，此时列名仍为原始数据库列名。
 * </p>
 */
@Slf4j
public class ForeignKeyResolverUtil {

	/**
	 * FK列名 → 查找SQL模板（%s 占位IN子句）
	 */
	private static final Map<String, String> FK_LOOKUP_MAP = new LinkedHashMap<>();

	static {
		FK_LOOKUP_MAP.put("did", "SELECT id, title AS display_value FROM oa_department WHERE id IN (%s)");
		FK_LOOKUP_MAP.put("position_id", "SELECT id, title AS display_value FROM oa_position WHERE id IN (%s)");
		FK_LOOKUP_MAP.put("position_name", "SELECT id, title AS display_value FROM oa_basic_user WHERE id IN (%s)");
		FK_LOOKUP_MAP.put("position_rank", "SELECT id, title AS display_value FROM oa_basic_user WHERE id IN (%s)");
		FK_LOOKUP_MAP.put("pid", "SELECT id, `name` AS display_value FROM oa_admin WHERE id IN (%s)");
	}

	/**
	 * 解析结果集中的FK列，将ID值替换为可读名称
	 * @param resultSetBO 原始查询结果
	 * @param accessor 数据库访问器
	 * @param dbConfig 数据库配置
	 * @return 处理后的ResultSetBO（原地修改data中的值）
	 */
	public static ResultSetBO resolveForeignKeys(ResultSetBO resultSetBO, Accessor accessor, DbConfigBO dbConfig) {
		if (resultSetBO == null || resultSetBO.getColumn() == null || resultSetBO.getData() == null
				|| resultSetBO.getData().isEmpty()) {
			return resultSetBO;
		}

		List<String> columns = resultSetBO.getColumn();
		List<Map<String, String>> data = resultSetBO.getData();

		for (Map.Entry<String, String> fkEntry : FK_LOOKUP_MAP.entrySet()) {
			String columnName = fkEntry.getKey();
			String lookupSqlTemplate = fkEntry.getValue();

			if (!columns.contains(columnName)) {
				continue;
			}

			// 收集唯一的非空、非零 ID 值
			Set<String> uniqueIds = data.stream()
				.map(row -> row.get(columnName))
				.filter(val -> val != null && !val.isEmpty() && !"0".equals(val))
				.collect(Collectors.toSet());

			if (uniqueIds.isEmpty()) {
				continue;
			}

			try {
				String inClause = uniqueIds.stream()
					.map(id -> "'" + id.replace("'", "''") + "'")
					.collect(Collectors.joining(", "));
				String lookupSql = String.format(lookupSqlTemplate, inClause);

				DbQueryParameter param = new DbQueryParameter();
				param.setSql(lookupSql);
				param.setSchema(dbConfig.getSchema());
				ResultSetBO lookupResult = accessor.executeSqlAndReturnObject(dbConfig, param);

				// 构建 ID → 显示名称 映射
				Map<String, String> idToName = new HashMap<>();
				if (lookupResult.getData() != null) {
					for (Map<String, String> row : lookupResult.getData()) {
						String id = row.get("id");
						String displayValue = row.get("display_value");
						if (id != null && displayValue != null && !displayValue.isEmpty()) {
							idToName.put(id, displayValue);
						}
					}
				}

				// 替换结果集中的ID值
				if (!idToName.isEmpty()) {
					for (Map<String, String> row : data) {
						String idValue = row.get(columnName);
						if (idValue != null && idToName.containsKey(idValue)) {
							row.put(columnName, idToName.get(idValue));
						}
					}
					log.info("Resolved {} FK values for column: {}", idToName.size(), columnName);
				}
			}
			catch (Exception e) {
				log.warn("Failed to resolve FK for column: {}, skipping", columnName, e);
			}
		}

		return resultSetBO;
	}

}
