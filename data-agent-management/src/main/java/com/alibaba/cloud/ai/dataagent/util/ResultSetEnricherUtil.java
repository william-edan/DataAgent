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

import com.alibaba.cloud.ai.dataagent.bo.schema.ColumnInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 工具类:为查询结果添加字段说明和枚举值转换 从数据库COMMENT中解析字段说明和枚举映射
 */
@Slf4j
public class ResultSetEnricherUtil {

	// 匹配枚举值的正则表达式
	// 支持格式: "状态:-1待入职,0禁止登录,1正常,2离职" 或 "类型:1教育经历/2工作经历/3相关证书"
	private static final Pattern ENUM_PATTERN = Pattern.compile("([-\\d]+)([^,/\\d]+)");

	/**
	 * 从ResultSetMetaData中提取表名和字段名映射
	 * @param metaData ResultSet元数据
	 * @return 字段名到表名的映射
	 */
	public static Map<String, String> extractTableMapping(ResultSetMetaData metaData) throws Exception {
		Map<String, String> columnToTable = new HashMap<>();
		int columnCount = metaData.getColumnCount();

		for (int i = 1; i <= columnCount; i++) {
			String columnLabel = metaData.getColumnLabel(i);
			String tableName = metaData.getTableName(i);

			// 清理列名和表名
			columnLabel = cleanName(columnLabel);
			tableName = cleanName(tableName);

			if (StringUtils.isNotBlank(tableName)) {
				columnToTable.put(columnLabel, tableName);
			}
		}

		return columnToTable;
	}

	/**
	 * 从字段description中解析枚举值映射 支持格式: "状态:-1待入职,0禁止登录,1正常,2离职" 或 "状态：-1待入职,0禁止登录,1正常,2离职"
	 * @param description 字段描述(COMMENT)
	 * @return 值到文本的映射
	 */
	public static Map<String, String> parseEnumMapping(String description) {
		Map<String, String> enumMap = new HashMap<>();

		if (StringUtils.isBlank(description)) {
			return enumMap;
		}

		// 检查是否包含冒号(支持中英文冒号)
		int colonIndex = findColonIndex(description);
		if (colonIndex == -1) {
			return enumMap;
		}

		// 提取冒号后面的枚举值部分
		String enumPart = description.substring(colonIndex + 1);

		// 使用正则匹配枚举值
		Matcher matcher = ENUM_PATTERN.matcher(enumPart);
		while (matcher.find()) {
			String value = matcher.group(1).trim(); // 数字值
			String text = matcher.group(2).trim(); // 文本说明
			enumMap.put(value, text);
		}

		return enumMap;
	}

	/**
	 * 从字段description中提取字段说明(去除枚举部分) 例如: "状态:-1待入职,0禁止登录,1正常,2离职" -> "状态" 或
	 * "状态：-1待入职,0禁止登录,1正常,2离职" -> "状态"
	 * @param description 字段描述
	 * @return 纯字段说明
	 */
	public static String extractFieldDescription(String description) {
		if (StringUtils.isBlank(description)) {
			return "";
		}

		int colonIndex = findColonIndex(description);
		if (colonIndex > 0) {
			return description.substring(0, colonIndex).trim();
		}

		return description.trim();
	}

	/**
	 * 查找冒号位置(支持中英文冒号)
	 * @param text 文本
	 * @return 冒号位置,找不到返回-1
	 */
	private static int findColonIndex(String text) {
		int englishColon = text.indexOf(':');
		int chineseColon = text.indexOf('：');

		if (englishColon == -1 && chineseColon == -1) {
			return -1;
		}
		if (englishColon == -1) {
			return chineseColon;
		}
		if (chineseColon == -1) {
			return englishColon;
		}
		// 两者都存在,返回较小的位置
		return Math.min(englishColon, chineseColon);
	}

	/**
	 * 将ResultSetBO中的字段名替换为字段说明,并转换枚举值
	 * @param resultSetBO 原始查询结果
	 * @param columnMetadataMap 字段元数据映射 (columnName -> ColumnInfoBO)
	 * @param columnToTableMap 字段到表的映射
	 * @return 处理后的ResultSetBO
	 */
	public static ResultSetBO enrichResultSet(ResultSetBO resultSetBO, Map<String, ColumnInfoBO> columnMetadataMap,
			Map<String, String> columnToTableMap) {

		return enrichResultSet(resultSetBO, columnMetadataMap, columnToTableMap, true);
	}

