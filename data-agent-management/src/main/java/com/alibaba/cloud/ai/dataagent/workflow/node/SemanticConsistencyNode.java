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

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SemanticConsistencyDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.DB_DIALECT_TYPE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SEMANTIC_CONSISTENCY_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.prompt.PromptHelper.buildMixMacSqlDbPrompt;
import static com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

/**
 * Semantic consistency validation node.
 */
@Slf4j
@Component
@AllArgsConstructor
public class SemanticConsistencyNode implements NodeAction {

	private static final String PASS_CONCLUSION = "通过";

	private static final String FAIL_CONCLUSION = "不通过";

	private static final String ERROR_TYPE_KEY = "错误类型=";

	private static final String PROBLEM_DESCRIPTION_KEY = "问题描述=";

	private static final String TYPICAL_ERROR_KEY = "典型错误=";

	private static final String SOLUTION_KEY = "解决方案=";

	private final Nl2SqlService nl2SqlService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);
		String sql = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
		String userQuery = StateUtil.getCanonicalQuery(state);

		String executionDescription;
		boolean hasPlan = state.value(PLANNER_NODE_OUTPUT).isPresent();
		if (hasPlan) {
			executionDescription = getCurrentExecutionStepInstruction(state);
			log.debug("使用计划步骤说明进行语义一致性校验");
		}
		else {
			executionDescription = StateUtil.getStringValue(state, INPUT_KEY, "");
			log.debug("使用用户原始问题进行语义一致性校验");
		}

		SemanticConsistencyDTO semanticConsistencyDTO = SemanticConsistencyDTO.builder()
			.dialect(dialect)
			.sql(sql)
			.executionDescription(executionDescription)
			.schemaInfo(buildMixMacSqlDbPrompt(schemaDTO, true))
			.userQuery(userQuery)
			.evidence(evidence)
			.build();
		log.info("开始语义一致性校验，SQL：{}", sql);
		Flux<ChatResponse> validationResultFlux = nl2SqlService.performSemanticConsistency(semanticConsistencyDTO);
		StringBuilder validationCollector = new StringBuilder();
		Flux<ChatResponse> displayFlux = validationResultFlux
			.doOnNext(chatResponse -> validationCollector.append(ChatResponseUtil.getText(chatResponse)))
			.thenMany(Flux.defer(() -> Flux
				.just(ChatResponseUtil.createResponse(buildValidationDisplayMessage(validationCollector.toString())))));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "开始语义一致性校验", "语义一致性校验完成", ignored -> {
					String validationResult = validationCollector.toString();
					boolean isPassed = parseValidationResult(validationResult);
					if (isPassed) {
						log.info("语义一致性校验通过");
					}
					else {
						log.info("语义一致性校验未通过，摘要：{}", extractFailureBriefSummary(validationResult));
					}
					Map<String, Object> result = buildValidationResult(isPassed, validationResult);
					log.info("[{}] 语义一致性校验完成，是否通过：{}，摘要：{}", this.getClass().getSimpleName(), isPassed,
							isPassed ? "通过" : extractFailureBriefSummary(validationResult));
					return result;
				}, displayFlux);

		return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, generator);
	}

	private boolean parseValidationResult(String validationResult) {
		if (validationResult == null) {
			return true;
		}
		String trimmed = validationResult.trim();
		if (trimmed.isEmpty()) {
			return true;
		}
		if (isExplicitPass(trimmed)) {
			return true;
		}
		if (isExplicitFailure(trimmed)) {
			return false;
		}
		String[] lines = trimmed.split("\\R");
		String lastLine = lines[lines.length - 1].trim();
		if (isExplicitFailure(lastLine)) {
			return false;
		}
		if (isExplicitPass(lastLine)) {
			return true;
		}
		if (containsNegativeConclusion(trimmed)) {
			return false;
		}
		if (containsPositiveConclusion(trimmed)) {
			return true;
		}
		return true;
	}

	private boolean isExplicitPass(String text) {
		return PASS_CONCLUSION.equals(text) || text.startsWith(PASS_CONCLUSION + "|") || text.startsWith(PASS_CONCLUSION + "\n");
	}

	private boolean isExplicitFailure(String text) {
		return FAIL_CONCLUSION.equals(text) || text.startsWith(FAIL_CONCLUSION + "|") || text.startsWith(FAIL_CONCLUSION + "\n");
	}

	private boolean containsNegativeConclusion(String text) {
		return text.contains("未通过") || text.contains("不通过") || text.contains("校验失败") || text.contains("判定为不通过")
				|| text.contains("最终判定为不通过") || text.contains("修正:不通过") || text.contains("修正：不通过")
				|| text.contains("更正:不通过") || text.contains("更正：不通过");
	}

	private boolean containsPositiveConclusion(String text) {
		return text.contains("最终判定为通过") || text.contains("判定为通过") || text.contains("修正:通过")
				|| text.contains("修正：通过") || text.contains("更正:通过") || text.contains("更正：通过")
				|| text.contains(PASS_CONCLUSION);
	}

	private String extractStructuredFailureSummary(String validationResult) {
		if (validationResult == null) {
			return "";
		}
		String trimmed = validationResult.trim();
		if (!trimmed.startsWith(FAIL_CONCLUSION)) {
			return trimmed;
		}
		Map<String, String> fields = parseStructuredFailureFields(trimmed);
		if (fields.isEmpty()) {
			return trimmed;
		}
		StringBuilder summary = new StringBuilder();
		appendSummaryField(summary, "错误类型", fields.get("错误类型"));
		appendSummaryField(summary, "问题描述", fields.get("问题描述"));
		appendSummaryField(summary, "典型错误", fields.get("典型错误"));
		appendSummaryField(summary, "解决方案", fields.get("解决方案"));
		return summary.length() == 0 ? trimmed : summary.toString();
	}

	private String extractFailureBriefSummary(String validationResult) {
		if (validationResult == null) {
			return "";
		}
		String trimmed = validationResult.trim();
		if (!trimmed.startsWith(FAIL_CONCLUSION)) {
			return trimmed;
		}
		Map<String, String> fields = parseStructuredFailureFields(trimmed);
		if (fields.isEmpty()) {
			return trimmed;
		}
		StringBuilder summary = new StringBuilder();
		appendSummaryField(summary, "错误类型", fields.get("错误类型"));
		appendSummaryField(summary, "问题描述", fields.get("问题描述"));
		return summary.length() == 0 ? trimmed : summary.toString();
	}

	private String buildValidationDisplayMessage(String validationResult) {
		if (parseValidationResult(validationResult)) {
			return "语义一致性校验通过";
		}
		String summary = extractFailureBriefSummary(validationResult);
		if (summary.isBlank()) {
			return "语义一致性校验未通过";
		}
		// 前端提示只保留简短失败原因，避免把完整结构化内容直接展示出来。
		return "语义一致性校验未通过：" + abbreviate(summary, 80);
	}

	private String abbreviate(String text, int maxLength) {
		if (text == null || text.length() <= maxLength) {
			return text == null ? "" : text;
		}
		return text.substring(0, maxLength) + "...";
	}

	private Map<String, String> parseStructuredFailureFields(String validationResult) {
		String payload = validationResult.substring(FAIL_CONCLUSION.length()).stripLeading();
		if (payload.startsWith("|")) {
			payload = payload.substring(1).stripLeading();
		}
		if (payload.contains("\n")) {
			return parseLineBasedStructuredFields(payload);
		}
		if (payload.contains(ERROR_TYPE_KEY) || payload.contains(PROBLEM_DESCRIPTION_KEY) || payload.contains(TYPICAL_ERROR_KEY)
				|| payload.contains(SOLUTION_KEY)) {
			return parseLegacyDelimitedStructuredFields(payload);
		}
		return Map.of();
	}

	private Map<String, String> parseLineBasedStructuredFields(String payload) {
		Map<String, String> fields = new LinkedHashMap<>();
		String currentKey = null;
		StringBuilder currentValue = new StringBuilder();
		for (String line : payload.split("\\R", -1)) {
			String trimmedLine = line.stripTrailing();
			String key = extractLineKey(trimmedLine);
			if (key != null) {
				putStructuredField(fields, currentKey, currentValue);
				currentKey = key;
				currentValue = new StringBuilder(trimmedLine.substring(key.length() + 1).stripLeading());
				continue;
			}
			if (currentKey != null) {
				if (currentValue.length() > 0) {
					currentValue.append('\n');
				}
				currentValue.append(line);
			}
		}
		putStructuredField(fields, currentKey, currentValue);
		return fields;
	}

	private String extractLineKey(String line) {
		if (line.startsWith(ERROR_TYPE_KEY)) {
			return "错误类型";
		}
		if (line.startsWith(PROBLEM_DESCRIPTION_KEY)) {
			return "问题描述";
		}
		if (line.startsWith(TYPICAL_ERROR_KEY)) {
			return "典型错误";
		}
		if (line.startsWith(SOLUTION_KEY)) {
			return "解决方案";
		}
		return null;
	}

	private Map<String, String> parseLegacyDelimitedStructuredFields(String payload) {
		Map<String, String> fields = new LinkedHashMap<>();
		putStructuredField(fields, "错误类型", extractBetween(payload, ERROR_TYPE_KEY, PROBLEM_DESCRIPTION_KEY));
		putStructuredField(fields, "问题描述", extractBetween(payload, PROBLEM_DESCRIPTION_KEY, TYPICAL_ERROR_KEY));
		putStructuredField(fields, "典型错误", extractBetween(payload, TYPICAL_ERROR_KEY, SOLUTION_KEY));
		putStructuredField(fields, "解决方案", extractBetween(payload, SOLUTION_KEY, null));
		return fields;
	}

	private String extractBetween(String text, String startToken, String endToken) {
		int start = text.indexOf(startToken);
		if (start < 0) {
			return null;
		}
		int valueStart = start + startToken.length();
		int end = endToken == null ? text.length() : text.indexOf(endToken, valueStart);
		if (end < 0) {
			end = text.length();
		}
		return trimLegacyFieldDelimiter(text.substring(valueStart, end));
	}

	private String trimLegacyFieldDelimiter(String value) {
		String trimmed = value.trim();
		while (trimmed.startsWith("|")) {
			trimmed = trimmed.substring(1).trim();
		}
		while (trimmed.endsWith("|")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return trimmed;
	}

	private void putStructuredField(Map<String, String> fields, String currentKey, Object currentValue) {
		if (currentKey == null) {
			return;
		}
		String value = currentValue.toString().trim();
		if (!value.isEmpty()) {
			fields.put(currentKey, value);
		}
	}

	private void appendSummaryField(StringBuilder summary, String label, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		if (summary.length() > 0) {
			summary.append('；');
		}
		summary.append(label).append('：').append(value);
	}

	private Map<String, Object> buildValidationResult(boolean passed, String validationResult) {
		if (passed) {
			return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, true);
		}
		return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, false, SQL_REGENERATE_REASON, SqlRetryDto.semantic(validationResult));
	}

}
