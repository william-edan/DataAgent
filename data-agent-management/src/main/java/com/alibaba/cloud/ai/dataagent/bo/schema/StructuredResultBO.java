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
package com.alibaba.cloud.ai.dataagent.bo.schema;

import com.alibaba.cloud.ai.dataagent.bo.display.DisplayHint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 结构化查询结果 - 包含元信息、数据和展示提示
 *
 * @author fudawei
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredResultBO {

	/**
	 * 元信息
	 */
	private MetaInfo meta;

	/**
	 * 原始结果集（保持兼容）
	 */
	private ResultSetBO resultSet;

	/**
	 * 嵌套数据
	 */
	private Map<String, List<Map<String, String>>> nestedData;

	/**
	 * 展示提示
	 */
	private DisplayHint displayHint;

	/**
	 * 从 ResultSetBO 创建简单结构化结果
	 * @param resultSet 原始结果集
	 * @return 结构化结果
	 */
	public static StructuredResultBO fromResultSet(ResultSetBO resultSet) {
		int count = resultSet.getData() != null ? resultSet.getData().size() : 0;
		return StructuredResultBO.builder()
			.resultSet(resultSet)
			.meta(MetaInfo.builder()
				.recordType(count == 1 ? "single" : "list")
				.totalCount(count)
				.hasNestedData(false)
				.build())
			.build();
	}

}
