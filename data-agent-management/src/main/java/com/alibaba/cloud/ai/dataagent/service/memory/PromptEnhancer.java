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
import com.alibaba.cloud.ai.dataagent.service.memory.model.AgentMemoryDocument;
import com.alibaba.cloud.ai.dataagent.service.memory.model.MemoryEntry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PromptEnhancer {

	private final DataAgentProperties.Memory memoryProperties;

	public PromptEnhancer(DataAgentProperties properties) {
		this.memoryProperties = properties.getMemory();
	}

	public String enhance(List<ChatMessage> recentMessages, String multiTurnContext, AgentMemoryDocument memory,
			String userInput) {
		String recentConversation = truncate(renderRecentConversation(recentMessages, multiTurnContext),
				memoryProperties.getConversationSectionMaxChars());
		String longTermMemory = truncate(renderLongTermMemory(memory), memoryProperties.getLongTermSectionMaxChars());
		return """
				# Recent Conversation
				%s

				# Long-Term Memory
				%s

				# Current User Input
				%s
				""".formatted(defaultIfBlank(recentConversation, "(none)"), defaultIfBlank(longTermMemory, "(none)"),
				defaultIfBlank(userInput, "(none)"));
	}

	private String renderRecentConversation(List<ChatMessage> recentMessages, String multiTurnContext) {
		List<String> sections = new ArrayList<>();
		if (recentMessages != null) {
			recentMessages.stream()
				.filter(message -> StringUtils.isNotBlank(message.getContent()))
				.map(message -> StringUtils.defaultIfBlank(message.getRole(), "unknown").toUpperCase() + ": "
						+ message.getContent().trim())
				.forEach(sections::add);
		}
		if (StringUtils.isNotBlank(multiTurnContext)) {
			sections.add("MULTI_TURN_CONTEXT:\n" + multiTurnContext.trim());
		}
		return String.join("\n", sections);
	}

	private String renderLongTermMemory(AgentMemoryDocument memoryDocument) {
		AgentMemoryDocument memory = memoryDocument == null ? AgentMemoryDocument.empty() : memoryDocument.withDefaults();
		List<String> sections = new ArrayList<>();
		appendMemorySection(sections, "Facts", memory.facts());
		appendMemorySection(sections, "Preferences", memory.preferences());
		appendMemorySection(sections, "Tasks", memory.tasks());
		appendMemorySection(sections, "Context", memory.context());
		return String.join("\n", sections);
	}

	private void appendMemorySection(List<String> sections, String title, List<MemoryEntry> entries) {
		if (entries == null || entries.isEmpty()) {
			return;
		}
		String renderedEntries = entries.stream().map(MemoryEntry::content).map(content -> "- " + content).reduce((left, right) -> left + "\n" + right).orElse("");
		sections.add("## " + title + "\n" + renderedEntries);
	}

	private String truncate(String value, int maxChars) {
		if (StringUtils.isBlank(value) || maxChars <= 0 || value.length() <= maxChars) {
			return StringUtils.trimToEmpty(value);
		}
		if (maxChars <= 3) {
			return value.substring(0, maxChars);
		}
		return value.substring(0, maxChars - 3) + "...";
	}

	private String defaultIfBlank(String value, String fallback) {
		return StringUtils.defaultIfBlank(StringUtils.trimToEmpty(value), fallback);
	}

}
