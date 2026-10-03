package com.fungus.farm.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HarvestInventory {
    private Long id;
    private Long fruitingBatchId;
    private Integer flushNo;
    /** 入库 / 冲正 / 调整（总量不变仅等级构成变化） */
    private String entryType;
    private BigDecimal quantity;
    private String gradeSummary;
    private String operator;
    private String requestKey;
    private LocalDateTime recordedAt;
}
