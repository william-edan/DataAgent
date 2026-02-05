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

import com.alibaba.cloud.ai.dataagent.bo.display.NestedConfig;
import com.alibaba.cloud.ai.dataagent.bo.schema.ForeignKeyInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.MetaInfo;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.TableDTO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 嵌套数据检测工具类
 * <p>基于逻辑外键配置检测一对多关系，并重构数据结构</p>
 */
@Slf4j
public class NestedDataDetector {

	/**
	 * 嵌套数据检测结果
	 */
	@Data
	public static class NestedDataResult {

		/**
		 * 是否是嵌套数据
		 */
		private boolean isNested;

		/**
		 * 主表数据（去重后的单条记录）
		 */
		private ResultSetBO mainTableData;

		/**
		 * 嵌套数据（按分组字段组织）
		 */
		private Map<String, List<Map<String, String>>> nestedData;

		/**
		 * 嵌套配置
		 */
		private Map<String, NestedConfig> nestedConfigs;

		/**
		 * 元信息
		 */
		private MetaInfo meta;

	}

	/**
	 * 分组字段信息
	 */
	@Data
	private static class GroupByFieldInfo {

		/**
		 * 原始列名（如：types）
		 */
		private String originalName;

		/**
		 * 显示名称（如：类型）
		 */
		private String displayName;

	}

	/**
	 * 检测并重构嵌套数据
	 *
	 * @param resultSetBO 原始查询结果
	 * @param sqlQuery SQL 查询语句
	 * @param schemaDTO Schema 信息（包含外键关系）
	 * @return 嵌套数据结果
	 */
	public static NestedDataResult detectAndRestructure(ResultSetBO resultSetBO, String sqlQuery,
			SchemaDTO schemaDTO) {

		NestedDataResult result = new NestedDataResult();
		result.setNested(false);

		// 1. 检查是否是 JOIN 查询
		if (!isJoinQuery(sqlQuery)) {
			log.debug("Not a JOIN query, skipping nested data detection");
			return result;
		}

		// 2. 检查是否有外键配置
		if (schemaDTO == null || schemaDTO.getForeignKeyDetails() == null
				|| schemaDTO.getForeignKeyDetails().isEmpty()) {
			log.debug("No foreign key details found, skipping nested data detection");
			return result;
		}

		// 3. 提取 SQL 中涉及的表名
		Set<String> tablesInQuery = extractTablesFromSchema(schemaDTO);
		if (tablesInQuery.size() < 2) {
			log.debug("Less than 2 tables in query, not a nested data scenario");
			return result;
		}

		// 4. 查找一对多关系（N:1，从子表角度）
		List<ForeignKeyInfoBO> oneToManyRelations = schemaDTO.getForeignKeyDetails()
			.stream()
			.filter(fk -> tablesInQuery.contains(fk.getTable()) && tablesInQuery.contains(fk.getReferencedTable()))
			.filter(fk -> "N:1".equals(fk.getRelationType()))
			.collect(Collectors.toList());

		if (oneToManyRelations.isEmpty()) {
			log.debug("No N:1 (one-to-many from parent view) relations found in query");
			return result;
		}

		log.info("Found {} N:1 relations in query, checking for nested data pattern", oneToManyRelations.size());

		// 5. 检测主键重复（确认是一对多场景）
		ForeignKeyInfoBO primaryRelation = oneToManyRelations.get(0); // 取第一个关系
		String mainTable = primaryRelation.getReferencedTable(); // 主表
		String childTable = primaryRelation.getTable(); // 子表

		// 查找主表的字段前缀（通过schema找到主表字段）
		Set<String> mainTableFields = getTableFields(schemaDTO, mainTable);
		Set<String> childTableFields = getTableFields(schemaDTO, childTable);

		if (mainTableFields.isEmpty() || childTableFields.isEmpty()) {
			log.warn("Cannot identify main table or child table fields");
			return result;
		}

		// 6. 检查数据中是否有主键重复
		List<Map<String, String>> data = resultSetBO.getData();
		if (data == null || data.size() <= 1) {
			log.debug("Data size <= 1, not a nested data scenario");
			return result;
		}

		// 统计主表记录的唯一值数量（只使用主键或 id 字段）
		Set<String> uniqueMainRecords = new HashSet<>();
		String primaryKeyField = findPrimaryKeyField(schemaDTO, mainTable, mainTableFields, resultSetBO);

		for (Map<String, String> row : data) {
			// 优先使用主键字段，如果没有则使用 id 字段
			String mainRecordKey = null;
			if (StringUtils.isNotBlank(primaryKeyField)) {
				mainRecordKey = row.get(primaryKeyField);
			}
			else {
				// 后备方案：查找 id 字段
				mainRecordKey = row.get("id");
			}

			if (StringUtils.isNotBlank(mainRecordKey)) {
				uniqueMainRecords.add(mainRecordKey);
			}
		}

		// 如果找不到主键字段，尝试使用主表所有字段的组合（保持原有逻辑）
		if (uniqueMainRecords.isEmpty()) {
			log.warn("No primary key field found, falling back to all main table fields for uniqueness check");
			for (Map<String, String> row : data) {
				String mainRecordKey = mainTableFields.stream()
					.filter(resultSetBO.getColumn()::contains)
					.map(row::get)
					.filter(StringUtils::isNotBlank)
					.collect(Collectors.joining("|"));

				if (StringUtils.isNotBlank(mainRecordKey)) {
					uniqueMainRecords.add(mainRecordKey);
				}
			}
		}

		// 如果主表记录唯一值数量远小于总记录数，说明是嵌套数据
		if (uniqueMainRecords.size() >= data.size() * 0.8) {
			log.debug("No significant duplication in main table records ({}  unique out of {} total)",
					uniqueMainRecords.size(), data.size());
			return result;
		}

		log.info("Detected nested data: {} unique main records out of {} total rows", uniqueMainRecords.size(),
				data.size());

		// 7. 重构数据结构
		result.setNested(true);
		result.setMainTableData(extractMainTableData(resultSetBO, mainTableFields, schemaDTO, mainTable));
		Map<String, List<Map<String, String>>> nestedData = extractNestedData(resultSetBO, mainTableFields, childTableFields, schemaDTO, childTable);
		result.setNestedData(nestedData);
		result.setNestedConfigs(buildNestedConfigs(childTable, childTableFields, resultSetBO, schemaDTO, nestedData));
		result.setMeta(buildMetaInfo(uniqueMainRecords.size(), data.size()));

		return result;
	}

