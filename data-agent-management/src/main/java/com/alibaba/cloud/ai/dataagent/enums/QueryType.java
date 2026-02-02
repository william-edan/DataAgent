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
package com.alibaba.cloud.ai.dataagent.enums;

/**
 * 查询类型枚举
 *
 * @author Claude Code
 * @since 2026-02-02
 */
public enum QueryType {

	/**
	 * 简单查询：直接数据检索，返回结构化数据
	 * <p>
	 * 特征：
	 * <ul>
	 * <li>关键词：查询、列出、显示、有多少、是什么</li>
	 * <li>行为：单表或简单JOIN查询、数据检索、条件过滤</li>
	 * <li>输出：结构化数据表格</li>
	 * </ul>
	 */
	SIMPLE,

	/**
	 * 复杂分析：需要深度分析、报告生成
	 * <p>
	 * 特征：
	 * <ul>
	 * <li>关键词：分析、趋势、对比、为什么、建议、报告、洞察</li>
	 * <li>行为：多维度分析、时间序列、聚合计算、业务解读</li>
	 * <li>输出：带有可视化图表、文字解读的完整报告</li>
	 * </ul>
	 */
	COMPLEX

}
