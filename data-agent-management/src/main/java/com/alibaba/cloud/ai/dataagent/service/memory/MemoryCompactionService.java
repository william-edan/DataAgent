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
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorCase;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlMemoryBlock;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlSuccessCase;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MemoryCompactionService {

	private static final int SUMMARY_SNIPPET_LIMIT = 3;

	private static final int SUMMARY_MAX_CHARS = 512;

	private final int maxDocumentSizeBytes;

	private final int compactionEntryThreshold;

	private final int compactionTargetEntriesPerCategory;

	private final int compactionTargetSqlCaseCount;

	public MemoryCompactionService(DataAgentProperties properties) {
		this.maxDocumentSizeBytes = properties.getMemory().getMaxDocumentSizeBytes();
		this.compactionEntryThreshold = properties.getMemory().getCompactionEntryThreshold();
		this.compactionTargetEntriesPerCategory = properties.getMemory().getCompactionTargetEntriesPerCategory();
		this.compactionTargetSqlCaseCount = properties.getMemory().getCompactionTargetSqlCaseCount();
	}

	public AgentMemoryDocument compactIfNeeded(AgentMemoryDocument document, String sessionId, String threadId,
			Long userId) {
		AgentMemoryDocument current = document.withDefaults();
		if (!needsCompaction(current)) {
			return current;
		}
		boolean sizeTriggered = estimateDocumentSize(current) > maxDocumentSizeBytes;
		AgentMemoryDocument compacted = new AgentMemoryDocument(
				compactEntries("facts", current.facts(), sessionId, threadId, userId, sizeTriggered),
				compactEntries("preferences", current.preferences(), sessionId, threadId, userId, sizeTriggered),
				compactEntries("tasks", current.tasks(), sessionId, threadId, userId, sizeTriggered),
				compactEntries("context", current.context(), sessionId, threadId, userId, sizeTriggered),
				compactSqlMemory(current.sqlMemory(), sizeTriggered));
		if (estimateDocumentSize(compacted) <= maxDocumentSizeBytes) {
			return compacted;
		}
		return aggressivelyCompact(compacted, sessionId, threadId, userId);
	}

	private AgentMemoryDocument aggressivelyCompact(AgentMemoryDocument document, String sessionId, String threadId,
			Long userId) {
		return new AgentMemoryDocument(compactEntries("facts", document.facts(), sessionId, threadId, userId, true, 1),
				compactEntries("preferences", document.preferences(), sessionId, threadId, userId, true, 1),
				compactEntries("tasks", document.tasks(), sessionId, threadId, userId, true, 1),
				compactEntries("context", document.context(), sessionId, threadId, userId, true, 1),
				compactSqlMemory(document.sqlMemory(), true, 1));
	}

	private boolean needsCompaction(AgentMemoryDocument document) {
		return estimateDocumentSize(document) > maxDocumentSizeBytes || document.facts().size() > compactionEntryThreshold
				|| document.preferences().size() > compactionEntryThreshold
				|| document.tasks().size() > compactionEntryThreshold || document.context().size() > compactionEntryThreshold
				|| document.sqlMemory().successCases().size() > compactionEntryThreshold
				|| document.sqlMemory().errorCases().size() > compactionEntryThreshold;
	}

	private List<MemoryEntry> compactEntries(String label, List<MemoryEntry> entries, String sessionId, String threadId,
			Long userId, boolean sizeTriggered) {
		int keepCount = determineEntryKeepCount(entries.size(), sizeTriggered);
		return compactEntries(label, entries, sessionId, threadId, userId, sizeTriggered, keepCount);
	}

	private List<MemoryEntry> compactEntries(String label, List<MemoryEntry> entries, String sessionId, String threadId,
			Long userId, boolean sizeTriggered, int keepCount) {
		if (!shouldCompactEntries(entries, sizeTriggered) || entries.size() <= 1) {
			return entries;
		}
		int boundedKeepCount = Math.max(1, Math.min(keepCount, entries.size() - 1));
		List<MemoryEntry> sorted = sortEntries(entries);
		List<MemoryEntry> kept = sorted.subList(0, boundedKeepCount);
		List<MemoryEntry> discarded = sorted.subList(boundedKeepCount, sorted.size());
		MemoryEntry summary = buildSummaryEntry(label, discarded, sessionId, threadId, userId);
		return java.util.stream.Stream.concat(kept.stream(), java.util.stream.Stream.of(summary)).toList();
	}

	private boolean shouldCompactEntries(List<MemoryEntry> entries, boolean sizeTriggered) {
		return entries.size() > compactionEntryThreshold || (sizeTriggered && entries.size() > 1);
	}

	private int determineEntryKeepCount(int entryCount, boolean sizeTriggered) {
		if (!sizeTriggered && entryCount > compactionEntryThreshold) {
			return Math.max(1, compactionTargetEntriesPerCategory - 1);
		}
		return Math.max(1, Math.min(compactionTargetEntriesPerCategory - 1, entryCount / 2));
	}

	private List<MemoryEntry> sortEntries(List<MemoryEntry> entries) {
		return entries.stream()
			.sorted(Comparator.comparing(MemoryEntry::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(MemoryEntry::hitCount, Comparator.reverseOrder()))
			.toList();
	}

	private MemoryEntry buildSummaryEntry(String label, List<MemoryEntry> discarded, String sessionId, String threadId,
			Long userId) {
		String content = truncate("Compacted " + discarded.size() + " " + label + ": "
				+ discarded.stream()
					.map(MemoryEntry::content)
					.filter(StringUtils::isNotBlank)
					.limit(SUMMARY_SNIPPET_LIMIT)
					.map(this::truncateSnippet)
					.collect(Collectors.joining("; "))
				+ (discarded.size() > SUMMARY_SNIPPET_LIMIT ? "; ..." : ""), SUMMARY_MAX_CHARS);
		MemoryEntry reference = discarded.get(0);
		return new MemoryEntry(content, StringUtils.defaultIfBlank(sessionId, reference.sourceSessionId()),
				StringUtils.defaultIfBlank(threadId, reference.sourceThreadId()), userId != null ? userId : reference.userId(),
				discarded.stream().mapToInt(MemoryEntry::weight).sum(),
				discarded.stream().mapToInt(MemoryEntry::hitCount).sum(), Instant.now());
	}

	private SqlMemoryBlock compactSqlMemory(SqlMemoryBlock block, boolean sizeTriggered) {
		int keepCount = determineSqlKeepCount(block.successCases().size(), sizeTriggered);
		return compactSqlMemory(block, sizeTriggered, keepCount);
	}

	private SqlMemoryBlock compactSqlMemory(SqlMemoryBlock block, boolean sizeTriggered, int keepCountOverride) {
		List<SqlSuccessCase> successCases = block.successCases();
		List<String> successSummaries = block.successSummaries();
		if (shouldCompactSqlCases(successCases, sizeTriggered) && successCases.size() > 1) {
			List<SqlSuccessCase> sorted = sortSuccessCases(successCases);
			int keepCount = Math.max(1, Math.min(keepCountOverride, sorted.size() - 1));
			successSummaries = appendSummary(successSummaries, buildSuccessSummary(sorted.subList(keepCount, sorted.size())));
			successCases = sorted.subList(0, keepCount);
		}

		List<SqlErrorCase> errorCases = block.errorCases();
		List<String> errorSummaries = block.errorSummaries();
		if (shouldCompactSqlCases(errorCases, sizeTriggered) && errorCases.size() > 1) {
			List<SqlErrorCase> sorted = sortErrorCases(errorCases);
			int keepCount = Math.max(1, Math.min(keepCountOverride, sorted.size() - 1));
			errorSummaries = appendSummary(errorSummaries, buildErrorSummary(sorted.subList(keepCount, sorted.size())));
			errorCases = sorted.subList(0, keepCount);
		}

		return new SqlMemoryBlock(successCases, errorCases, successSummaries, errorSummaries);
	}

	private boolean shouldCompactSqlCases(List<?> cases, boolean sizeTriggered) {
		return cases.size() > compactionEntryThreshold || (sizeTriggered && cases.size() > 1);
	}

	private int determineSqlKeepCount(int caseCount, boolean sizeTriggered) {
		if (!sizeTriggered && caseCount > compactionEntryThreshold) {
			return Math.max(1, compactionTargetSqlCaseCount - 1);
		}
		return Math.max(1, Math.min(compactionTargetSqlCaseCount - 1, caseCount / 2));
	}

	private List<SqlSuccessCase> sortSuccessCases(List<SqlSuccessCase> cases) {
		return cases.stream()
			.sorted(Comparator.comparing(SqlSuccessCase::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(SqlSuccessCase::hitCount, Comparator.reverseOrder()))
			.toList();
	}

	private List<SqlErrorCase> sortErrorCases(List<SqlErrorCase> cases) {
		return cases.stream()
			.sorted(Comparator.comparing(SqlErrorCase::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(SqlErrorCase::hitCount, Comparator.reverseOrder()))
			.toList();
	}

	private List<String> appendSummary(List<String> summaries, String newSummary) {
		if (StringUtils.isBlank(newSummary)) {
			return summaries;
		}
		return java.util.stream.Stream.concat(java.util.stream.Stream.of(newSummary), summaries.stream())
			.limit(3)
			.toList();
	}

	private String buildSuccessSummary(List<SqlSuccessCase> cases) {
		Set<String> tables = cases.stream()
			.flatMap(sqlCase -> sqlCase.tables().stream())
			.filter(StringUtils::isNotBlank)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		String intents = cases.stream().map(SqlSuccessCase::question).filter(StringUtils::isNotBlank).limit(3).map(this::truncateSnippet)
			.collect(Collectors.joining(" | "));
		return truncate("Compacted " + cases.size() + " SQL success cases. Common tables: "
				+ (tables.isEmpty() ? "N/A" : String.join(", ", tables.stream().limit(4).toList()))
				+ ". Representative intents: " + intents, SUMMARY_MAX_CHARS);
	}

	private String buildErrorSummary(List<SqlErrorCase> cases) {
		Set<String> objects = cases.stream()
			.map(SqlErrorCase::analysis)
			.filter(java.util.Objects::nonNull)
			.map(analysis -> analysis.wrongObject())
			.filter(StringUtils::isNotBlank)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		String errors = cases.stream()
			.map(SqlErrorCase::errorMessage)
			.filter(StringUtils::isNotBlank)
			.limit(3)
			.map(this::truncateSnippet)
			.collect(Collectors.joining(" | "));
		return truncate("Compacted " + cases.size() + " SQL error cases. Frequent errors: " + errors
				+ ". Affected objects: " + (objects.isEmpty() ? "N/A" : String.join(", ", objects.stream().limit(4).toList())),
				SUMMARY_MAX_CHARS);
	}

	private int estimateDocumentSize(AgentMemoryDocument document) {
		try {
			return JsonUtil.getObjectMapper().writeValueAsBytes(document).length;
		}
		catch (Exception ex) {
			return StringUtils.defaultString(document.toString()).length();
		}
	}

	private String truncateSnippet(String value) {
		return truncate(value, 96);
	}

	private String truncate(String value, int limit) {
		if (StringUtils.isBlank(value) || value.length() <= limit) {
			return StringUtils.defaultString(value);
		}
		return value.substring(0, Math.max(0, limit - 3)) + "...";
	}

}