	/**
	 * 检查是否是 JOIN 查询
	 */
	private static boolean isJoinQuery(String sql) {
		if (StringUtils.isBlank(sql)) {
			return false;
		}
		String lowerSql = sql.toLowerCase();
		return lowerSql.contains("join");
	}

	/**
	 * 从 Schema 中提取查询涉及的表名
	 */
	private static Set<String> extractTablesFromSchema(SchemaDTO schemaDTO) {
		if (schemaDTO.getTable() == null) {
			return Collections.emptySet();
		}
		return schemaDTO.getTable().stream().map(TableDTO::getName).collect(Collectors.toSet());
	}

	/**
	 * 获取表的所有字段名（返回原始列名，用于匹配原始数据）
	 */
	private static Set<String> getTableFields(SchemaDTO schemaDTO, String tableName) {
		if (schemaDTO.getTable() == null) {
			return Collections.emptySet();
		}

		return schemaDTO.getTable()
			.stream()
			.filter(t -> tableName.equals(t.getName()))
			.flatMap(t -> t.getColumn().stream())
			.map(col -> col.getName()) // 使用原始列名（而非中文描述）
			.filter(StringUtils::isNotBlank)
			.collect(Collectors.toSet());
	}

	/**
	 * 构建字段名映射（原始列名 -> 中文描述）
	 */
	private static Map<String, String> buildColumnNameMapping(SchemaDTO schemaDTO, String tableName) {
		if (schemaDTO == null || schemaDTO.getTable() == null) {
			return Collections.emptyMap();
		}

		return schemaDTO.getTable()
			.stream()
			.filter(t -> tableName.equals(t.getName()))
			.flatMap(t -> t.getColumn().stream())
			.filter(col -> StringUtils.isNotBlank(col.getName()))
			.collect(Collectors.toMap(col -> col.getName(),
					col -> extractFieldDisplayName(col.getDescription(), col.getName()),
					(existing, replacement) -> existing // 处理重复键
			));
	}

