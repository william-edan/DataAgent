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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

@Slf4j
@Component
public class MemoryFileStore {

	private static final DateTimeFormatter BACKUP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	private final ObjectMapper objectMapper;

	public MemoryFileStore() {
		this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	public <T> T read(Path path, Class<T> type, Supplier<T> defaultSupplier) {
		if (Files.notExists(path)) {
			return defaultSupplier.get();
		}
		try {
			return objectMapper.readValue(path.toFile(), type);
		}
		catch (IOException ex) {
			backupCorruptFile(path);
			log.warn("Failed to read memory file {}, reset to default: {}", path, ex.getMessage());
			return defaultSupplier.get();
		}
	}

	public void write(Path path, Object value) {
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path tempFile = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
			Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to write memory file: " + path, ex);
		}
	}

	private void backupCorruptFile(Path path) {
		try {
			String backupName = path.getFileName() + ".broken-" + LocalDateTime.now().format(BACKUP_TIME_FORMAT);
			Files.move(path, path.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException backupEx) {
			log.warn("Failed to backup corrupt memory file {}: {}", path, backupEx.getMessage());
		}
	}

}
