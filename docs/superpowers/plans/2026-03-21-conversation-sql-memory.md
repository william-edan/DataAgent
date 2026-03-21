# Conversation And SQL Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add file-backed long-term memory and SQL memory to the existing Data Agent graph flow, reusing current chat/session storage and `MultiTurnContextManager`, while injecting memory into prompts and persisting SQL execution outcomes.

**Architecture:** Keep the current `StateGraph` contract intact and layer memory in at the graph entry, SQL generation, SQL execution, and turn-finalization boundaries. Reuse persisted `chat_message` data for recent context, store long-term and SQL memory per agent in JSON files, and pass `sessionId` through the existing frontend-to-graph request chain so memory can bind graph turns to chat sessions without inventing a second session system.

**Tech Stack:** Java 17, Spring Boot, Spring AI Alibaba Data Agent, MyBatis, Vue 3 + TypeScript, JUnit 5, Mockito, Maven.

---

### Task 1: Build The File-Backed Memory Foundation

**Files:**
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/MemoryFileStore.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/LongTermMemoryService.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/model/AgentMemoryDocument.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/model/MemoryEntry.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/model/SqlMemoryBlock.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/model/SummaryResult.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/properties/DataAgentProperties.java`
- Modify: `data-agent-management/src/main/resources/application.yml`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/LongTermMemoryServiceTest.java`

- [ ] **Step 1: Write the failing memory persistence and merge tests**

```java
@Test
void updateMemory_mergesSummaryIntoTypedBuckets_andDeduplicatesByNormalizedContent() {
    SummaryResult summary = new SummaryResult(
            "User prefers monthly sales summaries",
            List.of("Needs monthly sales reports", "Needs monthly sales reports"),
            "Track monthly sales",
            List.of("Prefers concise output"));

    service.updateMemory("agent-1", "session-1", "thread-1", 99L, summary);

    AgentMemoryDocument document = service.load("agent-1");
    assertThat(document.preferences()).extracting(MemoryEntry::content)
            .containsExactly("Prefers concise output");
    assertThat(document.tasks()).extracting(MemoryEntry::content)
            .containsExactly("Track monthly sales");
    assertThat(document.facts()).extracting(MemoryEntry::content)
            .containsExactly("Needs monthly sales reports");
}

@Test
void load_returnsEmptyDocument_whenMemoryFileDoesNotExist() {
    AgentMemoryDocument document = service.load("agent-404");
    assertThat(document.facts()).isEmpty();
    assertThat(document.sqlMemory().successCases()).isEmpty();
}
```

- [ ] **Step 2: Run the tests to verify they fail for the expected reason**

Run: `cd data-agent-management && ./mvnw -Dtest=LongTermMemoryServiceTest test`

Expected: FAIL because `LongTermMemoryService`, `AgentMemoryDocument`, and related memory models do not exist yet.

- [ ] **Step 3: Implement the memory models, file store, and long-term memory service**

```java
public record MemoryEntry(
        String content,
        String sourceSessionId,
        String sourceThreadId,
        Long userId,
        int weight,
        int hitCount,
        Instant updatedAt) {
}

public AgentMemoryDocument updateMemory(String agentId, String sessionId, String threadId, Long userId,
        SummaryResult summary) {
    AgentMemoryDocument current = load(agentId);
    AgentMemoryDocument merged = merge(current, summary, sessionId, threadId, userId);
    fileStore.write(memoryPath(agentId), merged);
    return merged;
}
```

Implementation notes:
- Add a nested `memory` section to `DataAgentProperties` with paths and limits.
- Store memory under `memory/agent-{agentId}/long_term_memory.json`.
- Write JSON atomically via temp file + move.
- On read failure, back up the corrupt file and return an empty document.

- [ ] **Step 4: Run the targeted tests to verify the new foundation passes**

Run: `cd data-agent-management && ./mvnw -Dtest=LongTermMemoryServiceTest test`

Expected: PASS with both tests green and no compilation errors.

- [ ] **Step 5: Commit the foundation layer**

```bash
git add data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/properties/DataAgentProperties.java \
        data-agent-management/src/main/resources/application.yml \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/LongTermMemoryServiceTest.java
git commit -m "feat(memory): add file-backed agent memory foundation"
```

### Task 2: Add Summary And Conversation Prompt Enhancement