	/**
	 * 从字段描述中提取显示名称（去除枚举定义）
	 *
	 * @param description 字段描述（可能包含枚举定义，如："员工性别：0女,1男"）
	 * @param originalName 原始字段名（作为后备）
	 * @return 显示名称（如："员工性别"）
	 */
	private static String extractFieldDisplayName(String description, String originalName) {
		if (StringUtils.isBlank(description)) {
			return originalName;
		}

		// 如果包含冒号（枚举定义格式：字段名：枚举值...），只取冒号前的部分
		int colonIndex = description.indexOf('：'); // 中文冒号
		if (colonIndex == -1) {
			colonIndex = description.indexOf(':'); // 英文冒号
		}

		if (colonIndex > 0) {
			return description.substring(0, colonIndex).trim();
		}

		// 没有冒号，说明是普通描述，直接返回
		return description;
	}

	/**
	 * 获取字段的枚举映射（枚举值 -> 枚举描述）
	 */
	private static Map<String, String> getEnumMapping(SchemaDTO schemaDTO, String tableName, String columnName) {
		if (schemaDTO == null || schemaDTO.getTable() == null) {
			return Collections.emptyMap();
		}

		// 查找目标表
		TableDTO targetTable = schemaDTO.getTable()
			.stream()
			.filter(t -> tableName.equals(t.getName()))
			.findFirst()
			.orElse(null);

		if (targetTable == null || targetTable.getColumn() == null) {
			return Collections.emptyMap();
		}

		// 查找目标字段
		com.alibaba.cloud.ai.dataagent.dto.schema.ColumnDTO targetColumn = targetTable.getColumn()
			.stream()
			.filter(col -> columnName.equals(col.getName()))
			.findFirst()
			.orElse(null);

		if (targetColumn == null || targetColumn.getMapping() == null || targetColumn.getMapping().isEmpty()) {
			log.debug("No enum mapping found for field {}.{}", tableName, columnName);
			return Collections.emptyMap();
		}

		log.info("Found enum mapping for {}.{}: {}", tableName, columnName, targetColumn.getMapping());
		return targetColumn.getMapping();
	}

	/**
	 * 提取主表数据（去重后的单条记录）
	 */
	private static ResultSetBO extractMainTableData(ResultSetBO resultSetBO, Set<String> mainTableFields,
			SchemaDTO schemaDTO, String mainTableName) {
		List<String> mainColumns = resultSetBO.getColumn()
			.stream()
			.filter(mainTableFields::contains)
			.collect(Collectors.toList());

		if (resultSetBO.getData() == null || resultSetBO.getData().isEmpty()) {
			return ResultSetBO.builder().column(mainColumns).data(new ArrayList<>()).build();
		}

		// 构建字段名映射（原始列名 -> 中文描述）
		Map<String, String> columnNameMapping = buildColumnNameMapping(schemaDTO, mainTableName);

		// 取第一条记录，只保留主表字段，并映射为中文名
		Map<String, String> firstRow = resultSetBO.getData().get(0);
		Map<String, String> mainRow = new LinkedHashMap<>();
		List<String> mappedColumns = new ArrayList<>();

		for (String col : mainColumns) {
			String displayName = columnNameMapping.getOrDefault(col, col);
			String value = firstRow.get(col);
			// 清理字段值：去除制表符和多余空白
			if (value != null) {
				value = value.replaceAll("[\t\r\n]", "").trim();
			}
			mainRow.put(displayName, value);
			mappedColumns.add(displayName);
		}

		return ResultSetBO.builder().column(mappedColumns).data(Collections.singletonList(mainRow)).build();
	}

