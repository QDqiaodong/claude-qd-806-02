package com.fungus.farm.domain;

import lombok.Data;

/**
 * 采收/更正请求幂等记录：同一 request_key 只允许生效一次，
 * 重复到达时回放首次结果（写入的库存流水 id 与提示语），不再次记账。
 */
@Data
public class HarvestRequestLog {
    private String requestKey;
    private Long fruitingBatchId;
    private Integer flushNo;
    private Integer revisionNo;
    private Boolean yieldChanged;
    private Boolean duplicated;
    private String payloadHash;
    private String resultMessage;
    private String changeIds;
}
