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
package com.alibaba.cloud.ai.dataagent.bo.display;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 展示提示配置类，用于指导前端如何渲染数据。
 * <p>
 * 该类包含了数据展示的完整配置信息，包括主记录配置、字段分组、
 * 字段级配置、嵌套数据配置和列表模式配置等。
 *
 * @author fudawei
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisplayHint {

	/**
	 * 主记录展示配置，定义标题、副标题、头像和标签等
	 */
	private PrimaryConfig primary;

	/**
	 * 字段分组配置列表，用于将字段组织到逻辑分组中
	 */
	private List<FieldGroup> fieldGroups;

	/**
	 * 字段级配置映射，键为字段名，值为字段配置
	 */
	private Map<String, FieldConfig> fields;

	/**
	 * 嵌套数据配置映射，键为嵌套数据字段名，值为嵌套配置
	 */
	private Map<String, NestedConfig> nested;

	/**
	 * 列表模式配置，定义卡片字段、可搜索和可排序字段
	 */
	private ListModeConfig listMode;

}