	/**
	 * 提取嵌套数据（子表数据，按分组字段组织）
	 */
	private static Map<String, List<Map<String, String>>> extractNestedData(ResultSetBO resultSetBO,
			Set<String> mainTableFields, Set<String> childTableFields, SchemaDTO schemaDTO, String childTableName) {

		// 过滤出子表字段（原始列名）
		List<String> childColumns = resultSetBO.getColumn()
			.stream()
			.filter(childTableFields::contains)
			.collect(Collectors.toList());

		// 构建字段名映射（原始列名 -> 中文描述）
		Map<String, String> columnNameMapping = buildColumnNameMapping(schemaDTO, childTableName);

		// 从 SchemaDTO 中查找分组字段（原始列名）
		GroupByFieldInfo groupByInfo = findGroupByFieldFromSchema(schemaDTO, childTableName, resultSetBO);

		// 获取分组字段的枚举映射（枚举值 -> 枚举描述）
		Map<String, String> enumMapping = (groupByInfo != null)
				? getEnumMapping(schemaDTO, childTableName, groupByInfo.getOriginalName())
				: Collections.emptyMap();

		Map<String, List<Map<String, String>>> nestedData = new HashMap<>();

		if (groupByInfo != null && StringUtils.isNotBlank(groupByInfo.getOriginalName())) {
			log.info("Found groupBy field: {} (display: {}), enum mapping: {}", groupByInfo.getOriginalName(),
					groupByInfo.getDisplayName(), enumMapping);

			// 确保分组字段在子表字段列表中
			if (!childColumns.contains(groupByInfo.getOriginalName())) {
				childColumns.add(0, groupByInfo.getOriginalName());
			}

			// 按分组字段分组
			for (Map<String, String> row : resultSetBO.getData()) {
				// 使用原始列名获取分组值
				String groupValue = row.get(groupByInfo.getOriginalName());
				if (StringUtils.isBlank(groupValue)) {
					groupValue = "其他";
				}
				else {
					// 如果有枚举映射，转换枚举值为描述
					groupValue = enumMapping.getOrDefault(groupValue, groupValue);
				}

				// 提取子表数据并映射为中文字段名
				Map<String, String> childRow = new LinkedHashMap<>();
				for (String col : childColumns) {
					String displayName = columnNameMapping.getOrDefault(col, col);
					String value = row.get(col);
					// 清理字段值：去除制表符和多余空白
					if (value != null) {
						value = value.replaceAll("[\t\r\n]", "").trim();
					}
					childRow.put(displayName, value);
				}

				nestedData.computeIfAbsent(groupValue, k -> new ArrayList<>()).add(childRow);
			}
		}
		else {
			log.warn("No groupBy field found in schema for table: {}, using default group", childTableName);
			// 没有分组字段，全部放入 "明细" 分组
			List<Map<String, String>> allRecords = new ArrayList<>();
			for (Map<String, String> row : resultSetBO.getData()) {
				Map<String, String> childRow = new LinkedHashMap<>();
				for (String col : childColumns) {
					String displayName = columnNameMapping.getOrDefault(col, col);
					String value = row.get(col);
					// 清理字段值：去除制表符和多余空白
					if (value != null) {
						value = value.replaceAll("[\t\r\n]", "").trim();
					}
					childRow.put(displayName, value);
				}
				allRecords.add(childRow);
			}
			nestedData.put("明细", allRecords);
		}

		log.info("Extracted nested data: {} groups, total {} records", nestedData.size(),
				nestedData.values().stream().mapToInt(List::size).sum());

		return nestedData;
	}

