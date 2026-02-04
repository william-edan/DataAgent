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
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.display.DisplayHint;
import com.alibaba.cloud.ai.dataagent.bo.schema.ColumnInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.MetaInfo;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.StructuredResultBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.dto.schema.ColumnDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.TableDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.service.display.DisplayHintService;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.ResultSetEnricherUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_SQL_RESULT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;

/**
 * Query-only SQL execution node that runs SQL synchronously and returns result set.
 */
@Slf4j
@Component
@AllArgsConstructor
public class QuerySqlExecuteNode implements NodeAction {

	private final DatabaseUtil databaseUtil;

	private final Nl2SqlService nl2SqlService;

	private final DisplayHintService displayHintService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String sqlQuery = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
		sqlQuery = nl2SqlService.sqlTrim(sqlQuery);

		String agentIdStr = StateUtil.getStringValue(state, AGENT_ID);
		if (StringUtils.isBlank(agentIdStr)) {
			throw new IllegalStateException("Agent ID cannot be empty.");
		}
		Long agentId = Long.valueOf(agentIdStr);
		DbConfigBO dbConfig = databaseUtil.getAgentDbConfig(agentId);

		DbQueryParameter dbQueryParameter = new DbQueryParameter();
		dbQueryParameter.setSql(sqlQuery);
		dbQueryParameter.setSchema(dbConfig.getSchema());

		Accessor dbAccessor = databaseUtil.getAgentAccessor(agentId);
		ResultSetBO resultSetBO = dbAccessor.executeSqlAndReturnObject(dbConfig, dbQueryParameter);

		int rowCount = resultSetBO.getData() != null ? resultSetBO.getData().size() : 0;
		log.info("Query SQL executed successfully, rows: {}", rowCount);

		// 增强查询结果:添加字段说明和枚举值转换 (仅简单查询路径)
		try {
			resultSetBO = enrichResultSetWithMetadata(resultSetBO, state);
		}
		catch (Exception e) {
			log.warn("Failed to enrich result set with metadata, returning original result", e);
		}

		// 生成展示提示
		DisplayHint displayHint = null;
		SchemaDTO schemaDTO = null;
		try {
			schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
			String tableName = null;
			if (schemaDTO != null && schemaDTO.getTable() != null && !schemaDTO.getTable().isEmpty()) {
				tableName = schemaDTO.getTable().get(0).getName();
			}
			displayHint = displayHintService.generate(tableName, resultSetBO, null, schemaDTO);
			log.info("Generated displayHint for table: {}", tableName);
		}
		catch (Exception e) {
			log.warn("Failed to generate display hint", e);
		}

		// 构建结构化结果
		StructuredResultBO structuredResult = StructuredResultBO.builder()
			.resultSet(resultSetBO)
			.displayHint(displayHint)
			.meta(MetaInfo.builder()
				.recordType(rowCount == 1 ? "single" : "list")
				.totalCount(rowCount)
				.hasNestedData(false)
				.build())
			.build();

		// 构建返回结果
		Map<String, Object> result = new HashMap<>();
		result.put(QUERY_SQL, sqlQuery);
		result.put(QUERY_SQL_RESULT, resultSetBO);
		result.put(SQL_RESULT_LIST_MEMORY, resultSetBO.getData());
		result.put(Constant.RESULT, resultSetBO);
		result.put("STRUCTURED_RESULT", structuredResult);

		// 构建ResultBO对象(前端期望的格式，包含displayHint用于移动端智能渲染)
		ResultBO resultBO = new ResultBO();
		resultBO.setResultSet(resultSetBO);
		// 简单查询不需要图表配置,设置为null
		resultBO.setDisplayStyle(null);
		// 设置展示提示和元信息
		resultBO.setDisplayHint(displayHint);
		resultBO.setMeta(structuredResult.getMeta());

		String resultJson;
		try {
			resultJson = JsonUtil.getObjectMapper().writeValueAsString(resultBO);
			log.info("ResultBO JSON output: {}", resultJson);
		}
		catch (Exception e) {
			log.error("Failed to convert ResultBO to JSON", e);
			resultJson = "{}";
		}

		// 创建流式响应,输出RESULT_SET格式的JSON数据
		Flux<ChatResponse> resultFlux = Flux.just(ChatResponseUtil.createResponse("查询执行成功，共返回 " + rowCount + " 条记录"),
				ChatResponseUtil.createResponse("查询结果："),
				ChatResponseUtil.createPureResponse(TextType.RESULT_SET.getStartSign()),
				ChatResponseUtil.createPureResponse(resultJson),
				ChatResponseUtil.createPureResponse(TextType.RESULT_SET.getEndSign()));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> result, resultFlux);

		return Map.of(QUERY_SQL_RESULT, generator);
	}

	/**
	 * 增强查询结果:添加字段说明和枚举值转换 (仅简单查询路径,从State读取已召回的表信息)
	 */
	private ResultSetBO enrichResultSetWithMetadata(ResultSetBO resultSetBO, OverAllState state) {
		if (resultSetBO == null || resultSetBO.getColumn() == null || resultSetBO.getColumn().isEmpty()) {
			return resultSetBO;
		}

		// 1. 从 State 中获取 TableRelationNode 输出的 SchemaDTO
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);

		if (schemaDTO == null || schemaDTO.getTable() == null || schemaDTO.getTable().isEmpty()) {
			log.debug("No TABLE_RELATION_OUTPUT found in state, skipping result set enrichment");
			return resultSetBO;
		}

		// 2. 从 SchemaDTO 中提取字段元数据,转换为 ColumnInfoBO 格式
		Map<String, ColumnInfoBO> columnMetadataMap = new HashMap<>();

		for (TableDTO table : schemaDTO.getTable()) {
			if (table.getColumn() == null)
				continue;

			for (ColumnDTO columnDTO : table.getColumn()) {
				// 将 ColumnDTO 转换为 ColumnInfoBO
				ColumnInfoBO columnInfo = ColumnInfoBO.builder()
					.name(columnDTO.getName())
					.tableName(table.getName())
					.description(columnDTO.getDescription())
					.type(columnDTO.getType())
					.build();

				// 添加 "tableName.columnName" 和 "columnName" 两种键
				String qualifiedKey = table.getName() + "." + columnDTO.getName();
				columnMetadataMap.put(qualifiedKey, columnInfo);
				columnMetadataMap.putIfAbsent(columnDTO.getName(), columnInfo);
			}
		}

		if (columnMetadataMap.isEmpty()) {
			log.warn("No column metadata found in SchemaDTO, returning original result");
			return resultSetBO;
		}

		log.info("Loaded {} column metadata entries from State for enrichment", columnMetadataMap.size());

		// 3. 调用工具类进行增强
		Map<String, String> columnToTableMap = new HashMap<>();
		ResultSetBO enrichedResult = ResultSetEnricherUtil.enrichResultSet(resultSetBO, columnMetadataMap,
				columnToTableMap);

		log.info("Successfully enriched result set with field descriptions and enum conversions");
		return enrichedResult;
	}


}
