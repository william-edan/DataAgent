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

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 业务友好的字段过滤工具 对查询结果进行业务友好化处理：过滤技术字段、调整字段顺序等
 */
@Slf4j
public class BusinessFriendlyFieldFilter {

	// 默认隐藏的技术字段名称模式（仅隐藏纯技术字段，保留业务字段）
	private static final Set<String> DEFAULT_HIDDEN_FIELDS = new HashSet<>(
			Arrays.asList("id", "created_at", "updated_at", "create_time", "update_time", "deleted_at", "delete_time",
					"is_deleted", "version", "tenant_id", "creator", "updater"));

	// 默认隐藏的字段名称后缀（仅隐藏外键ID，不隐藏业务时间字段）
	private static final Set<String> HIDDEN_FIELD_SUFFIXES = new HashSet<>(Arrays.asList("_id"));

	// 业务时间字段关键词（即使以_time结尾也不隐藏）
	private static final Set<String> BUSINESS_TIME_KEYWORDS = new HashSet<>(
			Arrays.asList("start", "end", "begin", "finish", "entry", "leave", "arrival", "departure",
					"开始", "结束", "入职", "离职", "到达", "出发"));

	// 业务描述字段（永不隐藏）
	private static final Set<String> BUSINESS_DESCRIPTION_FIELDS = new HashSet<>(
			Arrays.asList("remark", "remarks", "description", "desc", "note", "notes", "content", "comment", "comments",
					"备注", "描述", "说明", "内容", "详情"));

	// 解析COMMENT中的元数据标记 格式: "字段说明|hidden:true|priority:1"
	private static final Pattern METADATA_PATTERN = Pattern.compile("\\|([^:]+):([^|]+)");

	/**
	 * 字段元数据
	 */
	public static class FieldMetadata {

		public boolean hidden = false;

		public int priority = 999; // 默认优先级最低

		public boolean formatTimestamp = false; // 是否格式化时间戳

		public String description;

		public Map<String, String> enumMapping;

	}

	/**
	 * 对查询结果进行业务友好化处理
	 * @param resultSetBO 原始查询结果
	 * @param columnMetadataMap 字段元数据映射
	 * @return 处理后的查询结果
	 */
	public static ResultSetBO applyBusinessFriendlyFilter(ResultSetBO resultSetBO,
			Map<String, ColumnInfoBO> columnMetadataMap) {

		if (resultSetBO == null || resultSetBO.getColumn() == null || resultSetBO.getColumn().isEmpty()) {
			return resultSetBO;
		}

		// 1. 解析字段元数据
		Map<String, FieldMetadata> fieldMetadataMap = new HashMap<>();
		for (String columnName : resultSetBO.getColumn()) {
			ColumnInfoBO columnInfo = columnMetadataMap.get(columnName);
			FieldMetadata metadata = parseFieldMetadata(columnName, columnInfo);
			fieldMetadataMap.put(columnName, metadata);
		}

		// 2. 过滤隐藏字段
		List<String> visibleColumns = resultSetBO.getColumn()
			.stream()
			.filter(col -> !fieldMetadataMap.get(col).hidden)
			.collect(Collectors.toList());

		if (visibleColumns.isEmpty()) {
			log.warn("All fields are hidden, returning empty result");
			return ResultSetBO.builder().column(new ArrayList<>()).data(new ArrayList<>()).build();
		}

		// 3. 按优先级排序字段
		visibleColumns.sort((col1, col2) -> {
			int priority1 = fieldMetadataMap.get(col1).priority;
			int priority2 = fieldMetadataMap.get(col2).priority;
			// 优先级数字小的排前面
			return Integer.compare(priority1, priority2);
		});

		// 4. 过滤数据行，只保留可见字段，并格式化时间戳
		List<Map<String, String>> filteredData = resultSetBO.getData()
			.stream()
			.map(row -> filterAndFormatRowData(row, visibleColumns, fieldMetadataMap))
			.collect(Collectors.toList());

		log.info("Business friendly filter applied: {} fields visible (hidden: {})", visibleColumns.size(),
				resultSetBO.getColumn().size() - visibleColumns.size());

		return ResultSetBO.builder().column(visibleColumns).data(filteredData).errorMsg(resultSetBO.getErrorMsg()).build();
	}

