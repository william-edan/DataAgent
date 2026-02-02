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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.QueryRequest;
import com.alibaba.cloud.ai.dataagent.service.query.QueryService;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import com.alibaba.cloud.ai.dataagent.vo.QueryResultVO;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * Query-only API for direct data lookup.
 */
@RestController
@AllArgsConstructor
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class QueryController {

	private final QueryService queryService;

	@PostMapping("/query")
	public ApiResponse<QueryResultVO> query(@Valid @RequestBody QueryRequest request) throws GraphRunnerException {
		if (!StringUtils.hasText(request.getAgentId()) || !StringUtils.hasText(request.getQuery())) {
			return ApiResponse.error("agentId/query cannot be empty");
		}
		QueryResultVO result = queryService.query(request.getAgentId(), request.getQuery());
		return ApiResponse.success("查询成功", result);
	}
}
