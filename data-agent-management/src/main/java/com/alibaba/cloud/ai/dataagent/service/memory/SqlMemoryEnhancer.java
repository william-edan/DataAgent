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
package com.alibaba.cloud.ai.dataagent.service.memory;

import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorCase;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlSuccessCase;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SqlMemoryEnhancer {

	private static final int MAX_SUCCESS_CASES = 3;

	private static final int MAX_ERROR_CASES = 2;

	private final int maxChars;

	public SqlMemoryEnhancer(DataAgentProperties properties) {
		this.maxChars = properties.getMemory().getSqlAdviceSectionMaxChars();
	}

	public String enhance(List<SqlSuccessCase> successCases, List<SqlErrorCase> errorCases,
			List<String> successSummaries, List<String> errorSummaries) {
		StringBuilder advice = new StringBuilder();
		appendSuccessPatterns(advice,
				successCases == null ? List.of() : successCases.stream().limit(MAX_SUCCESS_CASES).toList());
		appendSummaryLines(advice, "Compacted success patterns", successSummaries);
		appendErrorCases(advice, errorCases == null ? List.of() : errorCases.stream().limit(MAX_ERROR_CASES).toList());
		appendSummaryLines(advice, "Compacted error patterns", errorSummaries);
		String content = advice.toString().trim();
		if (content.isEmpty()) {
			return "";
		}
		return truncate(content, maxChars);
	}

	private void appendSuccessPatterns(StringBuilder advice, List<SqlSuccessCase> successCases) {
		if (successCases.isEmpty()) {
			return;
		}
		advice.append("Successful patterns\n");
		for (SqlSuccessCase successCase : successCases) {
			advice.append("- Tables: ").append(String.join(", ", successCase.tables())).append("\n");
			advice.append("  Matched question: ").append(successCase.question()).append("\n");
		}
	}

	private void appendErrorCases(StringBuilder advice, List<SqlErrorCase> errorCases) {
		if (errorCases.isEmpty()) {
			return;
		}
		if (!advice.isEmpty()) {
			advice.append("\n");
		}
		advice.append("Previous error cases\n");
		for (SqlErrorCase errorCase : errorCases) {
			advice.append("- SQL: ").append(errorCase.sql()).append("\n");
			advice.append("  Error: ").append(errorCase.errorMessage()).append("\n");
		}
	}

	private void appendSummaryLines(StringBuilder advice, String title, List<String> summaries) {
		List<String> lines = summaries == null ? List.of() : summaries.stream().filter(StringUtils::isNotBlank).toList();
		if (lines.isEmpty()) {
			return;
		}
		if (!advice.isEmpty()) {
			advice.append("\n");
		}
		advice.append(title).append("\n");
		for (String summary : lines) {
			advice.append("- ").append(summary).append("\n");
		}
	}

	private String truncate(String value, int limit) {
		if (StringUtils.isBlank(value) || limit <= 0 || value.length() <= limit) {
			return StringUtils.defaultString(value);
		}
		if (limit <= 3) {
			return value.substring(0, limit);
		}
		return value.substring(0, limit - 3) + "...";
	}

}
