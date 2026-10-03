package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.FruitingBatch;
import com.fungus.farm.domain.HarvestFlush;
import com.fungus.farm.domain.HarvestInventory;
import com.fungus.farm.domain.HarvestRequestLog;
import com.fungus.farm.dto.HarvestRequest;
import com.fungus.farm.repository.FruitingRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采收与采收更正：
 * 一次提交在同一事务内完成“幂等判定 → 锁定批次/潮次 → 剩余预产量校验 → 等级合计 →
 * 批次累计 → 库存流水 → 幂等落账”，任一步失败整体回滚，不会只更新一半数据。
 *
 * 库存采用只追加账本：首录写一条“入库”；更正时先追加一条“冲正”按旧等级构成抵消，
 * 再追加一条“更正入库”，潮次流水净额恒等于当前产量，减产只产生成对冲抵、不产生负数库存余额。
 * request_key 幂等表保证同一请求（双击、网络重试、刷新重发）只生效一次，并可回放首次结果。
 */
@Service
public class FruitingService {

    private static final String INVENTORY_IN = "入库";
    private static final String INVENTORY_REVERSAL = "冲正";
    private static final String INVENTORY_CORRECTION = "更正入库";
    private static final String FLUSH_HARVESTED = "已采收";

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

    /** 批次维度的库存变化账本（页面刷新后展示的对应库存记录） */
    public List<HarvestInventory> inventories(Long batchId) {
        batch(batchId);
        return repository.findInventories(batchId);
    }

    public List<Map<String, Object>> roomYieldStats() {
        return repository.yieldStatsByRoom();
    }

    @Transactional
    public Map<String, Object> harvest(Long batchId, Integer flushNo, HarvestRequest request) {
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
        if (request.harvestedBy() == null || request.harvestedBy().isBlank()) {
            throw new BizException("采收人不能为空");
        }
        String operator = request.harvestedBy().trim();
        if (operator.length() > 40) {
            throw new BizException("采收人姓名不能超过 40 字");
        }
        BigDecimal gradeA = normalized(request.gradeAYield());
        BigDecimal gradeB = normalized(request.gradeBYield());
        BigDecimal gradeC = normalized(request.gradeCYield());
        if (gradeA.signum() < 0 || gradeB.signum() < 0 || gradeC.signum() < 0) {
            throw new BizException("各等级采收量不得为负数");
        }
        BigDecimal newTotal = gradeA.add(gradeB).add(gradeC);
        if (newTotal.signum() <= 0) {
            throw new BizException("本潮采收总量必须大于 0 kg");
        }
        LocalDate harvestDate = request.harvestDate() == null ? LocalDate.now() : request.harvestDate();
        String payloadHash = payloadHash(harvestDate, gradeA, gradeB, gradeC, operator);

        // 先锁批次行：同批次的采收/更正、入房撤回全部串行，剩余预产量判断不会被并发打断
        FruitingBatch batch = repository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new BizException("出菇批次不存在"));

        // 幂等：同一 request_key 只生效一次。重复到达直接回放首次结果与流水，不再记账
        HarvestRequestLog existingLog = repository.findRequestLogForKey(requestKey).orElse(null);
        if (existingLog != null) {
            if (!existingLog.getFruitingBatchId().equals(batchId) || !existingLog.getFlushNo().equals(flushNo)) {
                throw new BizException("该请求标识已用于其他潮次的采收，请重新打开表单后提交");
            }
            if (!payloadHash.equals(existingLog.getPayloadHash())) {
                throw new BizException("该请求标识已提交过不同的采收数据，请重新打开表单后再提交更正");
            }
            repository.markRequestDuplicated(requestKey);
            return replayResult(existingLog, true);
        }

        if ("环境暂停".equals(batch.getStatus())) {
            throw new BizException("该批次因环境越限处置暂停生产，处置单解除后才能采收");
        }

        // 再锁潮次行：两个并发更正不会互相覆盖等级合计
        HarvestFlush flush = repository.findFlushForUpdate(batchId, flushNo).orElseGet(() -> {
            HarvestFlush item = new HarvestFlush();
            item.setBatchId(batchId);
            item.setFlushNo(flushNo);
            item.setGradeAYield(BigDecimal.ZERO);
            item.setGradeBYield(BigDecimal.ZERO);
            item.setGradeCYield(BigDecimal.ZERO);
            item.setYieldKg(BigDecimal.ZERO);
            item.setStatus("待采收");
            item.setRevisionNo(1);
            return item;
        });

