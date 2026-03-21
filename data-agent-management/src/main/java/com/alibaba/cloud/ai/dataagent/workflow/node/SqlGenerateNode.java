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

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryHistoryItem;
import com.alibaba.cloud.ai.dataagent.dto.planner.ExecutionStep;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.service.memory.SqlMemoryEnhancer;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlMemoryBlock;
import com.alibaba.cloud.ai.dataagent.service.memory.SqlMemoryService;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

/**
 * Enhanced SQL generation node that handles SQL query regeneration with advanced
 * optimization features. This node is responsible for: - Multi-round SQL optimization and
 * refinement - Syntax validation and security analysis - Performance optimization and
 * intelligent caching - Handling execution exceptions and semantic consistency failures -
 * Managing retry logic with schema advice - Providing streaming feedback during
 * regeneration process
 *
 * @author zhangshenghang
 */
@Slf4j
@Component
@AllArgsConstructor
public class SqlGenerateNode implements NodeAction {

	private final Nl2SqlService nl2SqlService;

	private final DataAgentProperties properties;

	private final SqlMemoryService sqlMemoryService;

	private final SqlMemoryEnhancer sqlMemoryEnhancer;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 判断是否达到最大尝试次数
		int count = state.value(SQL_GENERATE_COUNT, 0);
		if (count >= properties.getMaxSqlRetryCount()) {
			// 检查是否有计划（用于区分简单查询和复杂分析）
			boolean hasPlan = state.value(PLANNER_NODE_OUTPUT).isPresent();
			String sqlGenerateOutput;
			if (hasPlan) {
				ExecutionStep executionStep = PlanProcessUtil.getCurrentExecutionStep(state);
				sqlGenerateOutput = String.format("步骤[%d]中，SQL次数生成超限，最大尝试次数：%d，已尝试次数:%d，该步骤内容: \n %s",
						executionStep.getStep(), properties.getMaxSqlRetryCount(), count,
						executionStep.getToolParameters().getInstruction());
			}
			else {
				sqlGenerateOutput = String.format("SQL生成超限，最大尝试次数：%d，已尝试次数:%d", properties.getMaxSqlRetryCount(),
						count);
			}
			log.error("SQL generation failed, reason: {}", sqlGenerateOutput);
			Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(sqlGenerateOutput));
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
					this.getClass(), state, "正在进行重试评估...", "重试评估完成！",
					retryOutput -> Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT, 0), preFlux);
			// reset the sql generate count
			return Map.of(SQL_GENERATE_OUTPUT, generator);
		}

		// 获取SQL生成的指令
		// 1. 如果有计划（复杂分析路径），使用planner分配的当前执行步骤的sql任务要求
		// 2. 如果没有计划（简单查询路径），直接使用用户原始查询
		String promptForSql;
		boolean hasPlan = state.value(PLANNER_NODE_OUTPUT).isPresent();
		if (hasPlan) {
			promptForSql = getCurrentExecutionStepInstruction(state);
			log.debug("Using plan-based SQL instruction: {}", promptForSql);
		}
		else {
			// 简单查询路径：直接使用用户查询
			promptForSql = StateUtil.getStringValue(state, INPUT_KEY, "");
			log.debug("Using direct user query as SQL instruction: {}", promptForSql);
		}

		// 准备生成SQL
		String displayMessage;
		Flux<String> sqlFlux;
		SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class,
				SqlRetryDto.empty());

		// 获取历史重试记录
		List<SqlRetryHistoryItem> retryHistory = getRetryHistory(state);
		String originalSql = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, "");

		if (retryDto.sqlExecuteFail()) {
			displayMessage = "检测到SQL执行异常，开始重新生成SQL...";
			// 添加到历史记录
			retryHistory = addToHistory(retryHistory, count, originalSql, retryDto.reason(), "execution");
			sqlFlux = handleRetryGenerateSqlForExecutionError(state, originalSql, retryDto.reason(), promptForSql);
		}
		else if (retryDto.semanticFail()) {
			displayMessage = "语义一致性校验未通过，开始重新生成SQL...";
			// 添加到历史记录
			retryHistory = addToHistory(retryHistory, count, originalSql, retryDto.reason(), "semantic");
			sqlFlux = handleRetryGenerateSqlForSemanticFail(state, originalSql, retryDto.reason(), promptForSql, retryHistory);
		}
		else {
			displayMessage = "开始生成SQL...";
			// 首次生成，清空历史记录
			retryHistory = new ArrayList<>();
			sqlFlux = handleGenerateSql(state, promptForSql);
		}

		// 准备返回结果，同时需要清除一些状态数据，保留历史记录
		List<SqlRetryHistoryItem> finalRetryHistory = retryHistory;
		Map<String, Object> result = new HashMap<>(Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT,
				count + 1, SQL_REGENERATE_REASON, SqlRetryDto.empty(), SQL_RETRY_HISTORY, finalRetryHistory));

		// Create display flux for user experience only
		StringBuilder sqlCollector = new StringBuilder();
		Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(displayMessage),
				ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()));
		Flux<ChatResponse> displayFlux = preFlux
			.concatWith(sqlFlux.doOnNext(sqlCollector::append).map(ChatResponseUtil::createPureResponse))
			.concatWith(Flux.just(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()),
					ChatResponseUtil.createResponse("SQL生成完成，准备执行")));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> {
					String sql = nl2SqlService.sqlTrim(sqlCollector.toString());
					result.put(SQL_GENERATE_OUTPUT, sql);
					return result;
				}, displayFlux);

		return Map.of(SQL_GENERATE_OUTPUT, generator);
	}

	/**
	 * Handle SQL regeneration for execution errors (database errors like syntax errors,
	 * missing columns, etc.)
	 */
	private Flux<String> handleRetryGenerateSqlForExecutionError(OverAllState state, String originalSql, String errorMsg,
			String executionDescription) {
		SqlGenerationDTO sqlGenerationDTO = buildSqlGenerationDTO(state, originalSql, errorMsg, executionDescription, null);
		return nl2SqlService.generateSql(sqlGenerationDTO);
	}

	/**
	 * Handle SQL regeneration for semantic consistency failures (logic issues identified
	 * by LLM validation)
	 */
	private Flux<String> handleRetryGenerateSqlForSemanticFail(OverAllState state, String originalSql,
			String semanticFeedback, String executionDescription, List<SqlRetryHistoryItem> retryHistory) {
		SqlGenerationDTO sqlGenerationDTO = buildSqlGenerationDTO(state, originalSql, semanticFeedback, executionDescription, retryHistory);
		return nl2SqlService.regenerateSqlForSemanticFail(sqlGenerationDTO);
	}

	private Flux<String> handleGenerateSql(OverAllState state, String executionDescription) {
		SqlGenerationDTO sqlGenerationDTO = buildSqlGenerationDTO(state, null, null, executionDescription, null);
		return nl2SqlService.generateSql(sqlGenerationDTO);
	}

	private SqlGenerationDTO buildSqlGenerationDTO(OverAllState state, String originalSql, String errorMsg,
			String executionDescription, List<SqlRetryHistoryItem> retryHistory) {
		String agentId = StateUtil.getStringValue(state, AGENT_ID, "");
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String userQuery = StateUtil.getCanonicalQuery(state);
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);
		SqlMemoryBlock sqlMemoryBlock = sqlMemoryService.loadSqlMemory(agentId);
		String sqlMemoryAdvice = sqlMemoryEnhancer.enhance(sqlMemoryService.findSimilarSuccess(agentId, userQuery, 3),
				sqlMemoryService.findSimilarError(agentId, userQuery, 2), sqlMemoryBlock.successSummaries(),
				sqlMemoryBlock.errorSummaries());

		return SqlGenerationDTO.builder()
			.evidence(evidence)
			.query(userQuery)
			.schemaDTO(schemaDTO)
			.sql(originalSql)
			.exceptionMessage(errorMsg)
			.executionDescription(executionDescription)
			.dialect(dialect)
			.sqlMemoryAdvice(sqlMemoryAdvice)
			.retryHistory(retryHistory)
			.build();
	}

	/**
	 * Get retry history from state
	 */
	@SuppressWarnings("unchecked")
	private List<SqlRetryHistoryItem> getRetryHistory(OverAllState state) {
		return state.value(SQL_RETRY_HISTORY, List.class).map(list -> (List<SqlRetryHistoryItem>) list)
				.orElse(new ArrayList<>());
	}

	/**
	 * Add a new retry attempt to history
	 */
	private List<SqlRetryHistoryItem> addToHistory(List<SqlRetryHistoryItem> history, int attemptNumber,
			String sql, String reason, String type) {
		List<SqlRetryHistoryItem> newHistory = new ArrayList<>(history);
		SqlRetryHistoryItem item = "semantic".equals(type)
				? SqlRetryHistoryItem.semantic(attemptNumber, sql, reason)
				: SqlRetryHistoryItem.execution(attemptNumber, sql, reason);
		newHistory.add(item);
		log.debug("Added retry history item #{}: type={}, sql={}", attemptNumber, type, sql);
		return newHistory;
	}

}
