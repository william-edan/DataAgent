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

import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SummaryResult;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class SummaryService {

	private final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	public SummaryResult summarize(String content) {
		if (StringUtils.isBlank(content)) {
			return SummaryResult.empty();
		}
		String prompt = """
				Summarize the following conversation and return JSON only.
				Required keys:
				- summary: string
				- keyPoints: string array
				- userIntent: string
				- importantContext: string array

				Conversation:
				%s
				""".formatted(content);
		String response = llmService.toStringFlux(llmService.callUser(prompt))
			.collect(StringBuilder::new, StringBuilder::append)
			.map(StringBuilder::toString)
			.block();
		if (StringUtils.isBlank(response)) {
			return SummaryResult.empty();
		}
		return jsonParseUtil.tryConvertToObject(response, SummaryResult.class);
	}

}
