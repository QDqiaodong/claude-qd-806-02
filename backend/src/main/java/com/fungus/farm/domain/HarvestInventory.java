package com.fungus.farm.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HarvestInventory {
    private Long id;
    private Long fruitingBatchId;
    private Integer flushNo;
    /** 本流水数量变化：入库/更正入库为正，冲正为负（冲正只抵消历史入库，不产生负库存） */
    private BigDecimal quantity;
    /** 入库 / 冲正 / 更正入库 */
    private String changeType;
    private Integer revisionNo;
    private BigDecimal gradeADelta;
    private BigDecimal gradeBDelta;
    private BigDecimal gradeCDelta;
    /** 本条流水落账后该潮次的库存余额，恒 >= 0 */
    private BigDecimal balanceQty;
    private String requestKey;
    private String gradeSummary;
    private String operator;
    private LocalDateTime recordedAt;
}
