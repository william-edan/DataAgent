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
package com.alibaba.cloud.ai.dataagent.service.query;

import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import com.alibaba.cloud.ai.dataagent.vo.QueryResultVO;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

public interface QueryService {

	/**
	 * 同步查询方法（向后兼容）
	 * @param agentId 代理ID
	 * @param naturalQuery 自然语言查询
	 * @return 查询结果
	 * @throws GraphRunnerException 图执行异常
	 */
	QueryResultVO query(String agentId, String naturalQuery) throws GraphRunnerException;

	/**
	 * 流式查询方法，通过SSE推送查询过程和结果
	 * @param sink SSE事件流的Sink
	 * @param agentId 代理ID
	 * @param naturalQuery 自然语言查询
	 */
	void queryStream(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId, String sessionId,
			String threadId, String naturalQuery);

}