**Files:**
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/SummaryService.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/PromptEnhancer.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/ConversationMemoryCoordinator.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/chat/ChatMessageService.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/chat/ChatMessageServiceImpl.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/mapper/ChatMessageMapper.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/PromptEnhancerTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/ConversationMemoryCoordinatorTest.java`

- [ ] **Step 1: Write the failing prompt enhancement and coordinator tests**

```java
@Test
void enhance_combinesRecentMessages_multiTurnContext_andLongTermMemory_inStableOrder() {
    String prompt = enhancer.enhance(
            List.of(user("How are monthly sales trending?"), assistant("You asked for sales trends.")),
            "User: previous turn\nAI plan: inspect sales table",
            documentWithPreference("Prefers concise output"),
            "Show this month's sales");

    assertThat(prompt).contains("# Recent Conversation");
    assertThat(prompt).contains("# Long-Term Memory");
    assertThat(prompt).contains("Prefers concise output");
    assertThat(prompt).contains("Show this month's sales");
}

@Test
void finalizeTurn_skipsSummary_whenNotEnoughMessages() {
    coordinator.finalizeTurn("agent-1", "session-1", "thread-1", 5L);
    verify(summaryService, never()).summarize(anyString());
}
```

- [ ] **Step 2: Run the tests to verify they fail before implementation**

Run: `cd data-agent-management && ./mvnw -Dtest=PromptEnhancerTest,ConversationMemoryCoordinatorTest test`

Expected: FAIL because the prompt enhancer/coordinator APIs do not exist and `ChatMessageService` has no recent-message query yet.

- [ ] **Step 3: Implement summary generation, recent-message loading, and prompt assembly**

```java
public String enhance(List<ChatMessage> recentMessages, String multiTurnContext,
        AgentMemoryDocument memory, String userInput) {
    return """
            # Recent Conversation
            %s

            # Long-Term Memory
            %s

            # Current User Input
            %s
            """.formatted(renderMessages(recentMessages, multiTurnContext), renderMemory(memory), userInput);
}

public void finalizeTurn(String agentId, String sessionId, String threadId, Long userId) {
    List<ChatMessage> recentMessages = chatMessageService.findRecentBySessionId(sessionId, memoryProperties.getRecentMessageLimit());
    if (recentMessages.size() < memoryProperties.getSummaryTriggerMessageCount()) {
        return;
    }
    SummaryResult summary = summaryService.summarize(renderConversation(recentMessages));
    longTermMemoryService.updateMemory(agentId, sessionId, threadId, userId, summary);
}
```

Implementation notes:
- Keep `SummaryService` on top of `LlmService`; do not introduce any direct third-party API client.
- Add a targeted `findRecentBySessionId(sessionId, limit)` query in `ChatMessageMapper` to avoid loading full sessions for prompt assembly.
- Hard-truncate each section in `PromptEnhancer` using `DataAgentProperties.Memory`.

- [ ] **Step 4: Run the targeted tests again**

Run: `cd data-agent-management && ./mvnw -Dtest=PromptEnhancerTest,ConversationMemoryCoordinatorTest test`

Expected: PASS with prompt ordering and summary-trigger behavior verified.

- [ ] **Step 5: Commit the conversation memory layer**

```bash
git add data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/chat/ChatMessageService.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/chat/ChatMessageServiceImpl.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/mapper/ChatMessageMapper.java \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/PromptEnhancerTest.java \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/ConversationMemoryCoordinatorTest.java
git commit -m "feat(memory): add conversation summary and prompt enhancement"
```

### Task 3: Thread Session Identity Through The Graph Entry Points

**Files:**
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/GraphRequest.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/constant/Constant.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/StreamContext.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/query/QueryService.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/query/QueryServiceImpl.java`
- Modify: `data-agent-frontend/src/services/graph.ts`
- Modify: `data-agent-frontend/src/views/AgentRun.vue`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImplMemoryTest.java`

- [ ] **Step 1: Write the failing graph entry test for `sessionId` plumbing and prompt injection**

```java
@Test
void handleNewProcess_injectsEnhancedMultiTurnContext_andCarriesSessionIdIntoState() {
    GraphRequest request = GraphRequest.builder()
            .agentId("1")
            .sessionId("session-123")
            .threadId("thread-123")
            .query("Show monthly revenue")
            .build();

    graphService.graphStreamProcess(sink, request);

    verify(promptEnhancer).enhance(anyList(), anyString(), any(), eq("Show monthly revenue"));
    verify(compiledGraph).stream(argThat(state ->
            "session-123".equals(state.get(Constant.SESSION_ID))
                    && state.containsKey(Constant.MULTI_TURN_CONTEXT)), any());
}
```

- [ ] **Step 2: Run the graph-entry test to confirm it fails**

Run: `cd data-agent-management && ./mvnw -Dtest=GraphServiceImplMemoryTest test`

Expected: FAIL because `GraphRequest` has no `sessionId`, the graph state has no session key, and `GraphServiceImpl` does not call the memory services.

- [ ] **Step 3: Implement the session-aware graph request plumbing**

```java
@Builder
public class GraphRequest {
    private String agentId;
    private String sessionId;
    private String threadId;
    private String query;
    // existing fields...
}

