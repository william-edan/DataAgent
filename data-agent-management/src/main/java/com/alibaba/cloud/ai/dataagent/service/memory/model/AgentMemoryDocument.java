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
package com.alibaba.cloud.ai.dataagent.service.memory.model;

import java.util.List;

public record AgentMemoryDocument(List<MemoryEntry> facts, List<MemoryEntry> preferences, List<MemoryEntry> tasks,
		List<MemoryEntry> context, SqlMemoryBlock sqlMemory) {

	public static AgentMemoryDocument empty() {
		return new AgentMemoryDocument(List.of(), List.of(), List.of(), List.of(), SqlMemoryBlock.empty());
	}

	public AgentMemoryDocument withDefaults() {
		return new AgentMemoryDocument(facts == null ? List.of() : facts, preferences == null ? List.of() : preferences,
				tasks == null ? List.of() : tasks, context == null ? List.of() : context,
				sqlMemory == null ? SqlMemoryBlock.empty() : sqlMemory.withDefaults());
	}

}
