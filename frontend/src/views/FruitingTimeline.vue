<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const batches = ref([])
const selectedId = ref(null)
const flushes = ref([])
const inventories = ref([])
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

// 批次维度的等级合计（取已录潮次明细之和，与后端批次累计同一口径）
const gradeTotals = computed(() => {
  const sum = key => flushes.value.reduce((acc, flush) => acc + Number(flush[key] || 0), 0)
  return { a: sum('gradeAYield'), b: sum('gradeBYield'), c: sum('gradeCYield') }
})

const inventoryGroups = computed(() => {
  return flushes.value.map(flush => ({
    flush,
    rows: inventories.value
      .filter(row => Number(row.flushNo) === Number(flush.flushNo))
      .sort((a, b) => Number(a.id) - Number(b.id))
  })).filter(group => group.rows.length)
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
  try {
    const [flushList, inventoryList] = await Promise.all([
      farmApi.getFlushes(selectedId.value),
      farmApi.getHarvestInventory(selectedId.value)
    ])
    flushes.value = flushList
    inventories.value = inventoryList
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
  // 每次打开表单生成一次请求标识：双击、网络重试、刷新后重发同一标识都只生效一次
  requestKey.value = newRequestKey()
  dialogVisible.value = true
}

async function submitHarvest() {
  if (!form.harvestDate) return ElMessage.warning('请选择采收日期')
  if (!form.harvestedBy.trim()) return ElMessage.warning('采收人不能为空')
  const total = Number(form.gradeAYield || 0) + Number(form.gradeBYield || 0) + Number(form.gradeCYield || 0)
  if (total <= 0) return ElMessage.warning('本潮采收总量必须大于 0 kg')
  const otherFlushes = flushes.value
    .filter(item => Number(item.flushNo) !== Number(activeFlush.value.flushNo))
    .reduce((acc, item) => acc + Number(item.yieldKg || 0), 0)
  const expected = Number(selectedBatch.value.expected_yield || 0)
  if (otherFlushes + total > expected) {
    return ElMessage.warning(`超过剩余预产量，本潮最多可录入 ${Math.max(0, expected - otherFlushes).toFixed(1)} kg`)
  }
  saving.value = true
  try {
    const result = await farmApi.harvest(selectedId.value, activeFlush.value.flushNo, {
      requestKey: requestKey.value,
      ...form
    })
    // 零变化重提、增产/减产更正、重复请求回放，都展示服务端返回的明确结论
    ElMessage.success(result?.message || '采收已入账')
    dialogVisible.value = false
    await loadBatches()
    await loadFlushes()
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

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : ''
}

function qtyClass(row) {
  const v = Number(row.quantity || 0)
  if (v < 0) return 'qty-reversal'
  if (row.changeType === '更正入库') return 'qty-correction'
  return 'qty-in'
}

function formatQty(value) {
  const n = Number(value || 0)
  return `${n > 0 ? '+' : ''}${n.toFixed(1)}`
}

watch(selectedId, loadFlushes)
onMounted(loadBatches)
</script>

<template>
  <section class="page timeline-page" v-loading="loading">
    <div class="page-heading timeline-heading">
      <div>
        <span class="eyebrow">HARVEST RHYTHM</span>
        <h1>出菇潮次时间轴</h1>
        <p>沿批次生长节奏记录一潮、二潮与三潮，产量柱随采收入账即时增长；更正以冲正流水留痕，不会重复计账。</p>
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
    <div v-if="selectedBatch" class="harvest-totals">
      <div class="total-item">
        <span>累计采收</span>
        <strong>{{ Number(selectedBatch.harvested_yield || 0).toFixed(1) }} <small>/ {{ selectedBatch.expected_yield }} kg</small></strong>
      </div>
      <div class="total-item grade-a-total"><span>A 级合计</span><strong>{{ gradeTotals.a.toFixed(1) }} kg</strong></div>
      <div class="total-item grade-b-total"><span>B 级合计</span><strong>{{ gradeTotals.b.toFixed(1) }} kg</strong></div>
      <div class="total-item grade-c-total"><span>C 级合计</span><strong>{{ gradeTotals.c.toFixed(1) }} kg</strong></div>
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
          <em v-if="Number(flush.revisionNo || 1) > 1" class="revision-badge">第 {{ flush.revisionNo }} 版</em>
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
        <span>{{ flushName(flush.flushNo) }}等级构成<small v-if="Number(flush.revisionNo || 1) > 1"> · 已更正 {{ Number(flush.revisionNo) - 1 }} 次</small></span>
        <div class="grade-bar">
          <i class="grade-a" :style="{ flex: Number(flush.gradeAYield || 0) }"></i>
          <i class="grade-b" :style="{ flex: Number(flush.gradeBYield || 0) }"></i>
          <i class="grade-c" :style="{ flex: Number(flush.gradeCYield || 0) }"></i>
        </div>
        <small>A {{ flush.gradeAYield }} / B {{ flush.gradeBYield }} / C {{ flush.gradeCYield }} kg</small>
      </div>
    </div>

    <div class="inventory-ledger" v-if="inventoryGroups.length">
      <h3>对应库存变化</h3>
      <p class="ledger-hint">库存只追加不改写：更正时先“冲正”抵消旧账，再记“更正入库”，净额始终等于当前潮次产量。</p>
      <div v-for="group in inventoryGroups" :key="`inv-${group.flush.flushNo}`" class="inventory-group">
        <h4>{{ flushName(group.flush.flushNo) }} · 当前库存 {{ Number(group.flush.yieldKg || 0).toFixed(1) }} kg</h4>
        <table>
          <thead>
            <tr><th>时间</th><th>类型</th><th>变化量</th><th>库存余额</th><th>等级构成</th><th>操作人</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in group.rows" :key="row.id">
              <td>{{ formatTime(row.recordedAt) }}</td>
              <td><span class="change-tag" :class="`tag-${row.changeType}`">{{ row.changeType }}<small v-if="row.changeType !== '入库'"> v{{ row.revisionNo }}</small></span></td>
              <td :class="qtyClass(row)">{{ formatQty(row.quantity) }} kg</td>
              <td>{{ Number(row.balanceQty || 0).toFixed(1) }} kg</td>
              <td class="summary-cell">{{ row.gradeSummary }}</td>
              <td>{{ row.operator }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" width="520px" :title="`${flushName(activeFlush?.flushNo || 1)}采收录入`" class="farm-dialog">
      <el-form label-position="top">
        <el-form-item label="采收日期"><el-date-picker v-model="form.harvestDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <div class="three-inputs">
          <el-form-item label="A级产量 kg"><el-input-number v-model="form.gradeAYield" :min="0" :precision="1" /></el-form-item>
          <el-form-item label="B级产量 kg"><el-input-number v-model="form.gradeBYield" :min="0" :precision="1" /></el-form-item>
          <el-form-item label="C级产量 kg"><el-input-number v-model="form.gradeCYield" :min="0" :precision="1" /></el-form-item>
        </div>
        <el-form-item label="采收人"><el-input v-model="form.harvestedBy" placeholder="输入采收负责人" /></el-form-item>
        <p class="dialog-hint" v-if="Number(activeFlush?.revisionNo || 1) > 1">该潮次已更正过，本次更正将以“冲正 + 更正入库”流水留痕，原记录保留可查。</p>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitHarvest">确认入账</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.harvest-totals { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-top: 14px; }
.total-item { padding: 13px 18px; background: #fffefa; border: 1px solid #dcddd0; border-top: 3px solid #8f8a42; }
.total-item span { display: block; color: #8a8d7e; font-size: 10px; letter-spacing: .08em; }
.total-item strong { display: block; margin-top: 6px; font-size: 18px; color: #343829; }
.total-item small { font-size: 11px; color: #9a9d8c; }
.grade-a-total { border-top-color: #6f8a3c; }
.grade-b-total { border-top-color: #b08d3e; }
.grade-c-total { border-top-color: #9a7b56; }
.revision-badge { position: absolute; top: -6px; right: 6px; font-style: normal; font-size: 10px; color: #7c6a26; background: #f0e6a8; padding: 2px 7px; border-radius: 9px; }
.yield-column-zone { position: relative; }
.inventory-ledger { margin-top: 22px; background: #fffefa; border: 1px solid #dcddd0; padding: 18px 20px; }
.inventory-ledger h3 { margin: 0 0 4px; font-size: 15px; color: #343829; }
.ledger-hint { margin: 0 0 12px; font-size: 11px; color: #8a8d7e; }
.inventory-group + .inventory-group { margin-top: 16px; }
.inventory-group h4 { margin: 0 0 8px; font-size: 12px; color: #5c5f4e; }
.inventory-group table { width: 100%; border-collapse: collapse; font-size: 12px; }
.inventory-group th { text-align: left; color: #8a8d7e; font-weight: normal; padding: 7px 10px; background: #f1f1e8; border-bottom: 1px solid #e3e4da; }
.inventory-group td { padding: 8px 10px; border-bottom: 1px solid #eeefe6; color: #3f4233; }
.summary-cell { color: #6d705f; }
.change-tag { display: inline-block; padding: 2px 8px; border-radius: 3px; font-size: 11px; }
.change-tag small { opacity: .75; }
.tag-入库 { background: #e6efd8; color: #4f6a2a; }
.tag-更正入库 { background: #f2e9c4; color: #7c6a26; }
.tag-冲正 { background: #f3e0db; color: #8a3b2c; }
.qty-in { color: #4f6a2a; font-weight: 600; }
.qty-correction { color: #8a7320; font-weight: 600; }
.qty-reversal { color: #a2452f; font-weight: 600; }
.dialog-hint { margin: 4px 0 0; font-size: 11px; color: #8a7320; background: #faf3dd; padding: 8px 10px; border-radius: 4px; }
</style>
