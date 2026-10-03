<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const batches = ref([])
const selectedId = ref(null)
const flushes = ref([])
const inventories = ref([])
const inventoryTotal = ref(0)
const loading = ref(false)
const dialogVisible = ref(false)
const activeFlush = ref(null)
const saving = ref(false)
const requestKey = ref('')
const form = reactive({ harvestDate: '', gradeAYield: 0, gradeBYield: 0, gradeCYield: 0, harvestedBy: '' })

const selectedBatch = computed(() => batches.value.find(item => Number(item.id) === Number(selectedId.value)))
const remaining = computed(() => {
  if (!selectedBatch.value) return 0
  return Number(selectedBatch.value.expected_yield) - Number(selectedBatch.value.harvested_yield)
})

async function loadBatches() {
  loading.value = true
  try {
    batches.value = await farmApi.getFruitingBatches()
    if (!selectedId.value && batches.value.length) selectedId.value = batches.value[0].id
  } catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

async function loadFlushes() {
  if (!selectedId.value) return
  try { flushes.value = await farmApi.getFlushes(selectedId.value) }
  catch (error) { ElMessage.error(error.message) }
}

async function loadInventories() {
  if (!selectedId.value) return
  try {
    const data = await farmApi.getInventories(selectedId.value)
    inventories.value = data.rows || []
    inventoryTotal.value = Number(data.total || 0)
  } catch (error) { ElMessage.error(error.message) }
}

function newRequestKey() {
  return window.crypto?.randomUUID
    ? window.crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function openHarvest(flush) {
  if (selectedBatch.value?.status === '环境暂停') {
    ElMessage.warning('该批次因环境越限处置暂停生产，处置单解除后才能采收')
    return
  }
  activeFlush.value = flush
  form.harvestDate = flush.harvestDate || new Date().toISOString().slice(0, 10)
  form.gradeAYield = Number(flush.gradeAYield || 0)
  form.gradeBYield = Number(flush.gradeBYield || 0)
  form.gradeCYield = Number(flush.gradeCYield || 0)
  form.harvestedBy = flush.harvestedBy || ''
  // 每次打开表单生成一次请求标识：重复点击/网络重试都按同一单处理，不会重复入账
  requestKey.value = newRequestKey()
  dialogVisible.value = true
}

async function submitHarvest() {
  saving.value = true
  try {
    const result = await farmApi.harvest(selectedId.value, activeFlush.value.flushNo, {
      ...form,
      requestKey: requestKey.value
    })
    if (result.result === 'UNCHANGED') {
      ElMessage.info('与已入账记录一致，未产生新的库存流水')
    } else if (result.result === 'DUPLICATED') {
      ElMessage.warning('该请求已入账，重复提交已忽略')
    } else {
      const quantity = Number(result.inventory?.quantity || 0)
      ElMessage.success(quantity >= 0
        ? `采收已入账，库存 +${quantity.toFixed(1)} kg`
        : `更正已入账，库存冲正 ${quantity.toFixed(1)} kg`)
    }
    dialogVisible.value = false
    await loadBatches()
    await loadFlushes()
    await loadInventories()
  } catch (error) { ElMessage.error(error.message) }
  finally { saving.value = false }
}

function barHeight(flush) {
  const expected = Number(selectedBatch.value?.expected_yield || 1)
  return `${Math.max(10, Math.min(152, Number(flush.yieldKg || 0) / expected * 420))}px`
}

function flushName(no) {
  return ['一潮', '二潮', '三潮'][no - 1]
}

function signedKg(value) {
  const num = Number(value || 0)
  return `${num >= 0 ? '+' : ''}${num.toFixed(1)}`
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : ''
}

watch(selectedId, () => { loadFlushes(); loadInventories() })
onMounted(loadBatches)
</script>

<template>
  <section class="page timeline-page" v-loading="loading">
    <div class="page-heading timeline-heading">
      <div>
        <span class="eyebrow">HARVEST RHYTHM</span>
        <h1>出菇潮次时间轴</h1>
        <p>沿批次生长节奏记录一潮、二潮与三潮，产量柱随采收入账即时增长。</p>
      </div>
      <el-select v-model="selectedId" class="batch-switch" placeholder="选择出菇批次">
        <el-option v-for="batch in batches" :key="batch.id" :value="batch.id" :label="`${batch.batch_no} · ${batch.species}`" />
      </el-select>
    </div>

    <div v-if="selectedBatch" class="batch-brief">
      <div><span>当前批次</span><strong>{{ selectedBatch.batch_no }}</strong></div>
      <div><span>菇房</span><strong>{{ selectedBatch.room_code }}</strong></div>
      <div><span>菌包批次</span><strong>{{ selectedBatch.bag_batch_no }}</strong></div>
      <div><span>批次状态</span><strong :class="{ 'paused-text': selectedBatch.status === '环境暂停' }">{{ selectedBatch.status }}</strong></div>
      <div><span>预计产量</span><strong>{{ selectedBatch.expected_yield }} kg</strong></div>
      <div class="remaining"><span>剩余预产量</span><strong>{{ remaining.toFixed(1) }} kg</strong></div>
    </div>
    <div v-if="selectedBatch?.status === '环境暂停'" class="pause-banner">
      环境越限处置单未解除，该批次暂停生产与采收；解除后自动恢复原状态。
    </div>

    <div class="timeline-stage" v-if="flushes.length">
      <div class="timeline-rule"></div>
      <div v-for="flush in flushes" :key="flush.id || flush.flushNo" class="flush-stop" @click="openHarvest(flush)">
        <div class="yield-column-zone">
          <span class="yield-number">{{ Number(flush.yieldKg || 0).toFixed(1) }}<small>kg</small></span>
          <div class="yield-column" :class="{ empty: !Number(flush.yieldKg) }" :style="{ height: barHeight(flush) }">
            <i></i>
          </div>
        </div>
        <button class="timeline-node" :class="{ complete: flush.status === '已采收' }">
          <span>{{ flush.flushNo }}</span>
        </button>
        <div class="flush-meta">
          <h3>{{ flushName(flush.flushNo) }}</h3>
          <span>{{ flush.harvestDate || '等待采收' }}</span>
          <b>{{ flush.harvestedBy || '点击录入' }}</b>
        </div>
      </div>
    </div>

    <div class="grade-ledger" v-if="flushes.length">
      <div v-for="flush in flushes" :key="`grade-${flush.flushNo}`">
        <span>{{ flushName(flush.flushNo) }}等级构成</span>
        <div class="grade-bar">
          <i class="grade-a" :style="{ flex: Number(flush.gradeAYield || 0) }"></i>
          <i class="grade-b" :style="{ flex: Number(flush.gradeBYield || 0) }"></i>
          <i class="grade-c" :style="{ flex: Number(flush.gradeCYield || 0) }"></i>
        </div>
        <small>A {{ flush.gradeAYield }} / B {{ flush.gradeBYield }} / C {{ flush.gradeCYield }} kg</small>
      </div>
    </div>

    <div class="inventory-ledger" v-if="inventories.length">
      <div class="inventory-ledger-title">
        <span>库存流水</span>
        <small>更正只追加差额流水，历史全部保留；合计 {{ inventoryTotal.toFixed(1) }} kg，与批次累计采收一致</small>
      </div>
      <div v-for="row in inventories" :key="row.id" class="inventory-row" :class="{ reversal: row.entryType !== '入库' }">
        <div class="inventory-main">
          <b>{{ flushName(row.flushNo) }} · {{ row.entryType }}</b>
          <span>{{ row.gradeSummary }}</span>
        </div>
        <div class="inventory-side">
          <strong>{{ signedKg(row.quantity) }} kg</strong>
          <small>{{ row.operator }} · {{ formatTime(row.recordedAt) }}</small>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" width="520px" :title="`${flushName(activeFlush?.flushNo || 1)}${activeFlush?.status === '已采收' ? '采收更正' : '采收录入'}`" class="farm-dialog">
      <div v-if="activeFlush?.status === '已采收'" class="correct-hint">
        当前已入账 {{ Number(activeFlush?.yieldKg || 0).toFixed(1) }} kg；更正将按差额追加库存流水（增量入库 / 减量冲正），原记录保留可追溯。
      </div>
      <el-form label-position="top">
        <el-form-item label="采收日期"><el-date-picker v-model="form.harvestDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <div class="three-inputs">
          <el-form-item label="A级产量 kg"><el-input-number v-model="form.gradeAYield" :min="0" :precision="1" /></el-form-item>
          <el-form-item label="B级产量 kg"><el-input-number v-model="form.gradeBYield" :min="0" :precision="1" /></el-form-item>
          <el-form-item label="C级产量 kg"><el-input-number v-model="form.gradeCYield" :min="0" :precision="1" /></el-form-item>
        </div>
        <el-form-item label="采收人"><el-input v-model="form.harvestedBy" placeholder="输入采收负责人" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitHarvest">确认入账</el-button>
      </template>
    </el-dialog>
  </section>
</template>
