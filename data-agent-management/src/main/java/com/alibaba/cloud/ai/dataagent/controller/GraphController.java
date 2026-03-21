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

import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
import com.alibaba.cloud.ai.dataagent.dto.QueryTypeResult;
import com.alibaba.cloud.ai.dataagent.enums.QueryType;
import com.alibaba.cloud.ai.dataagent.service.classifier.QueryTypeClassifier;
import com.alibaba.cloud.ai.dataagent.service.graph.GraphService;
import com.alibaba.cloud.ai.dataagent.service.query.QueryService;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_COMPLETE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_ERROR;

/**
 * @author zhangshenghang
 * @author vlsmb
 */
@Slf4j
@RestController
@AllArgsConstructor
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class GraphController {

	private final GraphService graphService;

	private final QueryService queryService;

	private final QueryTypeClassifier queryTypeClassifier;

	@GetMapping(value = "/stream/search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphNodeResponse>> streamSearch(@RequestParam("agentId") String agentId,
			@RequestParam(value = "sessionId", required = false) String sessionId,
			@RequestParam(value = "threadId", required = false) String threadId, @RequestParam("query") String query,
			@RequestParam(value = "humanFeedback", required = false) boolean humanFeedback,
			@RequestParam(value = "humanFeedbackContent", required = false) String humanFeedbackContent,
			@RequestParam(value = "rejectedPlan", required = false) boolean rejectedPlan,
			@RequestParam(value = "nl2sqlOnly", required = false) boolean nl2sqlOnly, HttpServletResponse response) {
		// Set SSE-related HTTP headers
		response.setCharacterEncoding("UTF-8");
		response.setContentType("text/event-stream");
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Headers", "Cache-Control");

		Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();

		// 构建 GraphRequest（可能用于 COMPLEX 路径）
		GraphRequest request = GraphRequest.builder()
			.agentId(agentId)
			.sessionId(sessionId)
			.threadId(threadId)
			.query(query)
			.humanFeedback(humanFeedback)
			.humanFeedbackContent(humanFeedbackContent)
			.rejectedPlan(rejectedPlan)
			.nl2sqlOnly(nl2sqlOnly)
			.build();

		// 标记是否使用 SIMPLE 路径
		boolean useSimplePath = false;

		// 智能路由：仅对新请求进行分类，人工反馈请求直接走完整流程
		if (humanFeedbackContent == null || humanFeedbackContent.isEmpty()) {
			// 分类查询类型
			long classifyStartTime = System.currentTimeMillis();
			QueryTypeResult classificationResult;

			try {
				classificationResult = queryTypeClassifier.classify(query);
			}
			catch (Exception e) {
				log.warn("Query classification failed for query: '{}', fallback to COMPLEX. Error: {}", query,
						e.getMessage());
				classificationResult = QueryTypeResult.builder()
					.queryType(QueryType.COMPLEX)
					.reason("分类失败，保守降级")
					.build();
			}

			long classifyDuration = System.currentTimeMillis() - classifyStartTime;
			QueryType queryType = classificationResult.getQueryType();

			// 记录分类决策
			log.info("Query classified: type={}, query='{}', reason='{}', classification_latency={}ms", queryType,
					query, classificationResult.getReason(), classifyDuration);

			// 根据分类结果路由
			if (queryType == QueryType.SIMPLE) {
				log.info("Routing to SIMPLE query path for query: '{}'", query);
				useSimplePath = true;
				queryService.queryStream(sink, agentId, sessionId, threadId, query);
			}
			else {
				log.info("Routing to COMPLEX analysis path for query: '{}'", query);
				graphService.graphStreamProcess(sink, request);
			}
		}
		else {
			// 人工反馈请求直接走完整流程
			log.info("Human feedback detected, routing to COMPLEX analysis path");
			graphService.graphStreamProcess(sink, request);
		}

		// 构建返回的 Flux，根据路径类型决定是否需要清理操作
		final boolean isSimplePath = useSimplePath;
		return sink.asFlux().filter(sse -> {
			// 1. 如果 event 是 "complete" 或 "error"，直接放行（不管 text 是否为空）
			if (STREAM_EVENT_COMPLETE.equals(sse.event()) || STREAM_EVENT_ERROR.equals(sse.event())) {
				return true;
			}
			// 判断字符串是否为空
			return sse.data() != null && sse.data().getText() != null && !sse.data().getText().isEmpty();
		})
			.doOnSubscribe(subscription -> log.info("Client subscribed to stream, agentId: {}", agentId))
			.doOnCancel(() -> {
				if (!isSimplePath && request.getThreadId() != null) {
					log.info("Client disconnected from stream, threadId: {}", request.getThreadId());
					graphService.stopStreamProcessing(request.getThreadId());
				}
			})
			.doOnError(e -> {
				if (!isSimplePath && request.getThreadId() != null) {
					log.error("Error occurred during streaming, threadId: {}: ", request.getThreadId(), e);
					graphService.stopStreamProcessing(request.getThreadId());
				}
			})
			.doOnComplete(() -> {
				if (!isSimplePath) {
					log.info("Stream completed successfully, threadId: {}", request.getThreadId());
				}
				else {
					log.info("Stream completed successfully (simple path), agentId: {}", agentId);
				}
			});
	}

}
