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
import com.alibaba.cloud.ai.dataagent.service.memory.ConversationMemoryCoordinator;
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
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_SQL_RESULT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SESSION_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_COMPLETE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_ERROR;

@Slf4j
@Service
public class QueryServiceImpl implements QueryService {

	private final CompiledGraph compiledGraph;

	private final ConversationMemoryCoordinator conversationMemoryCoordinator;

	public QueryServiceImpl(@Qualifier("queryGraph") StateGraph queryGraph,
			ConversationMemoryCoordinator conversationMemoryCoordinator) throws GraphStateException {
		this.compiledGraph = queryGraph.compile(CompileConfig.builder().build());
		this.conversationMemoryCoordinator = conversationMemoryCoordinator;
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
	public void queryStream(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId, String sessionId,
			String threadId, String naturalQuery) {
		String effectiveThreadId = StringUtils.hasText(threadId) ? threadId : UUID.randomUUID().toString();
		log.info("Starting query stream for agentId: {}, threadId: {}, query: '{}'", agentId, effectiveThreadId,
				naturalQuery);

		try {
			AtomicReference<TextType> currentTextType = new AtomicReference<>(null);
			StringBuilder assistantResponse = new StringBuilder();
			Map<String, Object> state = new HashMap<>();
			state.put(INPUT_KEY, naturalQuery);
			state.put(AGENT_ID, agentId);
			state.put(MULTI_TURN_CONTEXT, "");
			if (StringUtils.hasText(sessionId)) {
				state.put(SESSION_ID, sessionId);
			}

			Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(state,
					RunnableConfig.builder().threadId(effectiveThreadId).build());

			nodeOutputFlux.subscribe(
					output -> handleNodeOutput(sink, agentId, effectiveThreadId, output, currentTextType, assistantResponse),
					error -> handleStreamError(sink, agentId, effectiveThreadId, error),
					() -> handleStreamComplete(sink, agentId, effectiveThreadId, sessionId,
							renderRuntimeConversation(naturalQuery, assistantResponse.toString())));

		}
		catch (Exception e) {
			log.error("Failed to start query stream for threadId: {}", effectiveThreadId, e);
			handleStreamError(sink, agentId, effectiveThreadId, e);
		}
	}

	private void handleNodeOutput(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId, String threadId,
			NodeOutput output, AtomicReference<TextType> currentTextType, StringBuilder assistantResponse) {
		if (output instanceof StreamingOutput streamingOutput) {
			handleStreamingOutput(sink, agentId, threadId, streamingOutput, currentTextType, assistantResponse);
		}
	}

	private void handleStreamingOutput(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId,
			String threadId, StreamingOutput output, AtomicReference<TextType> currentTextType,
			StringBuilder assistantResponse) {
		String node = output.node();
		String chunk = output.chunk();

		if (chunk == null || chunk.isEmpty()) {
			return;
		}

		TextType textType = determineTextType(chunk, currentTextType);
		boolean isTypeSign = isTextTypeSignal(chunk, currentTextType.get(), textType);
		currentTextType.set(textType);

		if (!isTypeSign) {
			assistantResponse.append(chunk);
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

	private TextType determineTextType(String chunk, AtomicReference<TextType> currentTextType) {
		TextType originType = currentTextType.get();
		if (originType == null) {
			return TextType.getTypeByStratSign(chunk);
		}
		return TextType.getType(originType, chunk);
	}

	private boolean isTextTypeSignal(String chunk, TextType originType, TextType newType) {
		if (originType == null) {
			return newType != TextType.TEXT;
		}
		return newType != originType;
	}

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

	private void handleStreamComplete(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, String agentId,
			String threadId, String sessionId, String runtimeConversation) {
		log.info("Query stream processing completed successfully for threadId: {}", threadId);
		conversationMemoryCoordinator.finalizeTurn(agentId, sessionId, threadId, null, runtimeConversation);

		if (sink != null && sink.currentSubscriberCount() > 0) {
			sink.tryEmitNext(ServerSentEvent.builder(GraphNodeResponse.complete(agentId, threadId))
				.event(STREAM_EVENT_COMPLETE)
				.build());
			sink.tryEmitComplete();
		}
	}

	private String renderRuntimeConversation(String userInput, String assistantOutput) {
		StringBuilder conversation = new StringBuilder();
		if (StringUtils.hasText(userInput)) {
			conversation.append("USER: ").append(userInput.trim());
		}
		if (StringUtils.hasText(assistantOutput)) {
			if (conversation.length() > 0) {
				conversation.append('\n');
			}
			conversation.append("ASSISTANT: ").append(assistantOutput.trim());
		}
		return conversation.toString();
	}

}
