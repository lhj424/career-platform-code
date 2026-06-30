<template>
  <div class="talent-match-page">
    <!-- 页头 -->
    <div class="tm-header">
      <div class="tm-header-left">
        <el-button text round @click="$router.back()">
          <el-icon :size="18"><ArrowLeft /></el-icon>
        </el-button>
        <div>
          <h2 class="tm-title">
            <el-icon :size="20"><Aim /></el-icon>
            人才匹配结果
          </h2>
          <p class="tm-subtitle" v-if="jobTitle">{{ jobTitle }} · {{ companyName || '' }}</p>
        </div>
      </div>
      <div class="tm-header-right">
        <el-icon :size="28" color="#6366f1" class="tm-loading" v-if="loading"><Loading /></el-icon>
        <span v-else class="tm-match-count">{{ candidates.length }} / {{ totalCandidates }} 人匹配</span>
      </div>
    </div>

    <!-- 岗位技能要求 -->
    <div class="tm-skill-bar" v-if="jobSkills.length">
      <span class="tm-skill-bar-label">岗位技能要求：</span>
      <el-tag
        v-for="sk in jobSkills"
        :key="sk.skillId"
        :type="reqLevelType(sk.requiredLevel)"
        size="small"
        effect="plain"
        round
      >
        {{ sk.skillName }}
        <span class="tm-tag-level">{{ sk.requiredLevel }}</span>
        <span class="tm-tag-weight" v-if="sk.weight > 1">×{{ sk.weight }}</span>
      </el-tag>
    </div>

    <!-- 加载 -->
    <div v-if="loading" class="tm-loading-state">
      <el-skeleton v-for="i in 4" :key="i" :rows="3" animated style="margin-bottom:16px" />
    </div>

    <!-- 候选人列表 -->
    <div v-else-if="candidates.length > 0" class="tm-candidate-list">
      <div
        v-for="(cand, idx) in candidates"
        :key="cand.userId"
        class="tm-cand-card"
        :style="{ animationDelay: (idx * 0.08) + 's' }"
      >
        <div class="tmcc-left">
          <div class="tmcc-rank" :class="'rank-' + (idx + 1)">
            {{ idx + 1 }}
          </div>
          <div class="tmcc-avatar" :style="{ background: avatarBg(cand.username) }">
            {{ (cand.username || '?')[0]?.toUpperCase() }}
          </div>
        </div>

        <div class="tmcc-main">
          <div class="tmcc-name-row">
            <span class="tmcc-name">{{ cand.username }}</span>
            <el-tag
              :type="levelType(cand.matchLevel)"
              size="small"
              effect="plain"
              round
            >{{ cand.matchLevel }}</el-tag>
          </div>

          <!-- 匹配岗位标注 -->
          <div class="tmcc-job-match">
            <el-icon :size="14"><OfficeBuilding /></el-icon>
            <span class="tmcc-job-title">{{ cand.matchJobTitle || jobTitle }}</span>
            <span class="tmcc-job-score">{{ Math.round(cand.matchScore || 0) }}分</span>
          </div>

          <!-- 其他更适合的岗位 -->
          <div class="tmcc-other-jobs" v-if="cand.otherJobMatches?.length">
            <span class="tmcc-other-label">也适配：</span>
            <el-tag
              v-for="oj in cand.otherJobMatches.slice(0, 3)"
              :key="oj.jobId"
              size="small"
              effect="plain"
              round
              class="tm-other-job-tag"
              :class="{ 'tm-other-better': oj.score >= cand.matchScore }"
            >
              {{ oj.jobTitle }}
              <span class="tm-other-score" :style="{ color: oj.score >= cand.matchScore ? '#059669' : '#86909c' }">
                {{ Math.round(oj.score) }}分
              </span>
            </el-tag>
          </div>

          <div class="tmcc-meta" v-if="cand.education || cand.school">
            {{ [cand.education, cand.school, cand.major].filter(Boolean).join(' · ') }}
          </div>

          <!-- 掌握的技能 -->
          <div class="tmcc-mastered-skills" v-if="cand.masteredSkills?.length">
            <span class="tmcc-tag-label">掌握：</span>
            <el-tooltip
              v-for="sk in cand.masteredSkills"
              :key="sk.skillName"
              :content="sk.skillName + ' · ' + sk.level + ' · 来源:' + sk.sourceLabel + ' · 置信度:' + Math.round(sk.confidence * 100) + '%'"
              placement="top"
            >
              <el-tag
                size="small"
                effect="plain"
                round
                :class="'tm-sk-mastered tm-sk-src-' + sk.source?.toLowerCase()"
              >
                {{ sk.skillName }}
                <span class="tm-sk-lv">{{ sk.level }}</span>
              </el-tag>
            </el-tooltip>
          </div>

          <!-- 技能匹配/缺口 -->
          <div class="tmcc-skill-tags" v-if="cand.matchedSkills?.length || cand.gapSkills?.length">
            <span class="tmcc-tag-label">匹配：</span>
            <el-tag
              v-for="s in cand.matchedSkills"
              :key="s"
              size="small"
              effect="plain"
              round
              class="tm-sk-matched"
            >✓ {{ s }}</el-tag>
            <template v-if="cand.gapSkills?.length">
              <span class="tmcc-tag-label gap">缺口：</span>
              <el-tag
                v-for="s in cand.gapSkills"
                :key="s"
                size="small"
                effect="plain"
                round
                class="tm-sk-gap"
              >✗ {{ s }}</el-tag>
            </template>
          </div>

          <p class="tmcc-reason" v-if="cand.recommendReason">{{ cand.recommendReason }}</p>
        </div>

        <div class="tmcc-right">
          <div class="tmcc-match-circle" :style="circleStyle(cand.matchScore)">
            <span class="tmcc-match-num">{{ Math.round(cand.matchScore || 0) }}</span>
            <span class="tmcc-match-unit">分</span>
          </div>
          <div class="tmcc-sub-scores">
            <div class="tmcc-sub-item">
              <span class="tmcc-sub-label">技能</span>
              <span class="tmcc-sub-val" style="color:#3b82f6">{{ Math.round(cand.skillScore || 0) }}</span>
            </div>
            <div class="tmcc-sub-item">
              <span class="tmcc-sub-label">测评</span>
              <span class="tmcc-sub-val" style="color:#8b5cf6">{{ Math.round(cand.assessmentScore || 0) }}</span>
            </div>
            <div class="tmcc-sub-item">
              <span class="tmcc-sub-label">掌握</span>
              <span class="tmcc-sub-val" style="color:#10b981">{{ Math.round(cand.masteryScore || 0) }}</span>
            </div>
            <div class="tmcc-sub-item">
              <span class="tmcc-sub-label">基础</span>
              <span class="tmcc-sub-val" style="color:#f59e0b">{{ Math.round(cand.basicScore || 0) }}</span>
            </div>
          </div>
          <el-button
            type="primary"
            size="small"
            round
            class="tmcc-chat-btn"
            @click="openChat(cand)"
          >
            <el-icon :size="13"><ChatDotRound /></el-icon>
            沟通
          </el-button>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="tm-empty">
      <el-empty description="暂无匹配候选人" :image-size="120">
        <template #extra>
          <p style="color:#9ca3af;font-size:13px">该岗位当前没有满足匹配条件的学生</p>
        </template>
      </el-empty>
    </div>

    <!-- 聊天沟通弹窗 -->
    <el-dialog
      v-model="chatVisible"
      :title="chatTitle"
      width="480px"
      class="tm-chat-dialog"
      :close-on-click-modal="false"
      @opened="onChatOpened"
    >
      <!-- 消息列表 -->
      <div class="tm-chat-messages" ref="chatMsgRef">
        <div v-if="chatLoading && !messages.length" class="tm-chat-loading">
          <el-icon :size="24" class="is-loading"><Loading /></el-icon>
          <span>加载中...</span>
        </div>
        <template v-for="msg in messages" :key="msg.id">
          <div class="tm-msg-row" :class="{ 'tm-msg-self': msg.senderRole === 'HR' }">
            <div class="tm-msg-avatar" :style="{ background: msg.senderRole === 'HR' ? '#6366f1' : avatarBg(msg.senderName) }">
              {{ (msg.senderName || '?')[0]?.toUpperCase() }}
            </div>
            <div class="tm-msg-bubble">{{ msg.content }}</div>
            <span class="tm-msg-time">{{ formatTime(msg.createdAt) }}</span>
          </div>
        </template>
        <div v-if="!messages.length && !chatLoading" class="tm-chat-empty">暂无消息，开始和候选人聊聊吧</div>
      </div>

      <!-- 输入区域 -->
      <div class="tm-chat-input-area">
        <el-input
          v-model="msgInput"
          placeholder="输入消息..."
          :disabled="chatSending"
          @keyup.enter="sendChatMessage"
          maxlength="500"
          show-word-limit
        />
        <el-button type="primary" :loading="chatSending" :disabled="!msgInput.trim()" @click="sendChatMessage">
          发送
        </el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Aim, ArrowLeft, Loading, OfficeBuilding, ChatDotRound } from '@element-plus/icons-vue'
