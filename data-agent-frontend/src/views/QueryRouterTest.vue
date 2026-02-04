<!--
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->

<template>
  <BaseLayout>
    <div class="query-router-test">
      <el-card class="header-card">
        <template #header>
          <div class="card-header">
            <span class="title">🧪 智能查询路由测试工具</span>
            <el-tag type="info" size="large">测试版本: 2026-02-02</el-tag>
          </div>
        </template>
        <el-alert
          type="info"
          title="测试说明"
          :closable="false"
          show-icon
          style="margin-bottom: 20px"
        >
          <p>此页面用于测试智能查询路由功能，自动判断查询应该走简单查询路径还是复杂分析路径。</p>
          <p><strong>简单查询示例：</strong> "查询所有用户"、"显示销售额"、"有多少订单"</p>
          <p><strong>复杂分析示例：</strong> "分析销售趋势"、"对比业绩表现"、"为什么下降"</p>
        </el-alert>

        <el-form :model="testForm" label-width="120px">
          <el-form-item label="选择智能体">
            <el-select v-model="testForm.agentId" placeholder="请选择智能体" style="width: 100%">
              <el-option
                v-for="agent in agents"
                :key="agent.id"
                :label="agent.name"
                :value="agent.id"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="测试查询">
            <el-input
              v-model="testForm.query"
              type="textarea"
              :rows="3"
              placeholder="输入你的查询..."
              :disabled="isProcessing"
            />
          </el-form-item>

          <el-form-item>
            <el-button
              type="primary"
              @click="executeQuery"
              :loading="isProcessing"
              :disabled="!testForm.agentId || !testForm.query"
              size="large"
            >
              <el-icon v-if="!isProcessing"><Search /></el-icon>
              {{ isProcessing ? '处理中...' : '执行查询' }}
            </el-button>
            <el-button @click="clearResults" :disabled="isProcessing" size="large">
              <el-icon><Delete /></el-icon>
              清空结果
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 查询路由信息 -->
      <el-card class="result-card" v-if="routingInfo">
        <template #header>
          <div class="card-header">
            <span>🎯 路由决策</span>
            <el-tag :type="routingInfo.queryType === 'SIMPLE' ? 'success' : 'warning'" size="large">
              {{ routingInfo.queryType === 'SIMPLE' ? '简单查询' : '复杂分析' }}
            </el-tag>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="查询类型">
            <el-tag :type="routingInfo.queryType === 'SIMPLE' ? 'success' : 'warning'">
              {{ routingInfo.queryType }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="路由路径">
            <el-tag :type="routingInfo.queryType === 'SIMPLE' ? 'success' : 'primary'">
              {{ routingInfo.queryType === 'SIMPLE' ? 'QueryService' : 'GraphService' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="查询内容" :span="2">
            {{ testForm.query }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 节点执行流程 -->
      <el-card class="result-card" v-if="nodeBlocks.length > 0">
        <template #header>
          <div class="card-header">
            <span>🔄 执行流程</span>
            <el-tag type="info" size="large">{{ nodeBlocks.length }} 个节点</el-tag>
          </div>
        </template>
        <el-timeline>
          <el-timeline-item
            v-for="(block, index) in nodeBlocks"
            :key="index"
            :timestamp="block.timestamp"
            placement="top"
            :type="getNodeType(block.nodeName)"
            :icon="getNodeIcon(block.nodeName)"
          >
            <el-card>
              <template #header>
                <div class="node-header">
                  <span class="node-name">{{ formatNodeName(block.nodeName) }}</span>
                  <el-tag :type="getTextTypeTag(block.textType)" size="small">
                    {{ block.textType }}
                  </el-tag>
                </div>
              </template>
              <div class="node-content" v-if="block.text">
                <pre v-if="block.textType === 'JSON'">{{ formatJSON(block.text) }}</pre>
                <pre v-else-if="block.textType === 'SQL'" class="sql-content">{{ block.text }}</pre>
                <div v-else>{{ block.text }}</div>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
      </el-card>

      <!-- 原始响应日志 -->
      <el-card class="result-card" v-if="rawResponses.length > 0">
        <template #header>
          <div class="card-header">
            <span>📋 原始响应日志</span>
            <el-tag type="info" size="large">{{ rawResponses.length }} 条消息</el-tag>
          </div>
        </template>
        <div class="log-container">
          <div v-for="(response, index) in rawResponses" :key="index" class="log-entry">
            <el-tag size="small" :type="response.error ? 'danger' : 'info'">
              {{ index + 1 }}
            </el-tag>
            <span class="log-node">{{ response.nodeName }}</span>
            <span class="log-text">{{ response.text?.substring(0, 100) }}...</span>
          </div>
        </div>
      </el-card>
    </div>
  </BaseLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { Search, Delete, CircleCheck, Loading, Document } from '@element-plus/icons-vue';
import BaseLayout from '@/layouts/BaseLayout.vue';
import GraphService from '@/services/graph';
import AgentService from '@/services/agent';

const testForm = ref({
  agentId: null,
  query: '',
});

const agents = ref([]);
const isProcessing = ref(false);
const routingInfo = ref(null);
const nodeBlocks = ref([]);
const rawResponses = ref([]);

// 加载智能体列表
onMounted(async () => {
  try {
    const response = await AgentService.getAgents();
    agents.value = response.data || [];
    if (agents.value.length > 0) {
      testForm.value.agentId = agents.value[0].id;
    }
  } catch (error) {
    console.error('加载智能体列表失败:', error);
    ElMessage.error('加载智能体列表失败');
  }
});

// 执行查询
const executeQuery = async () => {
  if (!testForm.value.agentId || !testForm.value.query) {
    ElMessage.warning('请选择智能体并输入查询内容');
    return;
  }

  isProcessing.value = true;
  routingInfo.value = null;
  nodeBlocks.value = [];
  rawResponses.value = [];

  console.log('=== 开始执行查询 ===');
  console.log('查询内容:', testForm.value.query);
  console.log('智能体ID:', testForm.value.agentId);

  // 推测查询类型（前端模拟）
  const queryLower = testForm.value.query.toLowerCase();
  const isSimple =
    queryLower.includes('查询') ||
    queryLower.includes('显示') ||
    queryLower.includes('列出') ||
    queryLower.includes('有多少');
  const isComplex =
    queryLower.includes('分析') ||
    queryLower.includes('趋势') ||
    queryLower.includes('对比') ||
    queryLower.includes('为什么');

  routingInfo.value = {
    queryType: isComplex ? 'COMPLEX' : isSimple ? 'SIMPLE' : 'COMPLEX',
  };

  console.log('预测路由类型:', routingInfo.value.queryType);

  const request = {
    agentId: testForm.value.agentId,
    query: testForm.value.query,
    humanFeedback: false,
    rejectedPlan: false,
    nl2sqlOnly: false,
  };

  try {
    let currentBlock = { nodeName: '', text: '', textType: 'TEXT', timestamp: new Date().toLocaleTimeString() };

    await GraphService.streamSearch(
      request,
      async response => {
        console.log('收到响应:', response);
        rawResponses.value.push(response);

        // 如果节点改变，创建新的块
        if (response.nodeName && response.nodeName !== currentBlock.nodeName) {
          if (currentBlock.nodeName) {
            nodeBlocks.value.push({ ...currentBlock });
          }
          currentBlock = {
            nodeName: response.nodeName,
            text: response.text || '',
            textType: response.textType,
            timestamp: new Date().toLocaleTimeString(),
          };
        } else {
          // 累积文本
          currentBlock.text += response.text || '';
        }
      },
      async error => {
        console.error('查询错误:', error);
        ElMessage.error('查询处理失败: ' + error.message);
        isProcessing.value = false;
      },
      async () => {
        console.log('=== 查询完成 ===');
        // 添加最后一个块
        if (currentBlock.nodeName) {
          nodeBlocks.value.push({ ...currentBlock });
        }
        ElMessage.success('查询完成');
        isProcessing.value = false;
      },
    );
  } catch (error) {
    console.error('执行查询失败:', error);
    ElMessage.error('执行查询失败: ' + error.message);
    isProcessing.value = false;
  }
};

// 清空结果
const clearResults = () => {
  routingInfo.value = null;
  nodeBlocks.value = [];
  rawResponses.value = [];
  testForm.value.query = '';
};

// 格式化节点名称
const formatNodeName = nodeName => {
  const nameMap = {
    IntentRecognitionNode: '意图识别',
    EvidenceRecallNode: '证据召回',
    QueryEnhanceNode: '查询增强',
    SchemaRecallNode: '表结构召回',
    TableRelationNode: '表关系分析',
    SqlGenerateNode: 'SQL生成',
    QuerySqlExecuteNode: 'SQL执行',
    SemanticConsistencyNode: '语义一致性检查',
    ReportGeneratorNode: '报告生成',
  };
  return nameMap[nodeName] || nodeName;
};

// 获取节点类型
const getNodeType = nodeName => {
  if (nodeName.includes('Error')) return 'danger';
  if (nodeName.includes('Execute')) return 'success';
  if (nodeName.includes('Generate')) return 'warning';
  return 'primary';
};

// 获取节点图标
const getNodeIcon = nodeName => {
  if (nodeName.includes('Execute')) return CircleCheck;
  if (nodeName.includes('Generate')) return Document;
  return Loading;
};

// 获取文本类型标签
const getTextTypeTag = textType => {
  const tagMap = {
    JSON: 'warning',
    SQL: 'success',
    TEXT: 'info',
    MARK_DOWN: 'primary',
  };
  return tagMap[textType] || 'info';
};

// 格式化 JSON
const formatJSON = text => {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
};
</script>

<style scoped>
.query-router-test {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
}

.header-card,
.result-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title {
  font-size: 18px;
  font-weight: bold;
}

.node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.node-name {
  font-weight: bold;
  font-size: 16px;
}

.node-content {
  margin-top: 10px;
  max-height: 300px;
  overflow: auto;
}

.node-content pre {
  background: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  overflow-x: auto;
}

.sql-content {
  color: #409eff;
}

.log-container {
  max-height: 400px;
  overflow-y: auto;
}

.log-entry {
  padding: 8px;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  gap: 10px;
  align-items: center;
}

.log-node {
  font-weight: bold;
  min-width: 150px;
  color: #409eff;
}

.log-text {
  color: #606266;
  font-family: monospace;
  font-size: 12px;
}
</style>