	/**
	 * 查找分组字段（优先级：类型 > type > 第一个枚举字段）
	 */
	private static String findGroupByField(List<String> columns) {
		// 优先查找 "类型"
		for (String col : columns) {
			if (col.contains("类型") || col.equalsIgnoreCase("type")) {
				return col;
			}
		}

		// 如果没有找到，返回 null
		return null;
	}

	/**
	 * 从 Schema 和数据中智能查找分组字段（基于数据特征分析）
	 */
	private static GroupByFieldInfo findGroupByFieldFromSchema(SchemaDTO schemaDTO, String tableName,
			ResultSetBO resultSetBO) {
		if (schemaDTO == null || schemaDTO.getTable() == null || resultSetBO == null
				|| resultSetBO.getData() == null || resultSetBO.getData().isEmpty()) {
			return null;
		}

		// 查找目标表的定义
		TableDTO targetTable = schemaDTO.getTable()
			.stream()
			.filter(t -> tableName.equals(t.getName()))
			.findFirst()
			.orElse(null);

		if (targetTable == null || targetTable.getColumn() == null) {
			log.warn("Table {} not found in schema", tableName);
			return null;
		}

		// 收集子表的所有字段（中文名）
		Set<String> childTableFields = targetTable.getColumn()
			.stream()
			.map(col -> col.getDescription())
			.filter(StringUtils::isNotBlank)
			.collect(Collectors.toSet());

		// 候选字段评分列表
		List<GroupByFieldCandidate> candidates = new ArrayList<>();

		// 分析每个子表字段
		for (com.alibaba.cloud.ai.dataagent.dto.schema.ColumnDTO col : targetTable.getColumn()) {
			String originalName = col.getName();
			String displayName = extractFieldDisplayName(col.getDescription(), originalName);

			// 只分析结果集中存在的字段（使用原始列名检查）
			if (!resultSetBO.getColumn().contains(originalName)) {
				log.debug("Skipping field {} (not in result set columns)", originalName);
				continue;
			}

			// 计算该字段的分组适合度得分
			int score = calculateGroupByScore(originalName, displayName, resultSetBO, col.getType());

			if (score > 0) {
				GroupByFieldCandidate candidate = new GroupByFieldCandidate();
				candidate.setOriginalName(originalName);
				candidate.setDisplayName(displayName);
				candidate.setScore(score);
				candidates.add(candidate);

				log.debug("GroupBy candidate: {} ({}), score: {}", displayName, originalName, score);
			}
		}

		// 按得分排序，选择最高分的字段
		if (!candidates.isEmpty()) {
			candidates.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
			GroupByFieldCandidate best = candidates.get(0);

			GroupByFieldInfo info = new GroupByFieldInfo();
			info.setOriginalName(best.getOriginalName());
			info.setDisplayName(best.getDisplayName());

			log.info("Selected groupBy field: {} ({}), score: {}", best.getDisplayName(), best.getOriginalName(),
					best.getScore());
			return info;
		}

		log.debug("No suitable groupBy field found for table: {}", tableName);
		return null;
	}

