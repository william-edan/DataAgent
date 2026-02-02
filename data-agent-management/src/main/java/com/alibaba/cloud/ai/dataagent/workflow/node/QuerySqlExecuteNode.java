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
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_SQL_RESULT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;

/**
 * Query-only SQL execution node that runs SQL synchronously and returns result set.
 */
@Slf4j
@Component
@AllArgsConstructor
public class QuerySqlExecuteNode implements NodeAction {

	private final DatabaseUtil databaseUtil;

	private final Nl2SqlService nl2SqlService;

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

		log.info("Query SQL executed successfully, rows: {}",
				resultSetBO.getData() != null ? resultSetBO.getData().size() : 0);

		return Map.of(QUERY_SQL, sqlQuery, QUERY_SQL_RESULT, resultSetBO, SQL_RESULT_LIST_MEMORY, resultSetBO.getData(),
				Constant.RESULT, resultSetBO);
	}
}
