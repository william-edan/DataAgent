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

import java.util.Map;

/**
 * 字段级配置类，用于定义单个字段的展示属性。
 *
 * @author fudawei
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldConfig {

	/**
	 * 字段显示标签
	 */
	private String label;

	/**
	 * 字段重要性级别: high, medium, low，默认为 medium
	 */
	@Builder.Default
	private String importance = "medium";

	/**
	 * 格式化类型: text, badge, currency, date, phone, percent，默认为 text
	 */
	@Builder.Default
	private String format = "text";

	/**
	 * 值映射，用于枚举值转换（如：{1: "启用", 0: "禁用"}）
	 */
	private Map<String, String> mapping;

	/**
	 * 颜色映射，用于 badge 类型的颜色设置（如：{"启用": "green", "禁用": "red"}）
	 */
	private Map<String, String> colorMapping;

}
