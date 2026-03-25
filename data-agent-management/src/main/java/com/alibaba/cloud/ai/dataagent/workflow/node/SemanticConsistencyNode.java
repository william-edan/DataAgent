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
import com.alibaba.cloud.ai.dataagent.service.nl2sql.QueryContractService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
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

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.DB_DIALECT_TYPE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SEMANTIC_CONSISTENCY_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TIME_SEMANTIC_BYPASS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TIME_SEMANTIC_FAIL_COUNT;
import static com.alibaba.cloud.ai.dataagent.prompt.PromptHelper.buildMixMacSqlDbPrompt;
import static com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

/**
 * Semantic consistency validation node.
 */
@Slf4j
@Component
@AllArgsConstructor
public class SemanticConsistencyNode implements NodeAction {

	private static final int TIME_SEMANTIC_BYPASS_THRESHOLD = 3;

	private static final String PASS_CONCLUSION = "通过";

	private static final String FAIL_CONCLUSION = "不通过";

	private static final String ERROR_TYPE_KEY = "错误类型=";

	private static final String PROBLEM_DESCRIPTION_KEY = "问题描述=";

	private static final String TYPICAL_ERROR_KEY = "典型错误=";

	private static final String SOLUTION_KEY = "解决方案=";

	private final Nl2SqlService nl2SqlService;

	private final QueryContractService queryContractService;

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

		String queryContract = queryContractService.buildQueryContract(userQuery, executionDescription, evidence, schemaDTO);
		if (!queryContract.isBlank()) {
			log.info("已构建语义校验查询契约，用于减少生成与校验之间的约束偏差。");
		}
		int currentTimeSemanticFailCount = state.value(TIME_SEMANTIC_FAIL_COUNT, 0);
		boolean currentTimeSemanticBypass = state.value(TIME_SEMANTIC_BYPASS, false);

