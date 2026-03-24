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
package com.alibaba.cloud.ai.dataagent.service.nl2sql;

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlSemanticLearningCard;
import com.alibaba.cloud.ai.dataagent.properties.FileStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * SQL 语义失败经验持久化实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlSemanticLearningPersistenceServiceImpl implements SqlSemanticLearningPersistenceService {

	private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

	private static final int MAX_FILENAME_PREFIX_LENGTH = 48;

	private final FileStorageProperties fileStorageProperties;

	@Override
	public void persistLearningCard(String agentId, SqlSemanticLearningCard card) {
		if (StringUtils.isBlank(agentId) || card == null || StringUtils.isBlank(card.getMarkdownContent())) {
			return;
		}

		// 仅保存在本地 Markdown，避免写入全局知识库后污染后续证据召回。
		writeMarkdownFile(agentId, card);
	}

	private void writeMarkdownFile(String agentId, SqlSemanticLearningCard card) {
		try {
			Path filePath = buildMarkdownPath(agentId, card);
			Files.createDirectories(filePath.getParent());
			Files.writeString(filePath, card.getMarkdownContent(), StandardCharsets.UTF_8);
			log.info("SQL 失败学习经验 Markdown 落盘成功，路径：{}", filePath);
		}
		catch (Exception e) {
			log.error("SQL 失败学习经验 Markdown 落盘失败，agentId: {}, 指纹: {}", agentId, card.getFingerprint(), e);
		}
	}

	private Path buildMarkdownPath(String agentId, SqlSemanticLearningCard card) {
		String safeFilename = buildSafeFilename(card);
		return fileStorageProperties.getLocalBasePath()
			.toAbsolutePath()
			.normalize()
			.resolve("sql-semantic-lessons")
			.resolve(agentId)
			.resolve(LocalDate.now().format(DATE_FORMATTER))
			.resolve(safeFilename + ".md");
	}

	private String buildSafeFilename(SqlSemanticLearningCard card) {
		String fingerprint = StringUtils.defaultIfBlank(card.getFingerprint(), card.getErrorType());
		String readablePrefix = StringUtils.defaultIfBlank(card.getErrorType(), "sql-semantic-learning");
		readablePrefix = INVALID_FILENAME_CHARS.matcher(readablePrefix).replaceAll("_");
		readablePrefix = StringUtils.strip(readablePrefix, " ._");
		if (StringUtils.isBlank(readablePrefix)) {
			readablePrefix = "sql-semantic-learning";
		}
		if (readablePrefix.length() > MAX_FILENAME_PREFIX_LENGTH) {
			readablePrefix = readablePrefix.substring(0, MAX_FILENAME_PREFIX_LENGTH);
		}
		return readablePrefix + "-" + buildFingerprintHash(fingerprint);
	}

	private String buildFingerprintHash(String fingerprint) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(StringUtils.defaultString(fingerprint).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 8);
		}
		catch (Exception e) {
			log.warn("生成 SQL 语义失败经验文件哈希失败，将退化为使用 hashCode", e);
			return Integer.toHexString(StringUtils.defaultString(fingerprint).hashCode());
		}
	}

}
