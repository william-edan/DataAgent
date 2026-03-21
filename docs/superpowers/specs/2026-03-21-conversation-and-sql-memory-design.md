# Conversation And SQL Memory Design

## Summary

This design adds two memory capabilities to the existing Data Agent workflow without replacing the current graph-driven architecture:

1. Conversation memory for longer sessions
2. SQL memory for successful and failed SQL generation/execution outcomes

The implementation must reuse the existing `StateGraph`, `GraphServiceImpl`, `QueryServiceImpl`, `MultiTurnContextManager`, `ChatMessage` persistence, `PromptHelper`, `Nl2SqlService`, and SQL execution nodes. No direct external LLM API integration will be introduced.

## Goals

- Reuse existing short-term memory sources instead of creating a parallel chat history system
- Persist long-term memory and SQL memory to local JSON files
- Inject memory into prompts before graph execution and before SQL generation
- Record SQL success and failure outcomes after execution
- Keep the change additive and low-risk for the current graph flow
- Support token and prompt length controls
- Keep the design compatible with current session-driven frontend behavior

## Non-Goals

- Replacing `chat_message` or `chat_session` storage
- Replacing `MultiTurnContextManager`
- Building a standalone memory agent outside the current graph flow
- Introducing a new external memory database
- Refactoring the full prompt architecture

## Current Constraints

### Existing short-term context

The repository already has two short-term context sources:

- Database-backed session messages in `chat_message`
- In-memory turn compression in `MultiTurnContextManager`

This design treats them as the source of recent conversation context. It does not create `logs/*.md`.

### Existing graph insertion points

The current graph flow already carries `MULTI_TURN_CONTEXT` in graph state and injects it into nodes such as:

- `IntentRecognitionNode`
- `EvidenceRecallNode`
- `QueryEnhanceNode`
- `FeasibilityAssessmentNode`

This makes `MULTI_TURN_CONTEXT` the lowest-risk insertion point for conversation memory enhancement.

### Existing SQL insertion points

The current SQL lifecycle already has clear boundaries:

- `SqlGenerateNode` builds `SqlGenerationDTO`
- `Nl2SqlServiceImpl` renders SQL generation and repair prompts via `PromptHelper`
- `SqlExecuteNode` executes SQL and already handles success and failure branches

This makes SQL memory injection and persistence straightforward.

## Recommended Approach

Use a graph-native enhancement strategy:

- Conversation memory is loaded before graph execution
- SQL memory is injected during SQL prompt construction
- SQL outcomes are persisted inside SQL execution nodes
- Long-term memory is updated when a turn finishes

This approach is preferred over AOP-first interception because graph streaming, retry, and human-feedback resume semantics are already explicit in the graph services and nodes.

## Component Design

### 1. MemoryFileStore

Responsibility:

- Ensure memory directories exist
- Read and write JSON files
- Use atomic replace semantics when writing
- Provide per-agent file resolution

Suggested package:

- `com.alibaba.cloud.ai.dataagent.service.memory`

Suggested files:

- `MemoryFileStore.java`
- `MemoryPaths.java`

### 2. LongTermMemoryService

Responsibility:

- Load and persist structured long-term memory
- Manage `facts`, `preferences`, `tasks`, and `context`
- Deduplicate items using normalized text or hash
- Track metadata such as `sessionId`, `userId`, `updatedAt`, `weight`, and `hitCount`
- Apply trimming rules when the memory file grows

Storage path:

- `memory/agent-{agentId}/long_term_memory.json`

Suggested JSON shape:

```json
{
  "facts": [],
  "preferences": [],
  "tasks": [],
  "context": [],
  "sql_memory": {
    "success_cases": [],
    "error_cases": []
  }
}
```

### 3. SummaryService

Responsibility:

- Convert recent conversation history into a structured summary
- Reuse the current LLM abstraction (`LlmService` or the existing `ChatClient` integration behind it)
- Return a typed result that `LongTermMemoryService` can merge

Important rule:

- The implementation must use the existing Data Agent model path only

Suggested summary output fields:

- `summary`
- `keyPoints`
- `userIntent`
- `importantContext`

