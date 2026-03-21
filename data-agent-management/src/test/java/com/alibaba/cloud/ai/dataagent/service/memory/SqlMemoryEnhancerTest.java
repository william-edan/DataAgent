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
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorAnalysis;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorCase;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorType;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlSuccessCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMemoryEnhancerTest {

	@Test
	void buildSqlAdviceOnlyInjectsRawSqlForErrorCases() {
		DataAgentProperties properties = new DataAgentProperties();
		SqlMemoryEnhancer enhancer = new SqlMemoryEnhancer(properties);

		String advice = enhancer.enhance(successCases(5), errorCases(4),
				List.of("Compacted 8 SQL success cases across users and orders"),
				List.of("Compacted 6 SQL error cases about missing date filters"));

		assertTrue(advice.contains("Successful patterns"));
		assertTrue(advice.contains("Compacted 8 SQL success cases"));
		assertTrue(advice.contains("Compacted 6 SQL error cases"));
		assertTrue(advice.contains("Matched question: question-0"));
		assertTrue(advice.contains("Tables: users"));
		assertTrue(!advice.contains("SUCCESS_SELECT_0"));
		assertTrue(countOccurrences(advice, "SQL:") <= 2);
	}

	private List<SqlSuccessCase> successCases(int count) {
		return java.util.stream.IntStream.range(0, count)
			.mapToObj(index -> new SqlSuccessCase("success-" + index, "question-" + index, "SUCCESS_SELECT_" + index,
					List.of("users"), "session-1", null, index + 1, Instant.now().minusSeconds(index)))
			.toList();
	}

	private List<SqlErrorCase> errorCases(int count) {
		return java.util.stream.IntStream.range(0, count)
			.mapToObj(index -> new SqlErrorCase("error-" + index, "question-" + index, "ERROR_SELECT_" + index,
					"Unknown column", new SqlErrorAnalysis(SqlErrorType.COLUMN_NOT_EXIST, "col_" + index,
							"Unknown column"),
					"session-1", null, index + 1, Instant.now().minusSeconds(index)))
			.toList();
	}

	private int countOccurrences(String value, String token) {
		int count = 0;
		int position = 0;
		while ((position = value.indexOf(token, position)) >= 0) {
			count++;
			position += token.length();
		}
		return count;
	}

}