Map<String, Object> state = new HashMap<>(Map.of(
        IS_ONLY_NL2SQL, nl2sqlOnly,
        INPUT_KEY, query,
        AGENT_ID, agentId,
        SESSION_ID, graphRequest.getSessionId(),
        MULTI_TURN_CONTEXT, enhancedContext));
```

Implementation notes:
- Add `SESSION_ID` to `Constant` and `DataAgentConfiguration.buildKeyStrategyFactory()`.
- In `GraphController.streamSearch(...)`, accept `sessionId` as an optional request parameter and pass it into `GraphRequest`.
- Persist request-scoped identifiers needed after streaming finishes by extending `StreamContext` with `sessionId` (and optional `userId` if later required).
- In `QueryService.queryStream(...)`, add overload parameters so the simple path can also receive `sessionId` and `threadId`.
- In the frontend, append `sessionId` in `data-agent-frontend/src/services/graph.ts` and set it from `currentSession.value.id` in `AgentRun.vue`.

- [ ] **Step 4: Re-run backend and frontend contract checks**

Run: `cd data-agent-management && ./mvnw -Dtest=GraphServiceImplMemoryTest test`

Run: `cd data-agent-frontend && npm run type-check`

Expected: backend test PASS and frontend type check PASS with the new `sessionId` field wired through.

- [ ] **Step 5: Commit the graph/session plumbing**

```bash
git add data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/GraphRequest.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/constant/Constant.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/query/QueryService.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/query/QueryServiceImpl.java \
        data-agent-frontend/src/services/graph.ts \
        data-agent-frontend/src/views/AgentRun.vue \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImplMemoryTest.java
git commit -m "feat(memory): pass session context through graph requests"
```

### Task 4: Add SQL Memory Analysis, Retrieval, And Prompt Injection

**Files:**
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlErrorAnalyzer.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlMemoryService.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlMemoryEnhancer.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/model/SqlErrorAnalysis.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/model/SqlSuccessCase.java`
- Create: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/model/SqlErrorCase.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/prompt/SqlGenerationDTO.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java`
- Modify: `data-agent-management/src/main/resources/prompts/new-sql-generate.txt`
- Modify: `data-agent-management/src/main/resources/prompts/sql-error-fixer.txt`
- Modify: `data-agent-management/src/main/resources/prompts/semantic-retry.txt`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlErrorAnalyzerTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlMemoryServiceTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlMemoryEnhancerTest.java`

- [ ] **Step 1: Write the failing SQL memory tests**

```java
@Test
void analyze_identifiesMissingTableErrors() {
    SqlErrorAnalysis analysis = analyzer.analyze("Table 'user' doesn't exist");
    assertThat(analysis.type()).isEqualTo(SqlErrorType.TABLE_NOT_EXIST);
    assertThat(analysis.wrongObject()).isEqualTo("user");
}

@Test
void saveSuccess_deduplicatesBySqlHash_andIncrementsHitCount() {
    service.saveSuccess("agent-1", "session-1", "How many users?", "SELECT COUNT(*) FROM users", List.of("users"));
    service.saveSuccess("agent-1", "session-1", "Count users again", "SELECT COUNT(*) FROM users", List.of("users"));

    List<SqlSuccessCase> cases = service.findSimilarSuccess("agent-1", "users", 3);
    assertThat(cases).hasSize(1);
    assertThat(cases.get(0).hitCount()).isEqualTo(2);
}

@Test
void buildSqlAdvice_limitsToThreeSuccessCases_andTwoErrorCases() {
    String advice = enhancer.enhance(successCases(5), errorCases(4));
    assertThat(advice).contains("Successful examples");
    assertThat(countOccurrences(advice, "SQL:")).isLessThanOrEqualTo(5);
}
```

