package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.FruitingBatch;
import com.fungus.farm.domain.HarvestFlush;
import com.fungus.farm.domain.HarvestInventory;
import com.fungus.farm.dto.HarvestRequest;
import com.fungus.farm.dto.HarvestResult;
import com.fungus.farm.repository.FruitingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采收与采收更正：
 * 一次入账 = 锁批次行 + 幂等检查 + 更新潮次等级合计 + 条件更新批次累计 + 追加一条差额库存流水，
 * 全部在同一事务内落库，任何一步失败整体回滚，不会只更新其中一部分。
 * request_key 幂等防重复提交；库存流水只追加不覆盖（入库/冲正/调整），历史可追溯且不会重复计入；
 * 并发更正按批次行锁串行，差额基于锁内最新值计算，不会突破剩余预产量。
 */
@Service
public class FruitingService {
    private static final String ENTRY_IN = "入库";
    private static final String ENTRY_REVERSAL = "冲正";
    private static final String ENTRY_ADJUST = "调整";

    private final FruitingRepository repository;

    public FruitingService(FruitingRepository repository) {
        this.repository = repository;
    }

    public List<Map<String, Object>> batches() {
        return repository.findAllWithHarvests();
    }

    public List<HarvestFlush> flushes(Long batchId) {
        batch(batchId);
        return repository.findFlushes(batchId);
    }

