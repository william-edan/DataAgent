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
package com.alibaba.cloud.ai.dataagent.service.classifier;

import com.alibaba.cloud.ai.dataagent.dto.QueryTypeResult;

/**
 * 查询类型分类器接口
 * <p>
 * 负责判断用户查询应该使用简单查询路径(SIMPLE)还是复杂分析路径(COMPLEX)
 *
 * @author Claude Code
 * @since 2026-02-02
 */
public interface QueryTypeClassifier {

	/**
	 * 分类用户查询
	 * @param query 用户查询文本
	 * @return 分类结果，包含查询类型和原因
	 */
	QueryTypeResult classify(String query);

}
