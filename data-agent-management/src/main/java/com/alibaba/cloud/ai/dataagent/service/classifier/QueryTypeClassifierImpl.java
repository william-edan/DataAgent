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
package com.alibaba.cloud.ai.dataagent.service.classifier;

import com.alibaba.cloud.ai.dataagent.dto.QueryTypeResult;
import com.alibaba.cloud.ai.dataagent.enums.QueryType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptConstant;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 查询类型分类器实现
 * <p>
 * 使用LLM快速判断用户查询应该走简单查询路径还是复杂分析路径
 *
 * @author Claude Code
 * @since 2026-02-02
 */
@Slf4j
@Service
@AllArgsConstructor
public class QueryTypeClassifierImpl implements QueryTypeClassifier {

	private final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	/**
	 * 分类超时时间（毫秒）
	 */
	private static final long CLASSIFICATION_TIMEOUT_MS = 3000;

	@Override
	public QueryTypeResult classify(String query) {
		long startTime = System.currentTimeMillis();

		try {
			// 构建prompt
			String prompt = buildClassifierPrompt(query);
			log.debug("Query type classifier prompt: {}", prompt);

			// 调用LLM进行分类
			Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

			// 同步获取结果，设置超时
			String resultJson = llmService.blockToString(responseFlux);

			long duration = System.currentTimeMillis() - startTime;
			log.debug("Classification completed in {}ms, result: {}", duration, resultJson);

			// 解析JSON结果
			QueryTypeResult result = jsonParseUtil.tryConvertToObject(resultJson, QueryTypeResult.class);

			// 如果解析失败或结果为空，使用保守策略
			if (result == null || result.getQueryType() == null) {
				log.warn("Failed to parse classification result, falling back to COMPLEX");
				return QueryTypeResult.builder()
					.queryType(QueryType.COMPLEX)
					.reason("分类结果解析失败，保守降级")
					.build();
			}

			log.info("Query classified: type={}, query='{}', reason='{}', latency={}ms", result.getQueryType(), query,
					result.getReason(), duration);

			return result;
		}
		catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			log.warn("Query classification failed after {}ms, fallback to COMPLEX. Query: '{}', Error: {}", duration,
					query, e.getMessage());

			// 失败时保守降级为COMPLEX
			return QueryTypeResult.builder()
				.queryType(QueryType.COMPLEX)
				.reason("分类失败，保守降级: " + e.getMessage())
				.build();
		}
	}

	/**
	 * 构建分类器prompt
	 * @param userQuery 用户查询
	 * @return prompt文本
	 */
	private String buildClassifierPrompt(String userQuery) {
		PromptTemplate template = PromptConstant.getQueryTypeClassifierPromptTemplate();
		Map<String, Object> params = new HashMap<>();
		params.put("userQuery", userQuery);
		return template.render(params);
	}

}
