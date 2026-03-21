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
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorAnalysis;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlErrorCase;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlMemoryBlock;
import com.alibaba.cloud.ai.dataagent.service.memory.model.SqlSuccessCase;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SqlMemoryService {

	private final MemoryFileStore memoryFileStore;

	private final Path memoryRootPath;

	private final int maxSqlCaseCount;

	private final MemoryCompactionService memoryCompactionService;

	@Autowired
	public SqlMemoryService(MemoryFileStore memoryFileStore, DataAgentProperties properties,
			MemoryCompactionService memoryCompactionService) {
		this(memoryFileStore, Paths.get(properties.getMemory().getPath()), properties.getMemory().getMaxSqlCaseCount(),
				memoryCompactionService);
	}

	SqlMemoryService(MemoryFileStore memoryFileStore, Path memoryRootPath, int maxSqlCaseCount,
			MemoryCompactionService memoryCompactionService) {
		this.memoryFileStore = memoryFileStore;
		this.memoryRootPath = memoryRootPath;
		this.maxSqlCaseCount = maxSqlCaseCount;
		this.memoryCompactionService = memoryCompactionService;
	}

	public void saveSuccess(String agentId, String sessionId, String question, String sql, List<String> tables) {
		AgentMemoryDocument current = load(agentId);
		List<SqlSuccessCase> updatedSuccessCases = upsertSuccessCase(current.sqlMemory().successCases(), sessionId, question, sql,
				tables);
		write(agentId, new AgentMemoryDocument(current.facts(), current.preferences(), current.tasks(), current.context(),
				new SqlMemoryBlock(updatedSuccessCases, current.sqlMemory().errorCases(),
						current.sqlMemory().successSummaries(), current.sqlMemory().errorSummaries())));
	}

	public void saveError(String agentId, String sessionId, String question, String sql, String errorMessage,
			SqlErrorAnalysis analysis) {
		AgentMemoryDocument current = load(agentId);
		List<SqlErrorCase> updatedErrorCases = upsertErrorCase(current.sqlMemory().errorCases(), sessionId, question, sql,
				errorMessage, analysis);
		write(agentId, new AgentMemoryDocument(current.facts(), current.preferences(), current.tasks(), current.context(),
				new SqlMemoryBlock(current.sqlMemory().successCases(), updatedErrorCases,
						current.sqlMemory().successSummaries(), current.sqlMemory().errorSummaries())));
	}

	public SqlMemoryBlock loadSqlMemory(String agentId) {
		return load(agentId).sqlMemory();
	}

	public List<SqlSuccessCase> findSimilarSuccess(String agentId, String lookupText, int limit) {
		return load(agentId).sqlMemory().successCases().stream()
			.filter(sqlCase -> matchesLookup(lookupText, sqlCase.question(), sqlCase.sql(), sqlCase.tables()))
			.sorted(successComparator())
			.limit(limit)
			.toList();
	}

	public List<SqlErrorCase> findSimilarError(String agentId, String lookupText, int limit) {
		return load(agentId).sqlMemory().errorCases().stream()
			.filter(sqlCase -> matchesLookup(lookupText, sqlCase.question(), sqlCase.sql(),
					List.of(StringUtils.defaultString(sqlCase.analysis() == null ? null : sqlCase.analysis().wrongObject()),
							StringUtils.defaultString(sqlCase.errorMessage()))))
			.sorted(errorComparator())
			.limit(limit)
			.toList();
	}

	private AgentMemoryDocument load(String agentId) {
		return memoryFileStore.read(memoryFilePath(agentId), AgentMemoryDocument.class, AgentMemoryDocument::empty)
			.withDefaults();
	}

	private void write(String agentId, AgentMemoryDocument document) {
		memoryFileStore.write(memoryFilePath(agentId), memoryCompactionService.compactIfNeeded(document, null, null, null));
	}

	private Path memoryFilePath(String agentId) {
		return memoryRootPath.resolve("agent-" + agentId).resolve("long_term_memory.json");
	}

	private List<SqlSuccessCase> upsertSuccessCase(List<SqlSuccessCase> existingCases, String sessionId, String question,
			String sql, List<String> tables) {
		Instant now = Instant.now();
		String sqlHash = hashSql(sql);
		Map<String, SqlSuccessCase> merged = new LinkedHashMap<>();
		existingCases.forEach(sqlCase -> merged.put(sqlCase.sqlHash(), sqlCase));
		SqlSuccessCase existingCase = merged.get(sqlHash);
		if (existingCase == null) {
			merged.put(sqlHash, new SqlSuccessCase(sqlHash, question, sql, sanitizeTables(tables), sessionId, null, 1, now));
		}
		else {
			merged.put(sqlHash,
					new SqlSuccessCase(sqlHash, StringUtils.defaultIfBlank(question, existingCase.question()),
							StringUtils.defaultIfBlank(sql, existingCase.sql()),
							mergeTables(existingCase.tables(), tables), sessionId, existingCase.threadId(),
							existingCase.hitCount() + 1, now));
		}
		return merged.values().stream().sorted(successComparator()).limit(maxSqlCaseCount).toList();
	}

	private List<SqlErrorCase> upsertErrorCase(List<SqlErrorCase> existingCases, String sessionId, String question,
			String sql, String errorMessage, SqlErrorAnalysis analysis) {
		Instant now = Instant.now();
		String sqlHash = hashSql(sql);
		Map<String, SqlErrorCase> merged = new LinkedHashMap<>();
		existingCases.forEach(sqlCase -> merged.put(sqlCase.sqlHash(), sqlCase));
		SqlErrorCase existingCase = merged.get(sqlHash);
		if (existingCase == null) {
			merged.put(sqlHash, new SqlErrorCase(sqlHash, question, sql, errorMessage, analysis, sessionId, null, 1, now));
		}
		else {
			merged.put(sqlHash,
					new SqlErrorCase(sqlHash, StringUtils.defaultIfBlank(question, existingCase.question()),
							StringUtils.defaultIfBlank(sql, existingCase.sql()),
							StringUtils.defaultIfBlank(errorMessage, existingCase.errorMessage()),
							analysis == null ? existingCase.analysis() : analysis, sessionId, existingCase.threadId(),
							existingCase.hitCount() + 1, now));
		}
		return merged.values().stream().sorted(errorComparator()).limit(maxSqlCaseCount).toList();
	}

	private Comparator<SqlSuccessCase> successComparator() {
		return Comparator.comparing(SqlSuccessCase::hitCount, Comparator.reverseOrder())
			.thenComparing(SqlSuccessCase::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
	}

	private Comparator<SqlErrorCase> errorComparator() {
		return Comparator.comparing(SqlErrorCase::hitCount, Comparator.reverseOrder())
			.thenComparing(SqlErrorCase::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
	}

	private boolean matchesLookup(String lookupText, String question, String sql, List<String> candidates) {
		String normalizedLookup = normalize(lookupText);
		if (normalizedLookup.isEmpty()) {
			return true;
		}
		if (normalize(question).contains(normalizedLookup) || normalize(sql).contains(normalizedLookup)) {
			return true;
		}
		return candidates.stream()
			.filter(StringUtils::isNotBlank)
			.map(this::normalize)
			.anyMatch(candidate -> candidate.contains(normalizedLookup) || normalizedLookup.contains(candidate));
	}

	private List<String> sanitizeTables(List<String> tables) {
		if (tables == null || tables.isEmpty()) {
			return List.of();
		}
		List<String> sanitized = new ArrayList<>();
		for (String table : tables) {
			if (StringUtils.isBlank(table)) {
				continue;
			}
			if (!sanitized.contains(table.trim())) {
				sanitized.add(table.trim());
			}
		}
		return sanitized;
	}

	private List<String> mergeTables(List<String> existingTables, List<String> newTables) {
		List<String> merged = new ArrayList<>(sanitizeTables(existingTables));
		for (String table : sanitizeTables(newTables)) {
			if (!merged.contains(table)) {
				merged.add(table);
			}
		}
		return merged;
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private String hashSql(String sql) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(normalize(sql).getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder();
			for (byte value : hash) {
				builder.append(String.format("%02x", value));
			}
			return builder.toString();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to hash SQL", e);
		}
	}

}
