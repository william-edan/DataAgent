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

import com.alibaba.cloud.ai.dataagent.entity.ChatMessage;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatMessageService;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SummaryResult;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class ConversationMemoryCoordinator {

	private final ChatMessageService chatMessageService;

	private final SummaryService summaryService;

	private final LongTermMemoryService longTermMemoryService;

	private final DataAgentProperties properties;

	public void finalizeTurn(String agentId, String sessionId, String threadId, Long userId) {
		finalizeTurn(agentId, sessionId, threadId, userId, null);
	}

	public void finalizeTurn(String agentId, String sessionId, String threadId, Long userId,
			String runtimeConversation) {
		if (StringUtils.isAnyBlank(agentId, threadId)) {
			return;
		}
		if (StringUtils.isNotBlank(sessionId)) {
			List<ChatMessage> recentMessages = chatMessageService.findRecentBySessionId(sessionId,
					properties.getMemory().getRecentMessageLimit());
			if (recentMessages.size() < properties.getMemory().getSummaryTriggerMessageCount()) {
				return;
			}
			summarizeAndUpdate(agentId, sessionId, threadId, userId, renderConversation(recentMessages));
			return;
		}

		if (StringUtils.isBlank(runtimeConversation)) {
			return;
		}
		summarizeAndUpdate(agentId, null, threadId, userId, runtimeConversation);
	}

	private void summarizeAndUpdate(String agentId, String sessionId, String threadId, Long userId,
			String conversationText) {
		SummaryResult summaryResult = summaryService.summarize(conversationText);
		if (summaryResult == null) {
			return;
		}
		longTermMemoryService.updateMemory(agentId, sessionId, threadId, userId, summaryResult);
	}

	private String renderConversation(List<ChatMessage> recentMessages) {
		return recentMessages.stream()
			.map(message -> StringUtils.defaultIfBlank(message.getRole(), "unknown").toUpperCase() + ": "
					+ StringUtils.defaultString(message.getContent()).trim())
			.reduce((left, right) -> left + "\n" + right)
			.orElse("");
	}

}
