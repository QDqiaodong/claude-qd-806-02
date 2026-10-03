package com.fungus.farm.dto;

import com.fungus.farm.domain.HarvestFlush;
import com.fungus.farm.domain.HarvestInventory;

/**
 * 采收/更正入账结果：
 * result = RECORDED（已入账并产生库存流水）、UNCHANGED（与已入账记录一致，未产生新流水）、
 *          DUPLICATED（同一请求标识已入账，重复提交被忽略）。
 * inventory 为本次产生的流水；UNCHANGED 时为 null，DUPLICATED 时为首次入账的那条流水。
 */
public record HarvestResult(
        HarvestFlush flush,
        HarvestInventory inventory,
        String result) {
}