### 4. SqlErrorAnalyzer

Responsibility:

- Normalize database error messages into typed error records
- Extract the wrong object name when possible
- Produce a repair hint for prompt injection

Supported categories:

- `TABLE_NOT_EXIST`
- `COLUMN_NOT_EXIST`
- `SQL_SYNTAX_ERROR`
- `UNKNOWN`

Suggested output model:

- `SqlErrorAnalysis`

Suggested fields:

- `type`
- `wrongObject`
- `suggestion`
- `rawMessage`
- `normalizedMessage`

### 5. SqlMemoryService

Responsibility:

- Save successful SQL execution cases
- Save failed SQL execution cases
- Find similar historical cases for the current user query
- Deduplicate by SQL hash
- Keep at most 100 success cases and 100 error cases per agent
- Prioritize by hit count and recency

Suggested methods:

- `saveSuccess(agentId, sessionId, question, sql, tables)`
- `saveError(agentId, sessionId, question, sql, errorMessage, analysis)`
- `findSimilarSuccess(agentId, question, limit)`
- `findSimilarError(agentId, question, limit)`

Similarity strategy for v1:

- Prefer simple normalized keyword overlap and fallback substring scoring
- Do not introduce a new vector dependency for memory v1

### 6. PromptEnhancer

Responsibility:

- Build the final conversation context string used as `MULTI_TURN_CONTEXT`
- Combine:
  - recent session messages
  - compressed multi-turn state
  - long-term memory snippets
- Enforce prompt length budgets

Conversation prompt sections:

- Recent session messages
- Long-term memory
- Current user query

### 7. SqlMemoryEnhancer

Responsibility:

- Convert SQL memory into a compact prompt segment
- Inject at most:
  - 3 success cases
  - 2 error cases
- Produce text suitable for:
  - new SQL generation
  - SQL repair after execution error
  - semantic retry prompt

Suggested prompt section:

```text
# SQL Memory

Successful examples:
- Question: ...
  SQL: ...
  Tables: ...

Avoid repeated failures:
- SQL: ...
  Reason: ...
  Fix hint: ...
```

### 8. ConversationMemoryCoordinator

Responsibility:

- Orchestrate the memory lifecycle for a completed turn
- Read recent chat messages by conversation key
- Trigger summary generation when the session history crosses a threshold
- Merge the summary into long-term memory

This coordinator keeps graph services and nodes thin.

## Data Model

### Conversation key

The frontend stores messages by `sessionId` and graph execution state by `threadId`. The current UI behavior reuses the graph `threadId` within the same session after the first request, but the backend graph request currently does not carry `sessionId`.

For implementation, extend the graph request contract to include optional `sessionId`.

For memory logic, define a small conversation key abstraction:

- `conversationId = threadId if present else sessionId`

Where only one identifier is available, the other may be null in metadata. This prevents the memory layer from depending on a single entry path.

### Long-term memory entry

Suggested fields:

- `content`
- `type`
- `sourceSessionId`
- `sourceThreadId`
- `userId`
- `weight`
- `hitCount`
- `updatedAt`

### SQL success case

Suggested fields:

- `sqlHash`
- `question`
- `sql`
- `tables`
- `sessionId`
- `threadId`
- `hitCount`
- `updatedAt`

### SQL error case

Suggested fields:

- `sqlHash`
- `question`
- `sql`
- `errorMessage`
- `analysis`
- `sessionId`
- `threadId`
- `hitCount`
- `updatedAt`

## Integration Points

### 1. Graph entry

Update `GraphServiceImpl.handleNewProcess(...)`:

- extend `GraphRequest` and frontend graph request payload to include optional `sessionId`
- Build a conversation key from request state
- Load recent persisted chat messages for the session when `sessionId` is available
- Load long-term memory for the agent
- Call `PromptEnhancer`
- Store the enhanced result back into `MULTI_TURN_CONTEXT`

This preserves downstream node contracts.

### 2. Simple query path

Update `QueryServiceImpl` similarly for the simple graph path:

- Build the same enhanced `MULTI_TURN_CONTEXT`
- Reuse the same coordinator for turn-finalization after stream completion if a session/thread identifier is available

If no session key is available in the simple path, only agent-scoped SQL memory should still work.

### 3. SQL generation

Extend `SqlGenerationDTO` to carry SQL memory prompt content, for example:

- `sqlMemoryAdvice`

Then update `PromptHelper` methods:

- `buildNewSqlGeneratorPrompt(...)`
- `buildSqlErrorFixerPrompt(...)`
- `buildSemanticRetryPrompt(...)`

Each prompt should add a conditional SQL memory section when the advice text is not empty.

### 4. SQL execution

Update `SqlExecuteNode`:

- On success:
  - extract table names from current schema and SQL metadata when possible
  - call `SqlMemoryService.saveSuccess(...)`
- On failure:
  - analyze the error with `SqlErrorAnalyzer`
  - call `SqlMemoryService.saveError(...)`

This is a better fit than AOP because the node already has the exact SQL text, execution result, and retry state.

### 5. Turn finalization

Update `GraphServiceImpl.handleStreamComplete(...)`:

- Finish the pending turn in `MultiTurnContextManager`
- Trigger `ConversationMemoryCoordinator`

Recommended order:

1. finalize in-memory multi-turn state
2. summarize recent persisted conversation
3. merge long-term memory

## Prompt Budget Rules

### Conversation prompt budget

Suggested v1 policy:

- Recent session messages: last 6 to 10 messages
- Long-term memory: top weighted 8 to 12 items
- Hard truncate each section by character count before final concatenation

### SQL prompt budget

Required limits from the design notes:

- Success cases: max 3
- Error cases: max 2

Additional policy:

- Truncate SQL text and explanation text independently
- Prefer recent high-frequency cases

## AOP Position

Two approaches will be documented for future use:

### Approach A: AOP wrapper

Can be used for:

- audit logging
- metrics
- request tagging

Not recommended for core memory persistence in v1 because:

- graph streaming completion is asynchronous
- SQL retries happen inside the graph loop
- human feedback can resume a suspended thread

### Approach B: Explicit graph and node integration

Recommended for v1 because it is deterministic and aligns with the current graph model.

## Failure Handling

- Memory read failure must not break graph execution
- Memory write failure must be logged and swallowed unless the file is corrupt in a way that requires operator action
- Corrupt JSON should trigger:
  - warning log
  - backup of the broken file
  - reset to an empty structure
- Summary generation failure must not block the user response path

## Testing Strategy

Implementation must follow test-first development.

### Unit tests

- `SqlErrorAnalyzerTest`
  - parses missing table errors
  - parses missing column errors
  - parses syntax errors
  - returns `UNKNOWN` for unsupported errors
- `SqlMemoryServiceTest`
  - deduplicates by SQL hash
  - increments hit count
  - trims to max capacity
  - returns most relevant cases first
- `PromptEnhancerTest`
  - combines sections in the correct order
  - truncates when sections exceed budgets
- `SqlMemoryEnhancerTest`
  - respects 3 success and 2 error limits
  - omits empty sections
- `LongTermMemoryServiceTest`
  - merges and deduplicates structured summary output
  - keeps metadata and ordering stable

### Integration-focused tests

- `SqlExecuteNode` success path writes SQL success memory
- `SqlExecuteNode` failure path writes SQL error memory
- `GraphServiceImpl` builds enhanced `MULTI_TURN_CONTEXT`

Use temporary directories for file-backed memory tests.

## Rollout Order

1. Add memory domain models and JSON file store
2. Add long-term memory service and summary service
3. Add SQL error analyzer and SQL memory service
4. Add prompt enhancers
5. Integrate graph entry context enhancement
6. Integrate SQL generation prompt enhancement
7. Integrate SQL execution persistence
8. Add tests and run backend verification

## Expected Outcome

After implementation:

- The agent keeps better long-session continuity without replacing current chat storage
- SQL generation can reuse successful patterns
- SQL repair can avoid historically repeated failures
- All changes remain additive and aligned with the existing Data Agent graph architecture