- [ ] **Step 2: Run the SQL memory tests to watch them fail**

Run: `cd data-agent-management && ./mvnw -Dtest=SqlErrorAnalyzerTest,SqlMemoryServiceTest,SqlMemoryEnhancerTest test`

Expected: FAIL because the analyzer, memory service, enhancer, and `sqlMemoryAdvice` prompt support do not exist yet.

- [ ] **Step 3: Implement SQL memory services and prompt wiring**

```java
public SqlGenerationDTO buildSqlGenerationDTO(...) {
    String sqlMemoryAdvice = sqlMemoryEnhancer.enhance(
            sqlMemoryService.findSimilarSuccess(agentId, userQuery, 3),
            sqlMemoryService.findSimilarError(agentId, userQuery, 2));

    return SqlGenerationDTO.builder()
            .query(userQuery)
            .sqlMemoryAdvice(sqlMemoryAdvice)
            .build();
}

public String buildNewSqlGeneratorPrompt(SqlGenerationDTO dto) {
    params.put("sql_memory_advice",
            StringUtils.defaultIfBlank(dto.getSqlMemoryAdvice(), "No relevant SQL memory."));
    return PromptConstant.getNewSqlGeneratorPromptTemplate().render(params);
}
```

Implementation notes:
- Deduplicate SQL cases by normalized SQL SHA-256 hash.
- Sort retained cases by `hitCount DESC, updatedAt DESC`.
- Keep a hard cap of 100 success cases and 100 error cases per agent.
- Update prompt templates to render SQL memory conditionally instead of always injecting placeholder noise.

- [ ] **Step 4: Re-run the SQL memory tests**

Run: `cd data-agent-management && ./mvnw -Dtest=SqlErrorAnalyzerTest,SqlMemoryServiceTest,SqlMemoryEnhancerTest test`

Expected: PASS with parsing, deduplication, ranking, and prompt-budget rules covered.

- [ ] **Step 5: Commit the SQL memory and prompt layer**

```bash
git add data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/prompt/SqlGenerationDTO.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java \
        data-agent-management/src/main/resources/prompts/new-sql-generate.txt \
        data-agent-management/src/main/resources/prompts/sql-error-fixer.txt \
        data-agent-management/src/main/resources/prompts/semantic-retry.txt \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlErrorAnalyzerTest.java \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlMemoryServiceTest.java \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlMemoryEnhancerTest.java
git commit -m "feat(sql): add sql memory prompt guidance"
```

### Task 5: Persist SQL Outcomes And Finalize Long-Term Memory Updates

**Files:**
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/StreamContext.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/query/QueryServiceImpl.java`
- Modify: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/ConversationMemoryCoordinator.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/node/SqlExecuteNodeMemoryTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImplMemoryLifecycleTest.java`

- [ ] **Step 1: Write the failing SQL execution and turn-finalization tests**

```java
@Test
void apply_savesSuccessfulSqlExecutionIntoSqlMemory() throws Exception {
    when(accessor.executeSqlAndReturnObject(any(), any())).thenReturn(resultSet());

    node.apply(stateWith("agentId", "1", "sessionId", "session-1", "input", "Count users",
            "SQL_GENERATE_OUTPUT", "SELECT COUNT(*) FROM users"));

    verify(sqlMemoryService).saveSuccess(eq("1"), eq("session-1"), eq("Count users"),
            eq("SELECT COUNT(*) FROM users"), anyList());
}

@Test
void handleStreamComplete_finalizesTurn_andUpdatesLongTermMemory() {
    StreamContext context = new StreamContext();
    context.setSessionId("session-1");
    contextMap.put("thread-1", context);

    service.handleStreamComplete("1", "thread-1");

    verify(conversationMemoryCoordinator).finalizeTurn(eq("1"), eq("session-1"), eq("thread-1"), isNull());
}
```

- [ ] **Step 2: Run the lifecycle tests to confirm they fail first**