	/**
	 * 计算字段作为分组字段的适合度得分（0-100）
	 */
	private static int calculateGroupByScore(String originalName, String displayName, ResultSetBO resultSetBO,
			String fieldType) {
		int score = 0;

		// 1. 分析字段的基数（唯一值数量）
		Set<String> uniqueValues = new HashSet<>();
		int nonNullCount = 0;

		// 使用原始列名从数据中读取（因为 resultSetBO 是原始数据）
		for (Map<String, String> row : resultSetBO.getData()) {
			String value = row.get(originalName);
			if (StringUtils.isNotBlank(value)) {
				uniqueValues.add(value);
				nonNullCount++;
			}
		}

		int totalCount = resultSetBO.getData().size();
		int cardinality = uniqueValues.size();

		// 如果唯一值数量接近或等于记录总数，说明是唯一性字段（如描述、备注），不适合分组
		if (cardinality >= totalCount * 0.8) {
			log.debug("Field {} has too high cardinality ({}/{}), likely a unique field", displayName, cardinality,
					totalCount);
			return 0; // 唯一性字段，不适合分组
		}

		// 基数评分：2-10 个唯一值最理想
		if (cardinality >= 2 && cardinality <= 10) {
			score += 50; // 最高分
		}
		else if (cardinality > 10 && cardinality <= 20) {
			score += 30; // 次优
		}
		else if (cardinality > 20 && cardinality <= totalCount / 2) {
			score += 10; // 可接受
		}
		else {
			return 0; // 太少或太多，不适合分组
		}

		// 2. 非空率评分：至少 80% 的记录有值
		double nonNullRate = (double) nonNullCount / totalCount;
		if (nonNullRate >= 0.8) {
			score += 20;
		}
		else if (nonNullRate >= 0.5) {
			score += 10;
		}
		else {
			return 0; // 空值太多，不适合分组
		}

		// 3. 字段名关键字加分
		String lowerOriginal = originalName.toLowerCase();
		String lowerDisplay = displayName.toLowerCase();

		if (lowerOriginal.contains("type") || lowerDisplay.contains("类型")) {
			score += 30;
		}
		else if (lowerOriginal.contains("category") || lowerDisplay.contains("分类")) {
			score += 25;
		}
		else if (lowerOriginal.contains("kind") || lowerDisplay.contains("种类")) {
			score += 20;
		}
		else if (lowerOriginal.contains("status") || lowerDisplay.contains("状态")) {
			score += 15;
		}

		// 4. 字段类型加分
		if (fieldType != null) {
			String lowerType = fieldType.toLowerCase();
			if (lowerType.contains("enum") || lowerType.contains("varchar") || lowerType.contains("char")) {
				score += 10;
			}
		}

		return score;
	}

	/**
	 * 分组字段候选项
	 */
	@Data
	private static class GroupByFieldCandidate {

		private String originalName;

		private String displayName;

		private int score;

	}

	/**
	 * 构建嵌套配置
	 */
	private static Map<String, NestedConfig> buildNestedConfigs(String childTableName, Set<String> childTableFields,
			ResultSetBO resultSetBO, SchemaDTO schemaDTO, Map<String, List<Map<String, String>>> nestedData) {

		Map<String, NestedConfig> configs = new HashMap<>();

		// 构建字段名映射（原始列名 -> 中文描述）
		Map<String, String> columnNameMapping = buildColumnNameMapping(schemaDTO, childTableName);

		// 从 Schema 中查找分组字段
		GroupByFieldInfo groupByInfo = findGroupByFieldFromSchema(schemaDTO, childTableName, resultSetBO);
		String groupByFieldOriginal = (groupByInfo != null) ? groupByInfo.getOriginalName() : null;
		String groupByFieldDisplay = (groupByInfo != null) ? groupByInfo.getDisplayName() : null;

		// 构建字段列表（原始列名过滤，映射为中文字段名）
		List<String> fieldList = resultSetBO.getColumn()
			.stream()
			.filter(childTableFields::contains)
			.collect(Collectors.toList());

		// 确保分组字段在字段列表中
		if (groupByFieldOriginal != null && !fieldList.contains(groupByFieldOriginal)) {
			log.info("Adding groupBy field '{}' to nested config fields", groupByFieldOriginal);
			fieldList = new ArrayList<>(fieldList);
			fieldList.add(0, groupByFieldOriginal);
		}

		// 映射为中文字段名
		List<String> mappedFields = fieldList.stream()
			.map(col -> columnNameMapping.getOrDefault(col, col))
			.collect(Collectors.toList());

		// 为每个分组键生成独立的配置
		for (String groupKey : nestedData.keySet()) {
			String displayMode = inferDisplayMode(groupKey);
			String icon = inferIcon(groupKey);

			NestedConfig config = NestedConfig.builder()
				.label(groupKey)
				.displayMode(displayMode)
				.icon(icon)
				.groupBy(groupByFieldDisplay)  // 设置分组字段（映射后的显示名）
				.fields(mappedFields)
				.build();

			configs.put(groupKey, config);

			log.info("Built nested config for group '{}': displayMode={}, icon={}, fields={}",
					groupKey, displayMode, icon, mappedFields);
		}

		return configs;
	}

