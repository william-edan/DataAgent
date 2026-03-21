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

import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorAnalysis;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SqlErrorAnalyzer {

	private static final List<Pattern> TABLE_PATTERNS = List.of(
			Pattern.compile("(?i)table ['\"`]?([^'\"`\\s]+)['\"`]? doesn't exist"),
			Pattern.compile("(?i)relation ['\"`]?([^'\"`\\s]+)['\"`]? does not exist"),
			Pattern.compile("(?i)unknown table ['\"`]?([^'\"`\\s]+)['\"`]?"));

	private static final List<Pattern> COLUMN_PATTERNS = List.of(
			Pattern.compile("(?i)unknown column ['\"`]?([^'\"`\\s]+)['\"`]?"),
			Pattern.compile("(?i)column ['\"`]?([^'\"`\\s]+)['\"`]? does not exist"));

	public SqlErrorAnalysis analyze(String errorMessage) {
		if (StringUtils.isBlank(errorMessage)) {
			return SqlErrorAnalysis.unknown(errorMessage);
		}
		for (Pattern pattern : TABLE_PATTERNS) {
			String wrongObject = findObject(pattern, errorMessage);
			if (wrongObject != null) {
				return new SqlErrorAnalysis(SqlErrorType.TABLE_NOT_EXIST, wrongObject, errorMessage);
			}
		}
		for (Pattern pattern : COLUMN_PATTERNS) {
			String wrongObject = findObject(pattern, errorMessage);
			if (wrongObject != null) {
				return new SqlErrorAnalysis(SqlErrorType.COLUMN_NOT_EXIST, wrongObject, errorMessage);
			}
		}
		String normalized = errorMessage.toLowerCase(Locale.ROOT);
		if (normalized.contains("syntax error") || normalized.contains("you have an error in your sql syntax")) {
			return new SqlErrorAnalysis(SqlErrorType.SYNTAX_ERROR, null, errorMessage);
		}
		return SqlErrorAnalysis.unknown(errorMessage);
	}

	private String findObject(Pattern pattern, String errorMessage) {
		Matcher matcher = pattern.matcher(errorMessage);
		if (!matcher.find()) {
			return null;
		}
		String candidate = matcher.group(1);
		if (candidate == null) {
			return null;
		}
		int lastDot = candidate.lastIndexOf('.');
		return lastDot >= 0 ? candidate.substring(lastDot + 1) : candidate;
	}

}