		SemanticConsistencyDTO semanticConsistencyDTO = SemanticConsistencyDTO.builder()
			.dialect(dialect)
			.sql(sql)
			.executionDescription(executionDescription)
			.schemaInfo(buildMixMacSqlDbPrompt(schemaDTO, true))
			.userQuery(userQuery)
			.evidence(evidence)
			.queryContract(queryContract)
			.build();
		log.info("开始语义一致性校验，SQL：{}", sql);
		Flux<ChatResponse> validationResultFlux = nl2SqlService.performSemanticConsistency(semanticConsistencyDTO);
		StringBuilder validationCollector = new StringBuilder();
		Flux<ChatResponse> displayFlux = validationResultFlux
			.doOnNext(chatResponse -> validationCollector.append(ChatResponseUtil.getText(chatResponse)))
			.thenMany(Flux.defer(() -> Flux
				.just(ChatResponseUtil.createResponse(
						buildValidationDisplayMessage(validationCollector.toString(), queryContract,
								currentTimeSemanticFailCount, currentTimeSemanticBypass)))));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "开始语义一致性校验", "语义一致性校验完成", ignored -> {
					String validationResult = validationCollector.toString();
					ValidationDecision validationDecision = evaluateValidationResult(validationResult, queryContract,
							currentTimeSemanticFailCount, currentTimeSemanticBypass);
					boolean isPassed = validationDecision.passed();
					if (isPassed) {
						if (validationDecision.bypassJustEnabled()) {
							log.info("连续触发时间语义校验失败，已临时关闭本轮对话的时间校验，当前次数：{}",
									validationDecision.timeSemanticFailCount());
						}
						if (validationDecision.downgraded() && !validationDecision.timeSemanticBypass()) {
							log.info("检测到 Schema 缺少可靠快照时间字段，本轮仅因推断时间条件缺失而失败，降级为通过。摘要：{}",
									extractFailureBriefSummary(validationResult));
						}
						if (validationDecision.timeSemanticBypass() && validationDecision.timeSemanticFailure()) {
							log.info("时间语义熔断已开启，本轮放开时间类语义校验，摘要：{}", extractFailureBriefSummary(validationResult));
						}
						log.info("语义一致性校验通过");
					}
					else {
						log.info("语义一致性校验未通过，摘要：{}", extractFailureBriefSummary(validationResult));
					}
					Map<String, Object> result = buildValidationResult(isPassed, validationResult,
							validationDecision.timeSemanticFailCount(), validationDecision.timeSemanticBypass());
					log.info("[{}] 语义一致性校验完成，是否通过：{}，摘要：{}", this.getClass().getSimpleName(), isPassed,
							isPassed ? "通过" : extractFailureBriefSummary(validationResult));
					return result;
				}, displayFlux);

		return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, generator);
	}

	private ValidationDecision evaluateValidationResult(String validationResult, String queryContract,
			int currentTimeSemanticFailCount, boolean currentTimeSemanticBypass) {
		if (parseValidationResult(validationResult)) {
			return ValidationDecision.pass(currentTimeSemanticFailCount, currentTimeSemanticBypass);
		}
		boolean timeSemanticFailure = isTimeSemanticFailure(validationResult);
		int nextTimeSemanticFailCount = currentTimeSemanticFailCount;
		boolean nextTimeSemanticBypass = currentTimeSemanticBypass;
		if (timeSemanticFailure && !currentTimeSemanticBypass) {
			nextTimeSemanticFailCount = currentTimeSemanticFailCount + 1;
			if (nextTimeSemanticFailCount >= TIME_SEMANTIC_BYPASS_THRESHOLD) {
				nextTimeSemanticBypass = true;
			}
		}
		if (timeSemanticFailure && nextTimeSemanticBypass) {
			return ValidationDecision.downgradedPass(nextTimeSemanticFailCount, nextTimeSemanticBypass,
					!currentTimeSemanticBypass && nextTimeSemanticBypass, true);
		}
		if (shouldDowngradeMissingTimeFilterFailure(validationResult, queryContract)) {
			return ValidationDecision.downgradedPass(nextTimeSemanticFailCount, nextTimeSemanticBypass, false, true);
		}
		return ValidationDecision.fail(nextTimeSemanticFailCount, nextTimeSemanticBypass, timeSemanticFailure);
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

	private String buildValidationDisplayMessage(String validationResult, String queryContract,
			int currentTimeSemanticFailCount, boolean currentTimeSemanticBypass) {
		if (evaluateValidationResult(validationResult, queryContract, currentTimeSemanticFailCount, currentTimeSemanticBypass)
			.passed()) {
			return "语义一致性校验通过";
		}
		String summary = extractFailureBriefSummary(validationResult);
		if (summary.isBlank()) {
			return "语义一致性校验未通过";
		}
		// 前端提示只保留简短失败原因，避免把完整结构化内容直接展示出来。
		return "语义一致性校验未通过：" + abbreviate(summary, 80);
	}

	private boolean isTimeSemanticFailure(String validationResult) {
		String trimmed = StringUtils.trimToEmpty(validationResult);
		if (trimmed.isEmpty() || !trimmed.startsWith(FAIL_CONCLUSION)) {
			return false;
		}
		Map<String, String> fields = parseStructuredFailureFields(trimmed);
		String errorType = fields.getOrDefault("错误类型", trimmed);
		String problemDescription = fields.getOrDefault("问题描述", trimmed);
		String typicalError = fields.getOrDefault("典型错误", "");
		String normalizedErrorType = StringUtils.defaultString(errorType).toUpperCase(Locale.ROOT);
		String normalizedDetails = StringUtils.defaultString(problemDescription) + "\n" + typicalError;
		return normalizedErrorType.contains("TIME_") || normalizedErrorType.contains("DATE_")
				|| normalizedDetails.contains("时间") || normalizedDetails.contains("日期") || normalizedDetails.contains("截至")
				|| normalizedDetails.contains("截止") || normalizedDetails.contains("快照");
	}

	private boolean shouldDowngradeMissingTimeFilterFailure(String validationResult, String queryContract) {
		// 当 Schema 明确缺少可靠快照字段时，只因“缺少推断时间条件”失败不再继续空转重试。
		if (!StringUtils.contains(queryContract, QueryContractService.NO_RELIABLE_SNAPSHOT_FIELD_MARKER)
				|| !StringUtils.contains(queryContract, QueryContractService.AVOID_INFERRED_TIME_FILTER_MARKER)) {
			return false;
		}
		String trimmed = StringUtils.trimToEmpty(validationResult);
		if (trimmed.isEmpty()) {
			return false;
		}
		Map<String, String> fields = trimmed.startsWith(FAIL_CONCLUSION) ? parseStructuredFailureFields(trimmed) : Map.of();
		String errorType = fields.getOrDefault("错误类型", trimmed);
		String problemDescription = fields.getOrDefault("问题描述", trimmed);
		String failureDetails = problemDescription + "\n" + fields.getOrDefault("典型错误", "");
		if (!isMissingTimeFilterFailure(errorType, problemDescription)) {
			return false;
		}
		return !containsDiscouragedPseudoSnapshotField(failureDetails, queryContract);
	}

	private boolean isMissingTimeFilterFailure(String errorType, String problemDescription) {
		String normalizedErrorType = StringUtils.defaultString(errorType).toUpperCase(Locale.ROOT);
		String normalizedProblem = StringUtils.defaultString(problemDescription);
		return normalizedErrorType.contains("TIME_FILTER_MISSING") || normalizedProblem.contains("缺少与时间相关的筛选条件")
				|| normalizedProblem.contains("关键日期过滤") || normalizedProblem.contains("限定在“截至");
	}

	private boolean containsDiscouragedPseudoSnapshotField(String text, String queryContract) {
		if (StringUtils.isBlank(text) || StringUtils.isBlank(queryContract)) {
			return false;
		}
		String normalizedText = text.toLowerCase(Locale.ROOT);
		for (String fieldName : extractDiscouragedPseudoSnapshotFields(queryContract)) {
			if (normalizedText.contains(fieldName.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private Set<String> extractDiscouragedPseudoSnapshotFields(String queryContract) {
		Set<String> fields = new LinkedHashSet<>();
		for (String line : StringUtils.defaultString(queryContract).split("\\R")) {
			if (!line.startsWith("- 不要将以下字段直接当作业务快照时间替代：")) {
				continue;
			}
			String payload = StringUtils.substringAfter(line, "：");
			for (String field : payload.split("[,，]")) {
				String normalizedField = field.trim();
				if (!normalizedField.isEmpty()) {
					fields.add(normalizedField);
				}
			}
		}
		return fields;
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

	private Map<String, Object> buildValidationResult(boolean passed, String validationResult, int timeSemanticFailCount,
			boolean timeSemanticBypass) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put(TIME_SEMANTIC_FAIL_COUNT, timeSemanticFailCount);
		result.put(TIME_SEMANTIC_BYPASS, timeSemanticBypass);
		if (passed) {
			result.put(SEMANTIC_CONSISTENCY_NODE_OUTPUT, true);
			return result;
		}
		result.put(SEMANTIC_CONSISTENCY_NODE_OUTPUT, false);
		result.put(SQL_REGENERATE_REASON, SqlRetryDto.semantic(validationResult));
		return result;
	}

	private record ValidationDecision(boolean passed, boolean downgraded, int timeSemanticFailCount,
			boolean timeSemanticBypass, boolean bypassJustEnabled, boolean timeSemanticFailure) {

		private static ValidationDecision pass(int timeSemanticFailCount, boolean timeSemanticBypass) {
			return new ValidationDecision(true, false, timeSemanticFailCount, timeSemanticBypass, false, false);
		}

		private static ValidationDecision downgradedPass(int timeSemanticFailCount, boolean timeSemanticBypass,
				boolean bypassJustEnabled, boolean timeSemanticFailure) {
			return new ValidationDecision(true, true, timeSemanticFailCount, timeSemanticBypass, bypassJustEnabled,
					timeSemanticFailure);
		}

		private static ValidationDecision fail(int timeSemanticFailCount, boolean timeSemanticBypass,
				boolean timeSemanticFailure) {
			return new ValidationDecision(false, false, timeSemanticFailCount, timeSemanticBypass, false,
					timeSemanticFailure);
		}

	}

}
