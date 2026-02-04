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
package com.alibaba.cloud.ai.dataagent.service.display;

import com.alibaba.cloud.ai.dataagent.bo.display.DisplayHint;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 展示配置加载服务，负责加载和缓存展示提示配置。
 * <p>
 * 该服务在启动时加载默认配置文件（default.json），并支持按表名加载特定配置。
 * 配置文件位于 classpath:config/display/ 目录下。
 *
 * @author fudawei
 */
@Slf4j
@Service
public class DisplayConfigLoader {

	private static final String CONFIG_BASE_PATH = "config/display/";

	private static final String DEFAULT_CONFIG_FILE = "default.json";

	private final ObjectMapper objectMapper = JsonUtil.getObjectMapper();

	/**
	 * 字段模式映射，键为模式类型（如 title、badge），值为匹配模式列表
	 */
	private Map<String, List<String>> fieldPatterns;

	/**
	 * 重要性规则映射，键为重要性级别（high、medium、low），值为字段名列表
	 */
	private Map<String, List<String>> importanceRules;

	/**
	 * 表级配置缓存，键为表名，值为该表的展示提示配置
	 */
	private final ConcurrentHashMap<String, DisplayHint> configCache = new ConcurrentHashMap<>();

	/**
	 * 初始化时加载默认配置文件
	 */
	@PostConstruct
	public void init() {
		loadDefaultConfig();
	}

	/**
	 * 加载默认配置文件
	 */
	private void loadDefaultConfig() {
		try {
			ClassPathResource resource = new ClassPathResource(CONFIG_BASE_PATH + DEFAULT_CONFIG_FILE);
			if (!resource.exists()) {
				log.warn("Default display config file not found: {}", CONFIG_BASE_PATH + DEFAULT_CONFIG_FILE);
				fieldPatterns = Collections.emptyMap();
				importanceRules = Collections.emptyMap();
				return;
			}

			try (InputStream is = resource.getInputStream()) {
				JsonNode rootNode = objectMapper.readTree(is);

				// 加载字段模式
				if (rootNode.has("fieldPatterns")) {
					fieldPatterns = objectMapper.convertValue(rootNode.get("fieldPatterns"),
							new TypeReference<Map<String, List<String>>>() {
							});
				}
				else {
					fieldPatterns = Collections.emptyMap();
				}

				// 加载重要性规则
				if (rootNode.has("importanceRules")) {
					importanceRules = objectMapper.convertValue(rootNode.get("importanceRules"),
							new TypeReference<Map<String, List<String>>>() {
							});
				}
				else {
					importanceRules = Collections.emptyMap();
				}

				log.info("Successfully loaded default display config with {} field patterns and {} importance rules",
						fieldPatterns.size(), importanceRules.size());
			}
		}
		catch (IOException e) {
			log.error("Failed to load default display config", e);
			fieldPatterns = Collections.emptyMap();
			importanceRules = Collections.emptyMap();
		}
	}

	/**
	 * 获取指定表的展示配置
	 * <p>
	 * 首先尝试从缓存中获取，如果缓存中没有，则尝试从配置文件加载。 配置文件路径为
	 * classpath:config/display/{tableName}.json
	 * @param tableName 表名
	 * @return 展示提示配置，如果配置不存在则返回 null
	 */
	public DisplayHint getConfig(String tableName) {
		if (tableName == null || tableName.isBlank()) {
			return null;
		}

		// 尝试从缓存获取
		DisplayHint cached = configCache.get(tableName);
		if (cached != null) {
			return cached;
		}

		// 尝试加载配置文件
		DisplayHint config = loadTableConfig(tableName);
		if (config != null) {
			configCache.put(tableName, config);
		}

		return config;
	}

	/**
	 * 从配置文件加载指定表的展示配置
	 * @param tableName 表名
	 * @return 展示提示配置，如果配置文件不存在则返回 null
	 */
	private DisplayHint loadTableConfig(String tableName) {
		String configPath = CONFIG_BASE_PATH + tableName + ".json";
		try {
			ClassPathResource resource = new ClassPathResource(configPath);
			if (!resource.exists()) {
				log.debug("No specific config found for table: {}", tableName);
				return null;
			}

			try (InputStream is = resource.getInputStream()) {
				DisplayHint config = objectMapper.readValue(is, DisplayHint.class);
				log.info("Successfully loaded display config for table: {}", tableName);
				return config;
			}
		}
		catch (IOException e) {
			log.error("Failed to load display config for table: {}", tableName, e);
			return null;
		}
	}

	/**
	 * 获取字段模式映射
	 * @return 字段模式映射，不可变
	 */
	public Map<String, List<String>> getFieldPatterns() {
		return fieldPatterns != null ? Collections.unmodifiableMap(fieldPatterns) : Collections.emptyMap();
	}

	/**
	 * 获取重要性规则映射
	 * @return 重要性规则映射，不可变
	 */
	public Map<String, List<String>> getImportanceRules() {
		return importanceRules != null ? Collections.unmodifiableMap(importanceRules) : Collections.emptyMap();
	}

	/**
	 * 清除配置缓存
	 */
	public void clearCache() {
		configCache.clear();
		log.info("Display config cache cleared");
	}

	/**
	 * 重新加载默认配置
	 */
	public void reloadDefaultConfig() {
		loadDefaultConfig();
		log.info("Default display config reloaded");
	}

}
