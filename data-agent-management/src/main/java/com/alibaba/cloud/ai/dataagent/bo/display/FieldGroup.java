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

/**
 * 字段分组配置类，用于将相关字段组织到逻辑分组中。
 *
 * @author fudawei
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldGroup {

	/**
	 * 分组唯一标识键
	 */
	private String key;

	/**
	 * 分组显示标签
	 */
	private String label;

	/**
	 * 分组图标
	 */
	private String icon;

	/**
	 * 分组包含的字段列表
	 */
	private List<String> fields;

	/**
	 * 是否默认展开，默认为 true
	 */
	@Builder.Default
	private Boolean defaultExpanded = true;

}
