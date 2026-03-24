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
package com.alibaba.cloud.ai.dataagent.service.nl2sql;

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlSemanticLearningCard;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * SQL 语义失败学习服务实现。
 */
@Service
public class SqlSemanticLearningServiceImpl implements SqlSemanticLearningService {

	private static final String ONLY_FULL_GROUP_BY_ERROR_TYPE = "ONLY_FULL_GROUP_BY_NONAGGREGATED_COLUMN";

	private static final String FAIL_CONCLUSION = "不通过";

	private static final String ERROR_TYPE_KEY = "错误类型=";

	private static final String PROBLEM_DESCRIPTION_KEY = "问题描述=";

	private static final String TYPICAL_ERROR_KEY = "典型错误=";

	private static final String SOLUTION_KEY = "解决方案=";

	private static final String DEFAULT_TEXT = "无";

	private static final String LEARNING_SECTION_TITLE = "本轮 SQL 失败学习经验";

	@Override
	public Optional<SqlSemanticLearningCard> buildLearningCard(String validationResult) {
		if (StringUtils.isBlank(validationResult)) {
			return Optional.empty();
		}
		String trimmed = validationResult.trim();
		if (!trimmed.startsWith(FAIL_CONCLUSION)) {
			return Optional.empty();
		}
		Map<String, String> fields = parseStructuredFields(trimmed);

		SqlSemanticLearningCard card = SqlSemanticLearningCard.builder()
			.errorType(fields.getOrDefault("错误类型", ""))
			.problemDescription(fields.getOrDefault("问题描述", ""))
			.typicalWrongSql(fields.getOrDefault("典型错误", ""))
			.solutions(parseSolutions(fields.get("解决方案")))
			.build();
		if (StringUtils.isBlank(card.getErrorType()) || StringUtils.isBlank(card.getProblemDescription())) {
			return Optional.empty();
		}
		card.setFingerprint(buildFingerprint(card));
		card.setMarkdownContent(buildMarkdown(card));
		return Optional.of(card);
	}

	@Override
	public Optional<SqlSemanticLearningCard> buildExecutionLearningCard(String errorMessage, String sql) {
		if (StringUtils.isBlank(errorMessage)) {
			return Optional.empty();
		}

		SqlSemanticLearningCard card = buildOnlyFullGroupByCard(errorMessage, sql)
			.orElseGet(() -> buildGenericExecutionCard(errorMessage, sql));
		card.setFingerprint(buildFingerprint(card));
		card.setMarkdownContent(buildMarkdown(card));
		return Optional.of(card);
	}

	@Override
	public String buildMarkdown(SqlSemanticLearningCard card) {
		if (card != null || card == null) {
			StringBuilder compactMarkdown = new StringBuilder();
			compactMarkdown.append("### 失败学习卡\n");
			compactMarkdown.append("- 错误类型：")
				.append(StringUtils.defaultIfBlank(card == null ? null : card.getErrorType(), DEFAULT_TEXT))
				.append("\n");
			compactMarkdown.append("- 问题描述：")
				.append(StringUtils.defaultIfBlank(card == null ? null : card.getProblemDescription(), DEFAULT_TEXT))
				.append("\n");
			compactMarkdown.append("- 修复要点：\n");
			List<String> compactSolutions = card == null ? List.of() : card.getSolutions();
			if (compactSolutions == null || compactSolutions.isEmpty()) {
				compactMarkdown.append("  1. ").append(DEFAULT_TEXT).append("\n");
			}
			else {
				for (int i = 0; i < compactSolutions.size(); i++) {
					compactMarkdown.append("  ").append(i + 1).append(". ").append(compactSolutions.get(i)).append("\n");
				}
			}
			compactMarkdown.append("- 错误 SQL：\n");
			String compactSql = StringUtils.defaultIfBlank(card == null ? null : card.getTypicalWrongSql(), DEFAULT_TEXT);
			if (!DEFAULT_TEXT.equals(compactSql)) {
				compactMarkdown.append("```sql\n").append(compactSql).append("\n```");
			}
			else {
				compactMarkdown.append(DEFAULT_TEXT);
			}
			return compactMarkdown.toString();
		}
		StringBuilder markdown = new StringBuilder();
		markdown.append("**问题描述**：\n");
		markdown.append(StringUtils.defaultIfBlank(card.getProblemDescription(), DEFAULT_TEXT)).append("\n\n");
		markdown.append("**典型错误**：\n");
		String typicalWrongSql = StringUtils.defaultIfBlank(card.getTypicalWrongSql(), DEFAULT_TEXT);
		if (!DEFAULT_TEXT.equals(typicalWrongSql)) {
			markdown.append("```sql\n").append(typicalWrongSql).append("\n```\n\n");
		}
		else {
			markdown.append(DEFAULT_TEXT).append("\n\n");
		}
		markdown.append("**解决方案**：\n");
		List<String> solutions = card.getSolutions();
		if (solutions == null || solutions.isEmpty()) {
			markdown.append(DEFAULT_TEXT);
		}
		else {
			for (int i = 0; i < solutions.size(); i++) {
				markdown.append(i + 1).append(". ").append(solutions.get(i)).append("\n");
			}
			markdown.setLength(markdown.length() - 1);
		}
		return markdown.toString();
	}

	@Override
	public String buildFingerprint(SqlSemanticLearningCard card) {
		String errorType = StringUtils.defaultString(card.getErrorType()).trim().toUpperCase(Locale.ROOT);
		String normalizedDescription = normalizeText(card.getProblemDescription());
		if (StringUtils.isBlank(errorType) && StringUtils.isBlank(normalizedDescription)) {
			return "";
		}
		return errorType + ":" + normalizedDescription;
	}