    /** 批次库存流水与合计：合计 = 各流水之和，与批次累计采收同口径，可直接对账 */
    public Map<String, Object> inventories(Long batchId) {
        batch(batchId);
        List<HarvestInventory> rows = repository.findInventories(batchId);
        BigDecimal total = rows.stream()
                .map(row -> zero(row.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("rows", rows, "total", total);
    }

    public List<Map<String, Object>> roomYieldStats() {
        return repository.yieldStatsByRoom();
    }

    @Transactional
    public HarvestResult harvest(Long batchId, Integer flushNo, HarvestRequest request) {
        if (flushNo == null || flushNo < 1 || flushNo > 3) {
            throw new BizException("潮次只能是一潮、二潮或三潮");
        }
        if (request.requestKey() == null || request.requestKey().isBlank()) {
            throw new BizException("缺少请求标识，请刷新页面后重新提交");
        }
        String requestKey = request.requestKey().trim();
        if (requestKey.length() > 64) {
            throw new BizException("请求标识过长");
        }
        BigDecimal gradeA = zero(request.gradeAYield());
        BigDecimal gradeB = zero(request.gradeBYield());
        BigDecimal gradeC = zero(request.gradeCYield());
        if (gradeA.signum() < 0 || gradeB.signum() < 0 || gradeC.signum() < 0) {
            throw new BizException("各等级采收量不得为负数");
        }
        BigDecimal total = gradeA.add(gradeB).add(gradeC);
        if (total.signum() <= 0) {
            throw new BizException("本潮采收总量必须大于 0 kg");
        }
        if (request.harvestedBy() == null || request.harvestedBy().isBlank()) {
            throw new BizException("采收人不能为空");
        }
        String harvestedBy = request.harvestedBy().trim();
        if (harvestedBy.length() > 40) {
            throw new BizException("采收人姓名不能超过 40 字");
        }

        // 锁批次行：同一批次的采收/更正/撤回分配串行，剩余预产量判断与累计更新不会被并发打断
        FruitingBatch batch = repository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new BizException("出菇批次不存在"));
        if ("环境暂停".equals(batch.getStatus())) {
            throw new BizException("该批次因环境越限处置暂停生产，处置单解除后才能采收");
        }

        // 幂等：同一请求标识只入账一次。重复提交/网络重试直接返回当前潮次与首次流水，不再产生新流水
        var recorded = repository.findInventoryByRequestKey(requestKey);
        if (recorded.isPresent()) {
            HarvestInventory existing = recorded.get();
            if (!existing.getFruitingBatchId().equals(batchId) || !existing.getFlushNo().equals(flushNo)) {
                throw new BizException("请求标识已被其他采收记录占用，请刷新页面后重新提交");
            }
            HarvestFlush current = repository.findFlush(batchId, flushNo)
                    .orElseThrow(() -> new BizException("该请求已入账，但潮次记录缺失，请核对数据"));
            return new HarvestResult(current, existing, "DUPLICATED");
        }

        LocalDate harvestDate = request.harvestDate() == null ? LocalDate.now() : request.harvestDate();
        HarvestFlush flush = repository.findFlush(batchId, flushNo).orElseGet(() -> {
            HarvestFlush item = new HarvestFlush();
            item.setBatchId(batchId);
            item.setFlushNo(flushNo);
            item.setYieldKg(BigDecimal.ZERO);
            return item;
        });
        BigDecimal oldYield = zero(flush.getYieldKg());
        BigDecimal oldGradeA = zero(flush.getGradeAYield());
        BigDecimal oldGradeB = zero(flush.getGradeBYield());
        BigDecimal oldGradeC = zero(flush.getGradeCYield());
        BigDecimal currentHarvested = zero(batch.getHarvestedYield());
        BigDecimal newHarvested = currentHarvested.subtract(oldYield).add(total);
        if (newHarvested.compareTo(batch.getExpectedYield()) > 0) {
            BigDecimal remaining = batch.getExpectedYield().subtract(currentHarvested).add(oldYield);
            throw new BizException("本潮采收量超过批次剩余预产量，当前最多可录入 " + remaining + " kg");
        }

        // 零变化重提：等级、日期、采收人与已入账值完全一致时不产生新流水，避免重复计入与 0 数量垃圾行
        if (flush.getId() != null && "已采收".equals(flush.getStatus())
                && oldGradeA.compareTo(gradeA) == 0
                && oldGradeB.compareTo(gradeB) == 0
                && oldGradeC.compareTo(gradeC) == 0
                && Objects.equals(flush.getHarvestDate(), harvestDate)
                && Objects.equals(flush.getHarvestedBy(), harvestedBy)) {
            return new HarvestResult(flush, null, "UNCHANGED");
        }

        flush.setHarvestDate(harvestDate);
        flush.setGradeAYield(gradeA);
        flush.setGradeBYield(gradeB);
        flush.setGradeCYield(gradeC);
        flush.setYieldKg(total);
        flush.setHarvestedBy(harvestedBy);
        flush.setStatus("已采收");
        if (flush.getId() == null) {
            flush.setId(repository.saveFlush(flush));
        } else {
            repository.updateFlush(flush);
        }
        // 原子兜底：即使行锁被绕过也不会把累计采收写到预产量之上
        if (repository.updateBatchYield(batchId, newHarvested) == 0) {
            throw new BizException("批次剩余预产量不足，请刷新后重试");
        }

        // 追加差额流水（不覆盖历史）：首次/增量为正数入库，减量更正为负数冲正，
        // 总量不变仅等级调整为零数量调整行；库存合计 = 各流水之和 = 批次累计采收
        BigDecimal diff = total.subtract(oldYield);
        HarvestInventory inventory = new HarvestInventory();
        inventory.setFruitingBatchId(batchId);
        inventory.setFlushNo(flushNo);
        inventory.setEntryType(diff.signum() > 0 ? ENTRY_IN : diff.signum() < 0 ? ENTRY_REVERSAL : ENTRY_ADJUST);
        inventory.setQuantity(diff);
        inventory.setGradeSummary("A:" + signed(gradeA.subtract(oldGradeA)) + "kg / B:"
                + signed(gradeB.subtract(oldGradeB)) + "kg / C:" + signed(gradeC.subtract(oldGradeC)) + "kg");
        inventory.setOperator(harvestedBy);
        inventory.setRequestKey(requestKey);
        inventory.setRecordedAt(LocalDateTime.now());
        inventory.setId(repository.saveInventory(inventory));
        return new HarvestResult(flush, inventory, "RECORDED");
    }

    private FruitingBatch batch(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException("出菇批次不存在"));
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 差额带符号格式化（+10 / -5 / +0），流水行自洽：数量 = 各等级差额之和 */
    private String signed(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.signum() >= 0 ? "+" : "") + stripped.toPlainString();
    }
}