	/**
	 * 将ResultSetBO中的字段名替换为字段说明,并转换枚举值
	 * @param resultSetBO 原始查询结果
	 * @param columnMetadataMap 字段元数据映射 (columnName -> ColumnInfoBO)
	 * @param columnToTableMap 字段到表的映射
	 * @param applyBusinessFilter 是否应用业务友好过滤
	 * @return 处理后的ResultSetBO
	 */
	public static ResultSetBO enrichResultSet(ResultSetBO resultSetBO, Map<String, ColumnInfoBO> columnMetadataMap,
			Map<String, String> columnToTableMap, boolean applyBusinessFilter) {

		if (resultSetBO == null || resultSetBO.getColumn() == null || resultSetBO.getColumn().isEmpty()) {
			return resultSetBO;
		}

		// 第一步：业务友好过滤（可选）
		ResultSetBO filteredResultSet = resultSetBO;
		if (applyBusinessFilter) {
			filteredResultSet = BusinessFriendlyFieldFilter.applyBusinessFriendlyFilter(resultSetBO,
					columnMetadataMap);
			if (filteredResultSet.getColumn().isEmpty()) {
				return filteredResultSet;
			}
		}

		// 第二步：字段说明和枚举值转换
		// 1. 构建字段名到枚举映射的缓存
		Map<String, Map<String, String>> columnEnumMappings = new HashMap<>();
		Map<String, String> columnDescriptions = new HashMap<>();

		for (String columnName : filteredResultSet.getColumn()) {
			ColumnInfoBO metadata = getColumnMetadata(columnName, columnMetadataMap, columnToTableMap);
			if (metadata != null && StringUtils.isNotBlank(metadata.getDescription())) {
				// 解析枚举映射
				Map<String, String> enumMap = parseEnumMapping(metadata.getDescription());
				if (!enumMap.isEmpty()) {
					columnEnumMappings.put(columnName, enumMap);
				}

				// 提取字段说明
				String fieldDesc = extractFieldDescription(metadata.getDescription());
				if (StringUtils.isNotBlank(fieldDesc)) {
					columnDescriptions.put(columnName, fieldDesc);
				}
			}
		}

		// 2. 替换列名为字段说明
		List<String> enrichedColumns = filteredResultSet.getColumn()
			.stream()
			.map(columnName -> columnDescriptions.getOrDefault(columnName, columnName))
			.collect(Collectors.toList());

		// 3. 转换数据中的枚举值,并更新键名
		List<Map<String, String>> enrichedData = new ArrayList<>();
		for (Map<String, String> row : filteredResultSet.getData()) {
			Map<String, String> newRow = new LinkedHashMap<>();

			for (int i = 0; i < filteredResultSet.getColumn().size(); i++) {
				String oldColumnName = filteredResultSet.getColumn().get(i);
				String newColumnName = enrichedColumns.get(i);
				String value = row.get(oldColumnName);

				// 尝试转换枚举值
				Map<String, String> enumMap = columnEnumMappings.get(oldColumnName);
				if (enumMap != null && enumMap.containsKey(value)) {
					value = enumMap.get(value);
				}

				newRow.put(newColumnName, value);
			}

			enrichedData.add(newRow);
		}

		return ResultSetBO.builder()
			.column(enrichedColumns)
			.data(enrichedData)
			.errorMsg(filteredResultSet.getErrorMsg())
			.build();
	}

	/**
	 * 获取字段元数据,优先使用 "tableName.columnName" 查找
	 */
	private static ColumnInfoBO getColumnMetadata(String columnName, Map<String, ColumnInfoBO> columnMetadataMap,
			Map<String, String> columnToTableMap) {

		// 尝试使用 "tableName.columnName" 查找
		String tableName = columnToTableMap.get(columnName);
		if (StringUtils.isNotBlank(tableName)) {
			String key = tableName + "." + columnName;
			ColumnInfoBO metadata = columnMetadataMap.get(key);
			if (metadata != null) {
				return metadata;
			}
		}

		// 尝试只使用 columnName 查找
		return columnMetadataMap.get(columnName);
	}

	/**
	 * 清理名称中的特殊字符
	 */
	private static String cleanName(String name) {
		if (name == null) {
			return "";
		}
		return StringUtils.remove(StringUtils.remove(name, "`"), "\"").trim();
	}

}
