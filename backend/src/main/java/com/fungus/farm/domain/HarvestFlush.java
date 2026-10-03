package com.fungus.farm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

@Data
public class HarvestFlush {
    private Long id;
    private Long batchId;
    private Integer flushNo;
    private LocalDate harvestDate;
    private BigDecimal gradeAYield;
    private BigDecimal gradeBYield;
    private BigDecimal gradeCYield;
    private BigDecimal yieldKg;
    private String harvestedBy;
    private String status;
    /** 第几次录入/更正：首录为 1，之后每次真实更正 +1，零变化重提不累加 */
    private Integer revisionNo;
}
