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

import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.memory.model.AgentMemoryDocument;
import com.alibaba.cloud.ai.dataagent.service.memory.model.MemoryEntry;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SummaryResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class LongTermMemoryService {

	private final MemoryFileStore memoryFileStore;

	private final Path memoryRootPath;

	private final int maxEntriesPerCategory;

	private final MemoryCompactionService memoryCompactionService;

	@Autowired
	public LongTermMemoryService(MemoryFileStore memoryFileStore, DataAgentProperties properties,
			MemoryCompactionService memoryCompactionService) {
		this(memoryFileStore, Paths.get(properties.getMemory().getPath()),
				properties.getMemory().getMaxEntriesPerCategory(), memoryCompactionService);
	}

	LongTermMemoryService(MemoryFileStore memoryFileStore, Path memoryRootPath) {
		this(memoryFileStore, memoryRootPath, 100, new MemoryCompactionService(new DataAgentProperties()));
	}

	LongTermMemoryService(MemoryFileStore memoryFileStore, Path memoryRootPath, int maxEntriesPerCategory,
			MemoryCompactionService memoryCompactionService) {
		this.memoryFileStore = memoryFileStore;
		this.memoryRootPath = memoryRootPath;
		this.maxEntriesPerCategory = maxEntriesPerCategory;
		this.memoryCompactionService = memoryCompactionService;
	}

	public AgentMemoryDocument load(String agentId) {
		return memoryFileStore.read(memoryFilePath(agentId), AgentMemoryDocument.class, AgentMemoryDocument::empty)
			.withDefaults();
	}

	public AgentMemoryDocument updateMemory(String agentId, String sessionId, String threadId, Long userId,
			SummaryResult summaryResult) {
		AgentMemoryDocument current = load(agentId);
		Instant now = Instant.now();
		AgentMemoryDocument merged = new AgentMemoryDocument(
				mergeEntries(current.facts(), safeList(summaryResult.keyPoints()), sessionId, threadId, userId, now),
				mergeEntries(current.preferences(), extractPreferences(summaryResult.importantContext()), sessionId, threadId,
						userId, now),
				mergeEntries(current.tasks(), safeList(summaryResult.userIntent()), sessionId, threadId, userId, now),
				mergeEntries(current.context(), buildContextEntries(summaryResult), sessionId, threadId, userId, now),
				current.sqlMemory());
		AgentMemoryDocument compacted = memoryCompactionService.compactIfNeeded(merged, sessionId, threadId, userId);
		memoryFileStore.write(memoryFilePath(agentId), compacted);
		return compacted;
	}

	private List<MemoryEntry> mergeEntries(List<MemoryEntry> existingEntries, List<String> newContents, String sessionId,
			String threadId, Long userId, Instant now) {
		Map<String, MemoryEntry> merged = new LinkedHashMap<>();
		existingEntries.forEach(entry -> merged.put(normalize(entry.content()), entry));
		for (String content : newContents) {
			String normalized = normalize(content);
			if (normalized.isEmpty()) {
				continue;
			}
			MemoryEntry existing = merged.get(normalized);
			if (existing == null) {
				merged.put(normalized, new MemoryEntry(content.trim(), sessionId, threadId, userId, 1, 1, now));
			}
			else {
				merged.put(normalized,
						new MemoryEntry(existing.content(), sessionId, threadId, userId, existing.weight() + 1,
								existing.hitCount() + 1, now));
			}
		}
		return merged.values()
			.stream()
			.sorted(Comparator.comparing(MemoryEntry::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(MemoryEntry::hitCount, Comparator.reverseOrder()))
			.limit(maxEntriesPerCategory)
			.toList();
	}

	private List<String> extractPreferences(List<String> importantContext) {
		return safeList(importantContext).stream().filter(this::isPreferenceText).toList();
	}

	private List<String> buildContextEntries(SummaryResult summaryResult) {
		return Stream.concat(safeList(summaryResult.summary()).stream(),
				safeList(summaryResult.importantContext()).stream().filter(text -> !isPreferenceText(text)))
			.toList();
	}

	private boolean isPreferenceText(String value) {
		String normalized = normalize(value);
		return normalized.contains("prefer") || normalized.contains("preference") || normalized.contains("偏好")
				|| normalized.contains("喜欢") || normalized.contains("习惯");
	}

	private List<String> safeList(List<String> values) {
		return values == null ? List.of() : values;
	}

	private List<String> safeList(String value) {
		return StringUtils.isBlank(value) ? List.of() : List.of(value);
	}

	private Path memoryFilePath(String agentId) {
		return memoryRootPath.resolve("agent-" + agentId).resolve("long_term_memory.json");
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

}