import { matchByJob } from '@/api/enterprise'
import { hrInitiateConversation, sendMessage as sendMsg, getMessages } from '@/api/chat'
import type { ChatMessage } from '@/types/chat'
import { ElMessage } from 'element-plus'

const route = useRoute()
const jobId = Number(route.params.id)

const loading = ref(false)
const jobTitle = ref('')
const companyName = ref('')
const totalCandidates = ref(0)
const jobSkills = ref<any[]>([])
const candidates = ref<any[]>([])

// ========== 聊天相关状态 ==========
const chatVisible = ref(false)
const chatLoading = ref(false)
const chatSending = ref(false)
const msgInput = ref('')
const messages = ref<ChatMessage[]>([])
const chatMsgRef = ref<HTMLDivElement>()
let currentConversationId: number | null = null
let currentStudentId: number | null = null
const chatTitle = ref('')

// ========== 工具函数 ==========
const avatarColors = ['#165DFF', '#722ED1', '#00B42A', '#FF7D00', '#F53F3F', '#7B61FF', '#16C8C8', '#F77234']

function avatarBg(name: string): string {
  let hash = 0
  for (let i = 0; i < (name || '').length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash)
  return avatarColors[Math.abs(hash) % avatarColors.length]
}

function formatTime(timeStr: string): string {
  const d = new Date(timeStr)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function levelType(level: string): string {
  if (level === '非常匹配') return 'success'
  if (level === '比较匹配') return 'primary'
  if (level === '一般匹配') return 'warning'
  if (level === '部分匹配') return 'info'
  return 'danger'
}

function reqLevelType(level: string): string {
  if (level === '精通') return 'danger'
  if (level === '熟练') return 'warning'
  if (level === '掌握') return 'success'
  return 'info'
}

function circleStyle(score: number) {
  const s = Math.round(score || 0)
  let color: string
  if (s >= 80) color = '#10b981'
  else if (s >= 60) color = '#3b82f6'
  else if (s >= 40) color = '#f59e0b'
  else color = '#ef4444'
  return {
    borderColor: color,
    boxShadow: `0 0 0 3px ${color}22`
  }
}

// ========== 聊天功能 ==========

/** 打开聊天窗口 */
async function openChat(cand: any) {
  currentStudentId = cand.userId
  chatTitle.value = `与 ${cand.username} 沟通 · ${jobTitle.value}`
  messages.value = []
  msgInput.value = ''
  chatVisible.value = true
  chatLoading.value = true

  try {
    // HR 发起会话（如果已存在则返回已有会话）
    const convRes: any = await hrInitiateConversation({
      studentId: cand.userId,
      jobId: jobId,
      jobTitle: jobTitle.value,
    })
    if (convRes.code === 200 && convRes.data) {
      currentConversationId = convRes.data.id
      // 加载历史消息
      await loadChatHistory()
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '打开对话失败')
    chatVisible.value = false
  } finally {
    chatLoading.value = false
  }
}

/** 加载历史消息 */
async function loadChatHistory() {
  if (!currentConversationId) return
  try {
    const res: any = await getMessages(currentConversationId, 1, 50)
    if (res.code === 200) {
      messages.value = res.data || []
      scrollToBottom()
    }
  } catch { /* ignore */ }
}

/** 发送消息 */
async function sendChatMessage() {
  const content = msgInput.value.trim()
  if (!content || !currentConversationId || chatSending.value) return

  // 乐观显示
  const fakeMsg: ChatMessage = {
    id: -(Date.now()),
    conversationId: currentConversationId,
    senderId: 0,
    senderName: '我',
    senderRole: 'HR' as const,
    receiverId: currentStudentId!,
    content,
    msgType: 'TEXT' as const,
    isRead: 0,
    createdAt: new Date().toISOString(),
  }
  messages.value.push(fakeMsg)
  msgInput.value = ''
  scrollToBottom()

  chatSending.value = true
  try {
    const res: any = await sendMsg({
      conversationId: currentConversationId,
      content,
      msgType: 'TEXT',
    })
    if (res.code === 200 && res.data) {
      // 用服务端返回的消息替换乐观消息
      const idx = messages.value.findIndex(m => m.id === fakeMsg.id)
      if (idx >= 0) messages.value[idx] = res.data
    } else {
      messages.value.pop()
      ElMessage.error(res.message || '发送失败')
    }
  } catch {
    messages.value.pop()
    ElMessage.error('发送失败，请重试')
  } finally {
    chatSending.value = false
    scrollToBottom()
  }
}

/** 滚动到底部 */
async function scrollToBottom() {
  await nextTick()
  if (chatMsgRef.value) {
    chatMsgRef.value.scrollTop = chatMsgRef.value.scrollHeight
  }
}

/** 聊天弹窗打开后 */
function onChatOpened() {
  scrollToBottom()
}

// ========== 数据加载 ==========

async function loadMatchResults() {
  loading.value = true
  try {
    const res: any = await matchByJob(jobId)
    const data = res.data || {}
    jobTitle.value = data.jobTitle || ''
    companyName.value = data.companyName || ''
    totalCandidates.value = data.totalCandidates || 0
    jobSkills.value = data.jobSkills || []
    candidates.value = data.candidates || []
  } catch {
    candidates.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadMatchResults)
</script>

<style scoped>
.talent-match-page { max-width: 1000px; margin: 0 auto; padding-bottom: 40px; }

/* Header */
.tm-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 20px; flex-wrap: wrap; gap: 12px;
}
.tm-header-left { display: flex; align-items: center; gap: 12px; }
.tm-title { font-size: 20px; font-weight: 700; color: #1d2129; margin: 0; display: flex; align-items: center; gap: 8px; }
.tm-subtitle { font-size: 13px; color: #86909c; margin: 2px 0 0; }
.tm-match-count { font-size: 14px; font-weight: 600; color: #6366f1; padding: 6px 16px; background: #eef2ff; border-radius: 20px; }
.tm-loading { animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* Skill bar */
.tm-skill-bar {
  background: #fff; border-radius: 12px; padding: 14px 20px;
  border: 1px solid #f0f0f0; display: flex; align-items: center;
  flex-wrap: wrap; gap: 8px; margin-bottom: 20px;
}
.tm-skill-bar-label { font-size: 13px; font-weight: 600; color: #4e5969; }
.tm-tag-level { opacity: 0.6; margin-left: 2px; font-size: 11px; }
.tm-tag-weight { opacity: 0.5; margin-left: 2px; font-size: 10px; }

/* Loading */
.tm-loading-state { background: #fff; border-radius: 14px; padding: 24px; border: 1px solid #f0f0f0; }

/* Candidate list */
.tm-candidate-list { display: flex; flex-direction: column; gap: 10px; }

.tm-cand-card {
  background: #fff; border-radius: 14px; padding: 18px 20px;
  border: 1px solid #f0f0f0; display: flex; align-items: flex-start;
  gap: 16px; transition: all 0.3s; opacity: 0;
  animation: fadeInUp 0.4s ease forwards;
}
.tm-cand-card:hover { border-color: #c7d2fe; box-shadow: 0 4px 16px rgba(0,0,0,0.06); transform: translateY(-2px); }

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}

/* Left section */
.tmcc-left { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.tmcc-rank {
  width: 28px; height: 28px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700; color: #86909c;
  background: #f2f3f5;
}
.tmcc-rank.rank-1 { background: #fff7e6; color: #d46b08; }
.tmcc-rank.rank-2 { background: #f2f3f5; color: #4e5969; }
.tmcc-rank.rank-3 { background: #fdf0e0; color: #c9721a; }

.tmcc-avatar {
  width: 44px; height: 44px; border-radius: 12px; color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 700; flex-shrink: 0;
}

/* Main section */
.tmcc-main { flex: 1; min-width: 0; }
.tmcc-name-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tmcc-name { font-size: 15px; font-weight: 700; color: #1d2129; }
.tmcc-meta { font-size: 12px; color: #86909c; margin-bottom: 8px; }

/* 匹配岗位标注 */
.tmcc-job-match {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; color: #6366f1; margin-bottom: 6px;
  padding: 2px 0;
}
.tmcc-job-title { font-weight: 600; }
.tmcc-job-score {
  font-size: 12px; color: #fff; background: #6366f1;
  padding: 1px 8px; border-radius: 10px; font-weight: 600;
}

/* 其他适配岗位 */
.tmcc-other-jobs {
  display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin-bottom: 6px;
}
.tmcc-other-label { font-size: 11px; color: #9ca3af; }
.tm-other-job-tag { font-size: 11px; cursor: default; }
.tm-other-better { border-color: #059669 !important; }
.tm-other-score { font-size: 10px; margin-left: 2px; font-weight: 600; }

.tmcc-skill-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; margin-bottom: 8px; }
.tmcc-tag-label { font-size: 12px; color: #86909c; }
.tmcc-tag-label.gap { margin-left: 8px; }
.tm-sk-matched { color: #059669 !important; background: #ecfdf5 !important; border-color: #a7f3d0 !important; }
.tm-sk-gap { color: #d97706 !important; background: #fffbeb !important; border-color: #fde68a !important; }

/* Mastered skills */
.tmcc-mastered-skills { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; margin-bottom: 8px; }
.tm-sk-mastered { font-weight: 500; cursor: default; }
.tm-sk-lv { opacity: 0.55; font-size: 10px; margin-left: 2px; }
/* 来源颜色 */
.tm-sk-src-test { color: #059669 !important; background: #ecfdf5 !important; border-color: #a7f3d0 !important; }
.tm-sk-src-profile { color: #2563eb !important; background: #eff6ff !important; border-color: #bfdbfe !important; }
.tm-sk-src-learning { color: #7c3aed !important; background: #f5f3ff !important; border-color: #ddd6fe !important; }

.tmcc-reason { font-size: 13px; color: #6b7280; margin: 0; line-height: 1.5; }

/* Right section */
.tmcc-right { display: flex; flex-direction: column; align-items: center; gap: 8px; flex-shrink: 0; min-width: 80px; }
.tmcc-match-circle {
  width: 64px; height: 64px; border-radius: 50%;
  border: 3px solid #3b82f6; display: flex;
  flex-direction: column; align-items: center; justify-content: center;
}
.tmcc-match-num { font-size: 20px; font-weight: 800; color: #1d2129; line-height: 1; }
.tmcc-match-unit { font-size: 10px; color: #86909c; }

.tmcc-sub-scores { display: grid; grid-template-columns: 1fr 1fr; gap: 2px 8px; }
.tmcc-sub-item { display: flex; align-items: center; gap: 3px; font-size: 11px; }
.tmcc-sub-label { color: #86909c; }
.tmcc-sub-val { font-weight: 700; }

/* Empty */
.tm-empty { background: #fff; border-radius: 14px; padding: 60px; border: 1px solid #f0f0f0; text-align: center; }

/* 沟通按钮 */
.tmcc-chat-btn {
  margin-top: 6px;
  font-size: 12px;
  padding: 4px 12px;
  height: auto;
}

/* 聊天弹窗 */
.tm-chat-dialog :deep(.el-dialog__header) { border-bottom: 1px solid #f0f0f0; padding: 16px 20px; margin-right: 0; }
.tm-chat-dialog :deep(.el-dialog__body) { padding: 0; }
.tm-chat-messages {
  height: 360px;
  overflow-y: auto;
  padding: 16px 20px;
  background: #f8fafc;
}
.tm-chat-loading, .tm-chat-empty {
  display: flex; align-items: center; justify-content: center;
  gap: 8px; color: #9ca3af; font-size: 13px; height: 100%;
}

/* 消息行 */
.tm-msg-row {
  display: flex; align-items: flex-start; gap: 8px; margin-bottom: 16px;
}
.tm-msg-row.tm-msg-self { flex-direction: row-reverse; }
.tm-msg-avatar {
  width: 32px; height: 32px; border-radius: 50%; color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 600; flex-shrink: 0;
}
.tm-msg-bubble {
  max-width: 260px; padding: 10px 14px; border-radius: 12px;
  font-size: 14px; line-height: 1.5; word-break: break-word;
  background: #fff; color: #1d2129; box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.tm-msg-self .tm-msg-bubble { background: #6366f1; color: #fff; }
.tm-msg-time { font-size: 11px; color: #c4c8cf; flex-shrink: 0; margin-top: 6px; }

/* 输入区域 */
.tm-chat-input-area {
  display: flex; gap: 10px; padding: 14px 20px;
  border-top: 1px solid #f0f0f0; align-items: center;
}
.tm-chat-input-area .el-input { flex: 1; }

@media (max-width: 768px) {
  .tm-cand-card { flex-direction: column; }
  .tmcc-right { flex-direction: row; gap: 16px; }
}
</style>