        BigDecimal oldA = zero(flush.getGradeAYield());
        BigDecimal oldB = zero(flush.getGradeBYield());
        BigDecimal oldC = zero(flush.getGradeCYield());
        BigDecimal oldTotal = oldA.add(oldB).add(oldC);
        boolean firstHarvest = !FLUSH_HARVESTED.equals(flush.getStatus());
        boolean yieldChanged = firstHarvest || oldA.compareTo(gradeA) != 0 || oldB.compareTo(gradeB) != 0
                || oldC.compareTo(gradeC) != 0;

        // 批次累计 = 当前累计 - 本潮旧值 + 本潮新值（锁定状态下计算，增减产都正确）
        BigDecimal currentHarvested = zero(batch.getHarvestedYield());
        BigDecimal newHarvested = currentHarvested.subtract(oldTotal).add(newTotal);
        if (newHarvested.compareTo(batch.getExpectedYield()) > 0) {
            BigDecimal remaining = batch.getExpectedYield().subtract(currentHarvested).add(oldTotal);
            throw new BizException("本潮采收量超过批次剩余预产量，当前最多可录入 " + kg(remaining) + " kg");
        }
        if (newHarvested.signum() < 0) {
            throw new BizException("更正后批次累计采收不能为负数");
        }

        // 零变化重提：只更新日期/采收人，等级合计与批次累计不变，不追加任何库存流水
        if (!yieldChanged) {
            flush.setHarvestDate(harvestDate);
            flush.setHarvestedBy(operator);
            if (flush.getId() == null) {
                flush.setId(repository.saveFlush(flush));
            } else {
                repository.updateFlush(flush);
            }
            String message = "提交数据与第" + cnFlush(flushNo) + "当前已录产量一致，未重复记账";
            return finishRequest(requestKey, batchId, flushNo, flush.getRevisionNo(), false, payloadHash,
                    message, List.of(), flush, false);
        }

        int newRevision = firstHarvest ? 1 : zeroInt(flush.getRevisionNo()) + 1;
        flush.setHarvestDate(harvestDate);
        flush.setGradeAYield(gradeA);
        flush.setGradeBYield(gradeB);
        flush.setGradeCYield(gradeC);
        flush.setYieldKg(newTotal);
        flush.setHarvestedBy(operator);
        flush.setStatus(FLUSH_HARVESTED);
        flush.setRevisionNo(newRevision);
        if (flush.getId() == null) {
            flush.setId(repository.saveFlush(flush));
        } else {
            repository.updateFlush(flush);
        }

        // 原子条件更新兜底：即使绕过行锁的并发更正，也不可能把累计写到超过预产量
        if (repository.updateBatchYieldIfWithinExpected(batchId, newHarvested) == 0) {
            throw new BizException("剩余预产量已变化，请刷新后重试");
        }

        // 库存只追加：更正先冲正旧账（按旧等级构成取负），再按新值入正账；
        // 冲正后余额为 0、更正入库后余额为新产量，任何时刻余额都不会为负
        List<Long> changeIds = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        if (!firstHarvest && oldTotal.signum() > 0) {
            changeIds.add(repository.saveInventory(inventory(batchId, flushNo, oldTotal.negate(),
                    INVENTORY_REVERSAL, newRevision, oldA.negate(), oldB.negate(), oldC.negate(),
                    BigDecimal.ZERO, requestKey,
                    "冲正第" + (newRevision - 1) + "版 A:" + kg(oldA) + " / B:" + kg(oldB) + " / C:" + kg(oldC),
                    operator, now)));
        }
        changeIds.add(repository.saveInventory(inventory(batchId, flushNo, newTotal,
                firstHarvest ? INVENTORY_IN : INVENTORY_CORRECTION, newRevision, gradeA, gradeB, gradeC,
                newTotal, requestKey,
                "A:" + kg(gradeA) + "kg / B:" + kg(gradeB) + "kg / C:" + kg(gradeC) + "kg（第" + newRevision + "版）",
                operator, now)));

