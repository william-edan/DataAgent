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

import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import com.alibaba.cloud.ai.dataagent.vo.QueryResultVO;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_SQL_RESULT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_COMPLETE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_ERROR;

@Slf4j
@Service
public class QueryServiceImpl implements QueryService {

	private final CompiledGraph compiledGraph;

	public QueryServiceImpl(@Qualifier("queryGraph") StateGraph queryGraph) throws GraphStateException {
		this.compiledGraph = queryGraph.compile(CompileConfig.builder().build());
	}

	@Override
	public QueryResultVO query(String agentId, String naturalQuery) throws GraphRunnerException {
		OverAllState state = compiledGraph
			.invoke(Map.of(INPUT_KEY, naturalQuery, AGENT_ID, agentId, MULTI_TURN_CONTEXT, ""),
					RunnableConfig.builder().build())
			.orElseThrow();

		ResultSetBO resultSet = StateUtil.getObjectValue(state, QUERY_SQL_RESULT, ResultSetBO.class);
		String sql = StateUtil.getStringValue(state, QUERY_SQL, "");

		return QueryResultVO.builder().sql(sql).resultSet(resultSet).build();
	}

	@Override
	public void queryStream(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId,
			String naturalQuery) {
		String threadId = UUID.randomUUID().toString();
		log.info("Starting query stream for agentId: {}, threadId: {}, query: '{}'", agentId, threadId, naturalQuery);

		try {
			// 用于跟踪当前文本类型
			AtomicReference<TextType> currentTextType = new AtomicReference<>(null);

			// 创建查询图的流式输出
			Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(
					Map.of(INPUT_KEY, naturalQuery, AGENT_ID, agentId, MULTI_TURN_CONTEXT, ""),
					RunnableConfig.builder().threadId(threadId).build());

			// 订阅并处理节点输出
			nodeOutputFlux.subscribe(output -> handleNodeOutput(sink, agentId, threadId, output, currentTextType),
					error -> handleStreamError(sink, agentId, threadId, error),
					() -> handleStreamComplete(sink, agentId, threadId));

		}
		catch (Exception e) {
			log.error("Failed to start query stream for threadId: {}", threadId, e);
			handleStreamError(sink, agentId, threadId, e);
		}
	}

	/**
	 * 处理节点输出
	 */
	private void handleNodeOutput(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId, String threadId,
			NodeOutput output, AtomicReference<TextType> currentTextType) {
		if (output instanceof StreamingOutput streamingOutput) {
			handleStreamingOutput(sink, agentId, threadId, streamingOutput, currentTextType);
		}
	}

	/**
	 * 处理流式输出
	 */
	private void handleStreamingOutput(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId,
			String threadId, StreamingOutput output, AtomicReference<TextType> currentTextType) {
		String node = output.node();
		String chunk = output.chunk();

		if (chunk == null || chunk.isEmpty()) {
			return;
		}

		// 处理文本类型标记
		TextType textType = determineTextType(chunk, currentTextType);
		boolean isTypeSign = isTextTypeSignal(chunk, currentTextType.get(), textType);
		currentTextType.set(textType);

		// 文本标记符号不返回给前端
		if (!isTypeSign) {
			GraphNodeResponse response = GraphNodeResponse.builder()
				.agentId(agentId)
				.threadId(threadId)
				.nodeName(node)
				.text(chunk)
				.textType(textType)
				.build();

			Sinks.EmitResult result = sink.tryEmitNext(ServerSentEvent.builder(response).build());
			if (result.isFailure()) {
				log.warn("Failed to emit data to sink for threadId: {}, result: {}", threadId, result);
			}
		}
	}

	/**
	 * 确定文本类型
	 */
	private TextType determineTextType(String chunk, AtomicReference<TextType> currentTextType) {
		TextType originType = currentTextType.get();
		if (originType == null) {
			return TextType.getTypeByStratSign(chunk);
		}
		else {
			return TextType.getType(originType, chunk);
		}
	}

	/**
	 * 判断是否是文本类型标记符号
	 */
	private boolean isTextTypeSignal(String chunk, TextType originType, TextType newType) {
		if (originType == null) {
			return newType != TextType.TEXT;
		}
		else {
			return newType != originType;
		}
	}

	/**
	 * 处理流式错误
	 */
	private void handleStreamError(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId,
			String threadId, Throwable error) {
		log.error("Error in query stream processing for threadId: {}", threadId, error);

		if (sink != null && sink.currentSubscriberCount() > 0) {
			sink.tryEmitNext(ServerSentEvent
				.builder(GraphNodeResponse.error(agentId, threadId, "Query processing error: " + error.getMessage()))
				.event(STREAM_EVENT_ERROR)
				.build());
			sink.tryEmitComplete();
		}
	}

	/**
	 * 处理流式完成
	 */
	private void handleStreamComplete(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId,
			String threadId) {
		log.info("Query stream processing completed successfully for threadId: {}", threadId);

		if (sink != null && sink.currentSubscriberCount() > 0) {
			sink.tryEmitNext(
					ServerSentEvent.builder(GraphNodeResponse.complete(agentId, threadId)).event(STREAM_EVENT_COMPLETE).build());
			sink.tryEmitComplete();
		}
	}

}
