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
 * 嵌套数据配置类，用于定义关联数据的展示方式。
 *
 * @author fudawei
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NestedConfig {

	/**
	 * 嵌套数据的显示标签
	 */
	private String label;

	/**
	 * 嵌套数据的图标
	 */
	private String icon;

	/**
	 * 显示模式: timeline, list, cards，默认为 list
	 */
	@Builder.Default
	private String displayMode = "list";

	/**
	 * 排序规则（如："-createTime" 表示按创建时间降序）
	 */
	private String orderBy;

	/**
	 * 嵌套数据的主配置
	 */
	private PrimaryConfig primary;

	/**
	 * 关联关系描述
	 */
	private String relation;

	/**
	 * 分组字段（如："类型"，用于将嵌套数据按类型分组）
	 */
	private String groupBy;

	/**
	 * 字段列表（嵌套数据中要显示的字段）
	 */
	private List<String> fields;

}