Run: `cd data-agent-management && ./mvnw -Dtest=SqlExecuteNodeMemoryTest,GraphServiceImplMemoryLifecycleTest test`

Expected: FAIL because `SqlExecuteNode` and `GraphServiceImpl` do not yet call the memory services.

- [ ] **Step 3: Implement SQL outcome persistence and end-of-turn memory updates**

```java
try {
    ResultSetBO resultSetBO = dbAccessor.executeSqlAndReturnObject(dbConfig, dbQueryParameter);
    sqlMemoryService.saveSuccess(agentIdStr, sessionId, userQuery, sqlQuery, extractTables(state, sqlQuery));
    // existing result handling...
}
catch (Exception e) {
    SqlErrorAnalysis analysis = sqlErrorAnalyzer.analyze(e.getMessage());
    sqlMemoryService.saveError(agentIdStr, sessionId, userQuery, sqlQuery, e.getMessage(), analysis);
    result.put(SQL_REGENERATE_REASON, SqlRetryDto.sqlExecute(e.getMessage()));
}
```

```java
private void handleStreamComplete(String agentId, String threadId) {
    StreamContext context = streamContextMap.get(threadId);
    multiTurnContextManager.finishTurn(threadId);
    conversationMemoryCoordinator.finalizeTurn(agentId,
            context != null ? context.getSessionId() : null,
            threadId,
            null);
    // existing sink completion...
}
```

Implementation notes:
- Persist SQL memory only after actual execution success or failure, not after SQL generation alone.
- Read `sessionId` needed at stream completion from `StreamContext` instead of inventing a second resolver or hidden global map.
- Tolerate null user IDs for v1; if user-aware memory becomes necessary later, resolve it in the coordinator via `ChatSessionService.findBySessionId(...)`.
- For simple-path streams, call the same coordinator from the query stream completion callback when session context is available.

- [ ] **Step 4: Re-run the lifecycle tests**

Run: `cd data-agent-management && ./mvnw -Dtest=SqlExecuteNodeMemoryTest,GraphServiceImplMemoryLifecycleTest test`

Expected: PASS with SQL persistence and turn finalization behavior verified.

- [ ] **Step 5: Commit the execution persistence layer**

```bash
git add data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/query/QueryServiceImpl.java \
        data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/memory/ConversationMemoryCoordinator.java \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/node/SqlExecuteNodeMemoryTest.java \
        data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImplMemoryLifecycleTest.java
git commit -m "feat(memory): persist sql outcomes and finalize turn memory"
```

### Task 6: Verify The Whole Feature End To End

**Files:**
- Modify: `docs/superpowers/specs/2026-03-21-conversation-and-sql-memory-design.md` (only if implementation reality diverges from design)
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/LongTermMemoryServiceTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/PromptEnhancerTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/ConversationMemoryCoordinatorTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlErrorAnalyzerTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlMemoryServiceTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/memory/SqlMemoryEnhancerTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/node/SqlExecuteNodeMemoryTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImplMemoryTest.java`
- Test: `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImplMemoryLifecycleTest.java`

- [ ] **Step 1: Run the focused backend memory test suite**

Run: `cd data-agent-management && ./mvnw -Dtest=LongTermMemoryServiceTest,PromptEnhancerTest,ConversationMemoryCoordinatorTest,SqlErrorAnalyzerTest,SqlMemoryServiceTest,SqlMemoryEnhancerTest,SqlExecuteNodeMemoryTest,GraphServiceImplMemoryTest,GraphServiceImplMemoryLifecycleTest test`

Expected: PASS with all new memory-related tests green.

- [ ] **Step 2: Run backend compile + broader verification**

Run: `cd data-agent-management && ./mvnw test`

Expected: PASS with no regressions in existing backend tests.

- [ ] **Step 3: Run frontend type and lint verification for the request contract change**

Run: `cd data-agent-frontend && npm run type-check`

Run: `cd data-agent-frontend && npm run lint:check`

Expected: PASS with no TypeScript or ESLint regressions caused by the new `sessionId` request field.

- [ ] **Step 4: Run repository formatting checks**

Run: `make format-check`

Expected: PASS with Spring Java format still clean.

- [ ] **Step 5: Commit the final verified feature**

```bash
git add data-agent-management data-agent-frontend docs/superpowers/specs
git commit -m "feat(memory): add conversation and sql memory support"
```