	/**
	 * 解析字段元数据 从COMMENT中提取hidden、priority、formatTimestamp等标记
	 */
	private static FieldMetadata parseFieldMetadata(String columnName, ColumnInfoBO columnInfo) {
		FieldMetadata metadata = new FieldMetadata();

		// 默认规则：检查是否是技术字段
		metadata.hidden = isDefaultHiddenField(columnName);
		metadata.priority = 999;
		metadata.formatTimestamp = isTimestampField(columnName, columnInfo);

		if (columnInfo != null && StringUtils.isNotBlank(columnInfo.getDescription())) {
			String description = columnInfo.getDescription();

			// 解析COMMENT中的标记 例如: "员工姓名|priority:1|hidden:false|formatTimestamp:true"
			Matcher matcher = METADATA_PATTERN.matcher(description);
			while (matcher.find()) {
				String key = matcher.group(1).trim();
				String value = matcher.group(2).trim();

				switch (key.toLowerCase()) {
					case "hidden":
						metadata.hidden = Boolean.parseBoolean(value);
						break;
					case "priority":
						try {
							metadata.priority = Integer.parseInt(value);
						}
						catch (NumberFormatException e) {
							log.warn("Invalid priority value for column {}: {}", columnName, value);
						}
						break;
					case "formattimestamp":
					case "format_timestamp":
						metadata.formatTimestamp = Boolean.parseBoolean(value);
						break;
					default:
						// 忽略未知标记
						break;
				}
			}
		}

		return metadata;
	}

	/**
	 * 判断字段是否是默认隐藏的技术字段
	 */
	private static boolean isDefaultHiddenField(String columnName) {
		if (StringUtils.isBlank(columnName)) {
			return false;
		}

		String lowerName = columnName.toLowerCase();

		// 业务描述字段永不隐藏
		if (BUSINESS_DESCRIPTION_FIELDS.contains(lowerName)) {
			return false;
		}

		// 检查是否在默认隐藏列表中
		if (DEFAULT_HIDDEN_FIELDS.contains(lowerName)) {
			return true;
		}

		// 检查是否以隐藏后缀结尾
		for (String suffix : HIDDEN_FIELD_SUFFIXES) {
			if (lowerName.endsWith(suffix)) {
				// 检查是否是业务时间字段（不应该被隐藏）
				boolean isBusinessTimeField = BUSINESS_TIME_KEYWORDS.stream()
					.anyMatch(keyword -> lowerName.contains(keyword));

				if (isBusinessTimeField) {
					return false; // 业务时间字段不隐藏
				}

				return true;
			}
		}

		return false;
	}

	/**
	 * 判断字段是否是时间戳字段 根据字段名和类型判断
	 */
	private static boolean isTimestampField(String columnName, ColumnInfoBO columnInfo) {
		if (StringUtils.isBlank(columnName)) {
			return false;
		}

		String lowerName = columnName.toLowerCase();

		// 检查字段名是否包含时间相关关键词（英文或中文）
		boolean hasTimeKeyword = lowerName.contains("time") || lowerName.contains("date")
				|| lowerName.contains("时间") || lowerName.contains("日期")
				|| lowerName.contains("时刻") || lowerName.contains("时段");

		if (hasTimeKeyword) {
			// 进一步检查类型是否是数字类型（时间戳通常是BIGINT或INT）
			if (columnInfo != null && StringUtils.isNotBlank(columnInfo.getType())) {
				String type = columnInfo.getType().toUpperCase();
				return type.contains("INT") || type.contains("LONG") || type.contains("BIGINT") || type.equals("NUMBER");
			}
			return true; // 如果无法确定类型，根据字段名判断
		}

		return false;
	}

	/**
	 * 过滤并格式化行数据 只保留可见字段，并格式化时间戳
	 */
	private static Map<String, String> filterAndFormatRowData(Map<String, String> row, List<String> visibleColumns,
			Map<String, FieldMetadata> fieldMetadataMap) {
		Map<String, String> filteredRow = new LinkedHashMap<>();
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

		for (String column : visibleColumns) {
			String value = row.get(column);

			// 格式化时间戳
			FieldMetadata metadata = fieldMetadataMap.get(column);
			if (metadata != null && metadata.formatTimestamp && StringUtils.isNotBlank(value)) {
				try {
					long timestamp = Long.parseLong(value);
					// 将秒级时间戳转为Date
					Date date = new Date(timestamp * 1000L);
					value = dateFormat.format(date);
				}
				catch (NumberFormatException e) {
					// 如果转换失败，保留原值
					log.debug("Failed to parse timestamp for column {}: {}", column, value);
				}
			}

			filteredRow.put(column, value);
		}
		return filteredRow;
	}

}
