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
package com.alibaba.cloud.ai.dataagent.dto.prompt;

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryHistoryItem;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@AllArgsConstructor
@Builder
@Data
public class SqlGenerationDTO {

	private String evidence;

	private String query;

	private SchemaDTO schemaDTO;

	private String sql;

	private String exceptionMessage;

	private String executionDescription;

	private String dialect;

	/**
	 * History of previous retry attempts. Used to help LLM avoid repeating the same
	 * mistakes.
	 */
	private List<SqlRetryHistoryItem> retryHistory;

	/**
	 * 查询契约。将用户问题中的显式约束固化下来，避免 SQL 生成时自行猜测。
	 */
	private String queryContract;

	/**
	 * 语义重试护栏。仅在语义校验失败后使用，约束本轮重试的必须项和禁止项。
	 */
	private String retryGuardrails;

}