        String message;
        if (firstHarvest) {
            message = cnFlush(flushNo) + "采收入账成功：" + kg(newTotal) + " kg，批次累计 "
                    + kg(newHarvested) + " / " + kg(batch.getExpectedYield()) + " kg";
        } else {
            String direction = newTotal.compareTo(oldTotal) > 0 ? "增产" : "减产";
            message = cnFlush(flushNo) + "产量已由 " + kg(oldTotal) + " kg 更正为 " + kg(newTotal)
                    + " kg（" + direction + "），库存已追加冲正与更正流水，批次累计同步为 "
                    + kg(newHarvested) + " / " + kg(batch.getExpectedYield()) + " kg";
        }
        return finishRequest(requestKey, batchId, flushNo, newRevision, true, payloadHash,
                message, changeIds, flush, false);
    }

    // ---------------- 内部工具 ----------------

    private Map<String, Object> finishRequest(String requestKey, Long batchId, Integer flushNo, int revision,
            boolean yieldChanged, String payloadHash, String message, List<Long> changeIds,
            HarvestFlush flush, boolean duplicated) {
        HarvestRequestLog log = new HarvestRequestLog();
        log.setRequestKey(requestKey);
        log.setFruitingBatchId(batchId);
        log.setFlushNo(flushNo);
        log.setRevisionNo(revision);
        log.setYieldChanged(yieldChanged);
        log.setDuplicated(duplicated);
        log.setPayloadHash(payloadHash);
        log.setResultMessage(message);
        log.setChangeIds(changeIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        try {
            repository.saveRequestLog(log);
        } catch (DuplicateKeyException race) {
            // 并发重复请求：另一事务已落同一 request_key，回放其首次结果，本事务只读不写账
            HarvestRequestLog winner = repository.findRequestLogForKey(requestKey)
                    .orElseThrow(() -> new BizException("采收请求正在处理中，请稍后刷新查看"));
            if (!winner.getFruitingBatchId().equals(batchId) || !winner.getFlushNo().equals(flushNo)) {
                throw new BizException("该请求标识已用于其他潮次的采收，请重新打开表单后提交");
            }
            if (!winner.getPayloadHash().equals(payloadHash)) {
                throw new BizException("该请求标识已提交过不同的采收数据，请重新打开表单后再提交更正");
            }
            return replayResult(winner, true);
        }
        return buildResult(flush, revision, yieldChanged, duplicated, message,
                repository.findInventoriesByIds(changeIds));
    }

    private Map<String, Object> replayResult(HarvestRequestLog log, boolean duplicated) {
        List<HarvestInventory> changes = repository.findInventoriesByIds(parseIds(log.getChangeIds()));
        HarvestFlush flush = repository.findFlush(log.getFruitingBatchId(), log.getFlushNo()).orElse(null);
        String message = log.getResultMessage() + "（同一请求重复到达，仅生效一次）";
        return buildResult(flush, log.getRevisionNo(), log.getYieldChanged(), duplicated, message, changes);
    }

    private Map<String, Object> buildResult(HarvestFlush flush, int revision, boolean yieldChanged,
            boolean duplicated, String message, List<HarvestInventory> changes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flush", flush);
        result.put("revisionNo", revision);
        result.put("yieldChanged", yieldChanged);
        result.put("duplicated", duplicated);
        result.put("changes", changes);
        result.put("message", message);
        return result;
    }

    private HarvestInventory inventory(Long batchId, Integer flushNo, BigDecimal quantity, String changeType,
            int revision, BigDecimal gradeA, BigDecimal gradeB, BigDecimal gradeC, BigDecimal balance,
            String requestKey, String gradeSummary, String operator, LocalDateTime recordedAt) {
        HarvestInventory item = new HarvestInventory();
        item.setFruitingBatchId(batchId);
        item.setFlushNo(flushNo);
        item.setQuantity(quantity);
        item.setChangeType(changeType);
        item.setRevisionNo(revision);
        item.setGradeADelta(gradeA);
        item.setGradeBDelta(gradeB);
        item.setGradeCDelta(gradeC);
        item.setBalanceQty(balance);
        item.setRequestKey(requestKey);
        item.setGradeSummary(gradeSummary);
        item.setOperator(operator);
        item.setRecordedAt(recordedAt);
        return item;
    }

    private List<Long> parseIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : value.split(",")) {
            ids.add(Long.parseLong(part.trim()));
        }
        return ids;
    }

    private String payloadHash(LocalDate harvestDate, BigDecimal gradeA, BigDecimal gradeB, BigDecimal gradeC,
            String operator) {
        String raw = harvestDate + "|" + kg(gradeA) + "|" + kg(gradeB) + "|" + kg(gradeC) + "|" + operator;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成请求指纹", e);
        }
    }

    private FruitingBatch batch(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException("出菇批次不存在"));
    }

    /** 统一到 2 位小数，避免 10.10 与 10.1 被误判为变更，也与 DECIMAL(10,2) 口径一致 */
    private BigDecimal normalized(BigDecimal value) {
        return zero(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private int zeroInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String kg(BigDecimal value) {
        return zero(value).stripTrailingZeros().toPlainString();
    }

    private String cnFlush(Integer flushNo) {
        return List.of("一潮", "二潮", "三潮").get(flushNo - 1);
    }
}