	@Override
	public boolean isDuplicate(String fingerprint, Collection<String> existingFingerprints) {
		if (StringUtils.isBlank(fingerprint) || existingFingerprints == null || existingFingerprints.isEmpty()) {
			return false;
		}
		return existingFingerprints.contains(fingerprint);
	}

	@Override
	public String mergeLearningContent(String existingLearningContent, SqlSemanticLearningCard card) {
		String markdownContent = card == null ? "" : StringUtils.defaultString(card.getMarkdownContent()).trim();
		if (StringUtils.isBlank(markdownContent)) {
			return StringUtils.defaultString(existingLearningContent);
		}
		if (StringUtils.isBlank(existingLearningContent)) {
			return markdownContent;
		}
		return existingLearningContent.trim() + "\n\n---\n\n" + markdownContent;
	}

	@Override
	public String buildPromptEvidence(String evidence, String learningContent) {
		if (StringUtils.isBlank(learningContent)) {
			return StringUtils.defaultString(evidence);
		}
		StringBuilder promptEvidence = new StringBuilder();
		if (StringUtils.isNotBlank(evidence)) {
			promptEvidence.append(evidence.trim()).append("\n\n");
		}
		promptEvidence.append("## ").append(LEARNING_SECTION_TITLE).append("\n");
		promptEvidence.append("以下内容来自本轮 SQL 失败后的学习，请优先遵循，避免重复犯同类错误。\n");
		promptEvidence.append(learningContent.trim());
		return promptEvidence.toString();
	}

	private Map<String, String> parseStructuredFields(String validationResult) {
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
			String key = extractLineKey(line.stripTrailing());
			if (key != null) {
				putField(fields, currentKey, currentValue);
				currentKey = key;
				currentValue = new StringBuilder(line.substring(key.length() + 1).stripLeading());
				continue;
			}
			if (currentKey != null) {
				if (currentValue.length() > 0) {
					currentValue.append('\n');
				}
				currentValue.append(line);
			}
		}
		putField(fields, currentKey, currentValue);
		return fields;
	}

	private Map<String, String> parseLegacyDelimitedStructuredFields(String payload) {
		Map<String, String> fields = new LinkedHashMap<>();
		putField(fields, "错误类型", extractBetween(payload, ERROR_TYPE_KEY, PROBLEM_DESCRIPTION_KEY));
		putField(fields, "问题描述", extractBetween(payload, PROBLEM_DESCRIPTION_KEY, TYPICAL_ERROR_KEY));
		putField(fields, "典型错误", extractBetween(payload, TYPICAL_ERROR_KEY, SOLUTION_KEY));
		putField(fields, "解决方案", extractBetween(payload, SOLUTION_KEY, null));
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
		String trimmed = StringUtils.defaultString(value).trim();
		while (trimmed.startsWith("|")) {
			trimmed = trimmed.substring(1).trim();
		}
		while (trimmed.endsWith("|")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return trimmed;
	}

	private void putField(Map<String, String> fields, String key, Object value) {
		if (key == null) {
			return;
		}
		String normalizedValue = StringUtils.defaultString(value == null ? null : value.toString()).trim();
		if (!normalizedValue.isEmpty()) {
			fields.put(key, normalizedValue);
		}
	}

	private List<String> parseSolutions(String rawSolutions) {
		if (StringUtils.isBlank(rawSolutions)) {
			return List.of();
		}

		String normalized = rawSolutions.trim();
		String[] parts = normalized.split("\\s*(?=\\d+\\.)");
		List<String> results = new ArrayList<>();
		for (String part : parts) {
			String item = part.replaceFirst("^\\d+\\.", "").trim();
			if (!item.isEmpty()) {
				results.add(item);
			}
		}
		if (results.isEmpty() && !normalized.isEmpty()) {
			results.add(normalized);
		}
		return results;
	}

	private Optional<SqlSemanticLearningCard> buildOnlyFullGroupByCard(String errorMessage, String sql) {
		String normalizedMessage = StringUtils.lowerCase(errorMessage);
		if (!normalizedMessage.contains("only_full_group_by")
				&& !normalizedMessage.contains("not in group by clause")) {
			return Optional.empty();
		}

		return Optional.of(SqlSemanticLearningCard.builder()
			.errorType(ONLY_FULL_GROUP_BY_ERROR_TYPE)
			.problemDescription("聚合查询的 SELECT 列表中包含未聚合且未出现在 GROUP BY 中的字段，在 sql_mode=only_full_group_by 下无法执行。")
			.typicalWrongSql(StringUtils.defaultString(sql))
			.solutions(List.of("将 SELECT 中未聚合的字段补充到 GROUP BY 子句中",
					"或对该字段使用聚合函数，例如 MIN、MAX、ANY_VALUE",
					"如果不需要聚合结果，移除 GROUP BY 或改写为明细查询"))
			.build());
	}

	private SqlSemanticLearningCard buildGenericExecutionCard(String errorMessage, String sql) {
		return SqlSemanticLearningCard.builder()
			.errorType("SQL_EXECUTION_ERROR")
			.problemDescription("SQL 执行失败，原始报错：" + errorMessage.trim())
			.typicalWrongSql(StringUtils.defaultString(sql))
			.solutions(List.of("根据数据库报错修正 SQL 语法、字段、函数或分组条件",
					"对照当前表结构确认字段存在且数据类型匹配",
					"如果是聚合查询，重点检查 SELECT、GROUP BY 与聚合函数是否一致"))
			.build();
	}

	private String normalizeText(String text) {
		if (StringUtils.isBlank(text)) {
			return "";
		}
		return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

}
