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
package com.alibaba.cloud.ai.dataagent.service.display;

import com.alibaba.cloud.ai.dataagent.bo.display.DisplayHint;
import com.alibaba.cloud.ai.dataagent.bo.display.FieldConfig;
import com.alibaba.cloud.ai.dataagent.bo.display.FieldGroup;
import com.alibaba.cloud.ai.dataagent.bo.display.ListModeConfig;
import com.alibaba.cloud.ai.dataagent.bo.display.NestedConfig;
import com.alibaba.cloud.ai.dataagent.bo.display.PrimaryConfig;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 展示提示服务，负责生成数据展示的提示配置。
 * <p>
 * 该服务采用"配置优先，AI 推断兜底"的策略：
 * <ol>
 * <li>首先尝试从配置文件加载预定义的展示提示</li>
 * <li>如果配置不存在，则通过 AI 推断规则自动生成展示提示</li>
 * </ol>
 *
 * @author fudawei
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisplayHintService {

	private final DisplayConfigLoader configLoader;

	/**
	 * 核心字段分组的最大字段数
	 */
	private static final int MAX_CORE_FIELDS = 6;

	/**
	 * 卡片模式显示的最大字段数
	 */
	private static final int MAX_CARD_FIELDS = 4;

	/**
	 * 副标题最大字段数
	 */
	private static final int MAX_SUBTITLE_FIELDS = 2;

	/**
	 * 生成展示提示
	 * <p>
	 * 策略：配置优先，AI 推断兜底
	 * @param tableName 表名
	 * @param resultSet 结果集
	 * @param queryIntent 查询意图（可选，用于更智能的推断）
	 * @param schema Schema 信息（可选，用于获取字段描述等元信息）
	 * @return 展示提示配置
	 */
	public DisplayHint generate(String tableName, ResultSetBO resultSet, String queryIntent, SchemaDTO schema) {
		// 1. 尝试加载配置
		DisplayHint configured = configLoader.getConfig(tableName);
		if (configured != null) {
			log.debug("Using configured display hint for table: {}", tableName);
			return configured;
		}

		// 2. AI 推断兜底
		log.debug("No configured display hint for table: {}, using AI inference", tableName);
		return inferDisplayHint(resultSet, queryIntent, schema);
	}

	/**
	 * 通过 AI 推断规则生成展示提示
	 * @param resultSet 结果集
	 * @param queryIntent 查询意图
	 * @param schema Schema 信息
	 * @return 推断生成的展示提示
	 */
	private DisplayHint inferDisplayHint(ResultSetBO resultSet, String queryIntent, SchemaDTO schema) {
		List<String> columns = resultSet.getColumn();
		if (columns == null || columns.isEmpty()) {
			log.warn("ResultSet has no columns, returning empty DisplayHint");
			return DisplayHint.builder().build();
		}

		Map<String, List<String>> fieldPatterns = configLoader.getFieldPatterns();
		Map<String, List<String>> importanceRules = configLoader.getImportanceRules();

		// 推断每个字段的配置
		Map<String, FieldConfig> fieldsConfig = new HashMap<>();
		List<String> highImportanceFields = new ArrayList<>();
		List<String> mediumImportanceFields = new ArrayList<>();
		List<String> lowImportanceFields = new ArrayList<>();

		String titleField = null;
		String avatarField = null;
		List<String> badgeFields = new ArrayList<>();

		for (String column : columns) {
			String lowerColumn = column.toLowerCase();

			// 推断格式类型
			String format = inferFormat(lowerColumn, fieldPatterns);

			// 推断重要性
			String importance = inferImportance(lowerColumn, importanceRules);

			// 提取枚举映射（从 schema 中）
			Map<String, String> enumMapping = extractEnumMapping(schema, column);
			Map<String, String> colorMapping = null;
			if (enumMapping != null && !enumMapping.isEmpty()) {
				colorMapping = generateColorMapping(enumMapping);
			}

			// 构建字段配置
			FieldConfig fieldConfig = FieldConfig.builder()
				.label(column)
				.format(format)
				.importance(importance)
				.mapping(enumMapping)
				.colorMapping(colorMapping)
				.build();

			fieldsConfig.put(column, fieldConfig);

			// 按重要性分类
			switch (importance) {
				case "high":
					highImportanceFields.add(column);
					break;
				case "low":
					lowImportanceFields.add(column);
					break;
				default:
					mediumImportanceFields.add(column);
					break;
			}

			// 推断 title 字段
			if (titleField == null && matchesPattern(lowerColumn, fieldPatterns.get("title"))) {
				titleField = column;
			}

			// 推断 avatar 字段
			if (avatarField == null && matchesPattern(lowerColumn, fieldPatterns.get("avatar"))) {
				avatarField = column;
			}

			// 推断 badge 字段
			if (matchesPattern(lowerColumn, fieldPatterns.get("badge"))) {
				badgeFields.add(column);
				// 为 badge 类型字段设置格式
				fieldConfig.setFormat("badge");
			}
		}

		// 如果没有找到 title 字段，使用第一个 high importance 字段或第一个字段
		if (titleField == null) {
			titleField = highImportanceFields.isEmpty() ? columns.get(0) : highImportanceFields.get(0);
		}

		// 构建 subtitle（前 2 个 high/medium 字段，排除 title 和 badge）
		List<String> subtitleFields = buildSubtitleFields(titleField, badgeFields, highImportanceFields,
				mediumImportanceFields);

		// 构建 primary 配置
		PrimaryConfig primary = PrimaryConfig.builder()
			.title(titleField)
			.subtitle(subtitleFields)
			.avatar(avatarField)
			.badges(badgeFields.isEmpty() ? null : badgeFields)
			.build();

		// 构建字段分组（核心信息 + 其他信息）
		List<FieldGroup> fieldGroups = buildFieldGroups(highImportanceFields, mediumImportanceFields,
				lowImportanceFields);

		// 构建列表模式配置
		ListModeConfig listMode = buildListModeConfig(titleField, highImportanceFields, mediumImportanceFields);

		return DisplayHint.builder()
			.primary(primary)
			.fieldGroups(fieldGroups)
			.fields(fieldsConfig)
			.listMode(listMode)
			.build();
	}

	/**
	 * 推断字段格式类型
	 */
	private String inferFormat(String lowerColumn, Map<String, List<String>> fieldPatterns) {
		if (matchesPattern(lowerColumn, fieldPatterns.get("date"))) {
			return "date";
		}
		if (matchesPattern(lowerColumn, fieldPatterns.get("currency"))) {
			return "currency";
		}
		if (matchesPattern(lowerColumn, fieldPatterns.get("phone"))) {
			return "phone";
		}
		if (matchesPattern(lowerColumn, fieldPatterns.get("badge"))) {
			return "badge";
		}
		return "text";
	}

	/**
	 * 推断字段重要性
	 */
	private String inferImportance(String lowerColumn, Map<String, List<String>> importanceRules) {
		List<String> highPatterns = importanceRules.get("high");
		if (highPatterns != null && matchesPattern(lowerColumn, highPatterns)) {
			return "high";
		}

		List<String> lowPatterns = importanceRules.get("low");
		if (lowPatterns != null && matchesPattern(lowerColumn, lowPatterns)) {
			return "low";
		}

		return "medium";
	}

	/**
	 * 检查字段名是否匹配给定的模式列表
	 */
	private boolean matchesPattern(String lowerColumn, List<String> patterns) {
		if (patterns == null || patterns.isEmpty()) {
			return false;
		}
		for (String pattern : patterns) {
			if (lowerColumn.contains(pattern.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 构建副标题字段列表
	 */
	private List<String> buildSubtitleFields(String titleField, List<String> badgeFields,
			List<String> highImportanceFields, List<String> mediumImportanceFields) {
		List<String> subtitleCandidates = new ArrayList<>();

		// 先添加 high importance 字段
		for (String field : highImportanceFields) {
			if (!field.equals(titleField) && !badgeFields.contains(field)) {
				subtitleCandidates.add(field);
			}
		}

		// 再添加 medium importance 字段
		for (String field : mediumImportanceFields) {
			if (!field.equals(titleField) && !badgeFields.contains(field)) {
				subtitleCandidates.add(field);
			}
		}

		// 返回前 MAX_SUBTITLE_FIELDS 个
		return subtitleCandidates.subList(0, Math.min(MAX_SUBTITLE_FIELDS, subtitleCandidates.size()));
	}

	/**
	 * 构建字段分组
	 */
	private List<FieldGroup> buildFieldGroups(List<String> highImportanceFields, List<String> mediumImportanceFields,
			List<String> lowImportanceFields) {
		List<FieldGroup> groups = new ArrayList<>();

		// 核心信息组：包含 high 和部分 medium importance 字段
		List<String> coreFields = new ArrayList<>(highImportanceFields);
		int remaining = MAX_CORE_FIELDS - coreFields.size();
		if (remaining > 0 && !mediumImportanceFields.isEmpty()) {
			coreFields.addAll(mediumImportanceFields.subList(0, Math.min(remaining, mediumImportanceFields.size())));
		}

		if (!coreFields.isEmpty()) {
			groups.add(FieldGroup.builder()
				.key("core")
				.label("核心信息")
				.icon("info-circle")
				.fields(coreFields)
				.defaultExpanded(true)
				.build());
		}

		// 其他信息组：包含剩余 medium 和 low importance 字段
		List<String> otherFields = new ArrayList<>();
		if (mediumImportanceFields.size() > remaining) {
			otherFields.addAll(mediumImportanceFields.subList(Math.max(0, remaining), mediumImportanceFields.size()));
		}
		otherFields.addAll(lowImportanceFields);

		if (!otherFields.isEmpty()) {
			groups.add(FieldGroup.builder()
				.key("other")
				.label("其他信息")
				.icon("more")
				.fields(otherFields)
				.defaultExpanded(false)
				.build());
		}

		return groups;
	}

	/**
	 * 构建列表模式配置
	 */
	private ListModeConfig buildListModeConfig(String titleField, List<String> highImportanceFields,
			List<String> mediumImportanceFields) {
		// 卡片字段：取前 MAX_CARD_FIELDS 个核心字段
		List<String> cardFields = new ArrayList<>();
		cardFields.add(titleField);

		for (String field : highImportanceFields) {
			if (!field.equals(titleField) && cardFields.size() < MAX_CARD_FIELDS) {
				cardFields.add(field);
			}
		}

		for (String field : mediumImportanceFields) {
			if (!field.equals(titleField) && cardFields.size() < MAX_CARD_FIELDS) {
				cardFields.add(field);
			}
		}

		// 可搜索字段：标题字段和其他 high importance 字段
		List<String> searchableFields = new ArrayList<>();
		searchableFields.add(titleField);
		for (String field : highImportanceFields) {
			if (!field.equals(titleField)) {
				searchableFields.add(field);
			}
		}

		// 可排序字段：所有 high importance 字段
		List<String> sortableFields = new ArrayList<>(highImportanceFields);

		return ListModeConfig.builder()
			.cardFields(cardFields)
			.searchableFields(searchableFields)
			.sortableFields(sortableFields)
			.build();
	}

	/**
	 * 从 Schema 中提取字段的枚举映射
	 * 支持通过原始列名或别名（description中的字段名）查找
	 * 优先使用 ColumnDTO 的 mapping 字段，其次从 description 解析
	 */
	private Map<String, String> extractEnumMapping(SchemaDTO schema, String columnName) {
		if (schema == null || schema.getTable() == null) {
			return null;
		}

		// 查找字段并获取映射
		for (var table : schema.getTable()) {
			if (table.getColumn() == null) continue;

			for (var column : table.getColumn()) {
				boolean matched = false;

				// 方式1：直接匹配列名
				if (columnName.equals(column.getName())) {
					matched = true;
				}

				// 方式2：匹配 description 中冒号前的别名（如"员工性别：0女,1男"）
				if (!matched && column.getDescription() != null) {
					String description = column.getDescription();
					int colonIndex = description.indexOf('：');
					if (colonIndex == -1) {
						colonIndex = description.indexOf(':');
					}
					if (colonIndex > 0) {
						String alias = description.substring(0, colonIndex).trim();
						if (columnName.equals(alias)) {
							matched = true;
						}
					}
				}

				if (matched) {
					// 优先使用 ColumnDTO 中的 mapping
					if (column.getMapping() != null && !column.getMapping().isEmpty()) {
						log.debug("Found mapping for column {} (original: {}): {}",
							columnName, column.getName(), column.getMapping());
						return column.getMapping();
					}

					// 回退：从 description 解析
					if (column.getDescription() != null) {
						String description = column.getDescription();
						int colonIndex = description.indexOf('：');
						if (colonIndex == -1) {
							colonIndex = description.indexOf(':');
						}

						if (colonIndex > 0) {
							String enumPart = description.substring(colonIndex + 1).trim();
							Map<String, String> parsed = parseEnumString(enumPart);
							if (parsed != null && !parsed.isEmpty()) {
								log.debug("Parsed mapping from description for column {}: {}", columnName, parsed);
								return parsed;
							}
						}
					}
				}
			}
		}

		return null;
	}

	/**
	 * 解析枚举字符串 "0女,1男" -> {0: "女", 1: "男"}
	 */
	private Map<String, String> parseEnumString(String enumStr) {
		Map<String, String> mapping = new HashMap<>();
		if (enumStr == null || enumStr.isEmpty()) {
			return mapping;
		}

		// 按逗号分隔
		String[] pairs = enumStr.split("[,，]");
		for (String pair : pairs) {
			// 查找第一个数字
			int digitStart = -1;
			for (int i = 0; i < pair.length(); i++) {
				if (Character.isDigit(pair.charAt(i))) {
					digitStart = i;
					break;
				}
			}

			if (digitStart >= 0) {
				// 提取数字和描述
				int digitEnd = digitStart;
				while (digitEnd < pair.length() && Character.isDigit(pair.charAt(digitEnd))) {
					digitEnd++;
				}

				String key = pair.substring(digitStart, digitEnd);
				String value = pair.substring(digitEnd).trim();

				if (!value.isEmpty()) {
					mapping.put(key, value);
				}
			}
		}

		return mapping;
	}

	/**
	 * 根据枚举值生成颜色映射（通用算法）
	 * <p>
	 * 策略：
	 * 1. 如果枚举键是数字序列，按数值大小分配颜色（从低到高：红->橙->蓝->绿）
	 * 2. 如果枚举键不是数字，按字典序分配颜色
	 * 3. 颜色池：["blue", "green", "orange", "red", "purple", "cyan", "gray"]
	 */
	private Map<String, String> generateColorMapping(Map<String, String> enumMapping) {
		if (enumMapping == null || enumMapping.isEmpty()) {
			return null;
		}

		Map<String, String> colorMapping = new HashMap<>();

		// 颜色池：用于不同枚举项
		String[] colorPool = {"blue", "green", "orange", "purple", "cyan", "red", "gray"};

		// 尝试解析枚举键为数字并排序
		List<Map.Entry<String, String>> entries = new ArrayList<>(enumMapping.entrySet());
		boolean allNumeric = entries.stream().allMatch(e -> {
			try {
				Integer.parseInt(e.getKey());
				return true;
			} catch (NumberFormatException ex) {
				return false;
			}
		});

		if (allNumeric) {
			// 按数字大小排序
			entries.sort((a, b) -> {
				try {
					return Integer.parseInt(a.getKey()) - Integer.parseInt(b.getKey());
				} catch (NumberFormatException e) {
					return 0;
				}
			});
		} else {
			// 按字典序排序
			entries.sort(Map.Entry.comparingByKey());
		}

		// 根据枚举项数量分配颜色
		int size = entries.size();
		for (int i = 0; i < size; i++) {
			String enumValue = entries.get(i).getValue();
			String color;

			if (size == 2) {
				// 2项：蓝色/绿色（或对立色）
				color = (i == 0) ? "blue" : "green";
			} else if (size == 3) {
				// 3项：蓝色/橙色/绿色（低/中/高）
				color = (i == 0) ? "blue" : (i == 1) ? "orange" : "green";
			} else if (size == 4) {
				// 4项：蓝色/橙色/紫色/绿色
				String[] colors = {"blue", "orange", "purple", "green"};
				color = colors[i];
			} else {
				// 5项及以上：循环使用颜色池
				color = colorPool[i % colorPool.length];
			}

			colorMapping.put(enumValue, color);
		}

		return colorMapping;
	}

	/**
	 * 为嵌套数据的字段生成配置
	 * @param nestedConfigs 嵌套配置
	 * @param schema Schema 信息
	 * @return 嵌套字段的配置映射
	 */
	public Map<String, FieldConfig> generateNestedFieldConfigs(Map<String, NestedConfig> nestedConfigs, SchemaDTO schema) {
		Map<String, FieldConfig> nestedFieldsConfig = new HashMap<>();

		if (nestedConfigs == null || nestedConfigs.isEmpty()) {
			return nestedFieldsConfig;
		}

		Map<String, List<String>> fieldPatterns = configLoader.getFieldPatterns();

		// 遍历所有嵌套配置
		for (NestedConfig nestedConfig : nestedConfigs.values()) {
			if (nestedConfig.getFields() == null) {
				continue;
			}

			// 为每个嵌套字段生成配置
			for (String fieldName : nestedConfig.getFields()) {
				// 如果已经配置过（可能在主表中），跳过
				if (nestedFieldsConfig.containsKey(fieldName)) {
					continue;
				}

				String lowerFieldName = fieldName.toLowerCase();

				// 推断格式类型
				String format = inferFormat(lowerFieldName, fieldPatterns);

				// 嵌套字段的重要性默认为 medium
				String importance = "medium";

				// 提取枚举映射（从 schema 中）
				Map<String, String> enumMapping = extractEnumMapping(schema, fieldName);
				Map<String, String> colorMapping = null;
				if (enumMapping != null && !enumMapping.isEmpty()) {
					colorMapping = generateColorMapping(enumMapping);
				}

				// 构建字段配置
				FieldConfig fieldConfig = FieldConfig.builder()
					.label(fieldName)
					.format(format)
					.importance(importance)
					.mapping(enumMapping)
					.colorMapping(colorMapping)
					.build();

				nestedFieldsConfig.put(fieldName, fieldConfig);

				log.debug("Generated field config for nested field {}: mapping={}, format={}",
					fieldName, enumMapping, format);
			}
		}

		return nestedFieldsConfig;
	}

}