	/**
	 * 根据分组名推断展示模式
	 */
	private static String inferDisplayMode(String groupKey) {
		if (groupKey.contains("教育") || groupKey.contains("工作") || groupKey.contains("经历")) {
			return "timeline";
		}
		if (groupKey.contains("证书") || groupKey.contains("技能") || groupKey.contains("语言")) {
			return "cards";
		}
		return "list";
	}

	/**
	 * 根据分组名推断图标
	 */
	private static String inferIcon(String groupKey) {
		if (groupKey.contains("教育")) {
			return "graduation-cap";
		}
		if (groupKey.contains("工作")) {
			return "briefcase";
		}
		if (groupKey.contains("证书")) {
			return "certificate";
		}
		if (groupKey.contains("技能")) {
			return "code";
		}
		if (groupKey.contains("语言")) {
			return "language";
		}
		return "list";
	}

	/**
	 * 获取表的显示名称
	 */
	private static String getTableDisplayName(String tableName) {
		if (tableName.contains("profile")) {
			return "档案信息";
		}
		if (tableName.contains("education")) {
			return "教育经历";
		}
		if (tableName.contains("work")) {
			return "工作经历";
		}
		if (tableName.contains("certificate")) {
			return "证书信息";
		}
		return "详细信息";
	}

	/**
	 * 构建元信息
	 */
	private static MetaInfo buildMetaInfo(int mainRecordCount, int totalRecordCount) {
		Map<String, Integer> nestedCounts = new HashMap<>();
		nestedCounts.put("total", totalRecordCount - mainRecordCount);

		return MetaInfo.builder()
			.recordType("single")
			.hasNestedData(true)
			.totalCount(mainRecordCount)
			.nestedCounts(nestedCounts)
			.build();
	}

	/**
	 * 查找主表的主键字段
	 *
	 * @param schemaDTO Schema 信息
	 * @param mainTableName 主表名称
	 * @param mainTableFields 主表字段集合
	 * @param resultSetBO 查询结果集
	 * @return 主键字段名（原始列名），如果找不到则返回 null
	 */
	private static String findPrimaryKeyField(SchemaDTO schemaDTO, String mainTableName, Set<String> mainTableFields,
			ResultSetBO resultSetBO) {

		// 1. 从 Schema 中查找主表的主键字段
		if (schemaDTO != null && schemaDTO.getTable() != null) {
			for (TableDTO table : schemaDTO.getTable()) {
				if (mainTableName.equals(table.getName())) {
					List<String> primaryKeys = table.getPrimaryKeys();
					if (primaryKeys != null && !primaryKeys.isEmpty()) {
						// 返回第一个主键字段（如果有多个主键，只用第一个）
						String pkField = primaryKeys.get(0);
						// 确保主键字段存在于结果集中
						if (resultSetBO.getColumn().contains(pkField)) {
							log.info("Found primary key field '{}' for table '{}'", pkField, mainTableName);
							return pkField;
						}
					}
					break;
				}
			}
		}

		// 2. 如果没有找到主键，尝试查找 "id" 字段
		for (String field : mainTableFields) {
			if ("id".equalsIgnoreCase(field) && resultSetBO.getColumn().contains(field)) {
				log.info("Using 'id' field as primary key for table '{}'", mainTableName);
				return field;
			}
		}

		// 3. 如果还是找不到，尝试查找以 "_id" 结尾的字段
		for (String field : mainTableFields) {
			if (field.toLowerCase().endsWith("_id") && resultSetBO.getColumn().contains(field)) {
				log.info("Using '{}' field as primary key for table '{}'", field, mainTableName);
				return field;
			}
		}

		log.warn("No primary key field found for table '{}', will use all fields for uniqueness check", mainTableName);
		return null;
	}

}
