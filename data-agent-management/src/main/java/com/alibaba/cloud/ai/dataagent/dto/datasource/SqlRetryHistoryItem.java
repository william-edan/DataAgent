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
package com.alibaba.cloud.ai.dataagent.dto.datasource;

/**
 * Represents a single retry attempt in the SQL generation history. Used to track previous
 * SQL attempts and their failure reasons to help LLM avoid repeating the same mistakes.
 *
 * @param attemptNumber The attempt number (1-based)
 * @param sql The SQL that was generated in this attempt
 * @param failureReason The reason why this SQL failed (semantic or execution error)
 * @param failureType The type of failure: "semantic" or "execution"
 */
public record SqlRetryHistoryItem(int attemptNumber, String sql, String failureReason, String failureType) {

	public static SqlRetryHistoryItem semantic(int attemptNumber, String sql, String reason) {
		return new SqlRetryHistoryItem(attemptNumber, sql, reason, "semantic");
	}

	public static SqlRetryHistoryItem execution(int attemptNumber, String sql, String reason) {
		return new SqlRetryHistoryItem(attemptNumber, sql, reason, "execution");
	}

	/**
	 * Format this history item for display in prompt
	 */
	public String toPromptFormat() {
		return String.format("### 尝试 #%d\nSQL: %s\n失败原因: %s", attemptNumber, sql, failureReason);
	}

}
