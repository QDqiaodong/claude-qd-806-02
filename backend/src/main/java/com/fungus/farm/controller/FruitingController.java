package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.domain.HarvestFlush;
import com.fungus.farm.dto.HarvestRequest;
import com.fungus.farm.dto.HarvestResult;
import com.fungus.farm.service.FruitingService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fruiting-batches")
public class FruitingController {
    private final FruitingService service;

    public FruitingController(FruitingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> batches() {
        return ApiResponse.success(service.batches());
    }

    @GetMapping("/{batchId}/flushes")
    public ApiResponse<List<HarvestFlush>> flushes(@PathVariable Long batchId) {
        return ApiResponse.success(service.flushes(batchId));
    }

    @PostMapping("/{batchId}/flushes/{flushNo}/harvest")
    public ApiResponse<HarvestResult> harvest(@PathVariable Long batchId, @PathVariable Integer flushNo,
            @RequestBody HarvestRequest request) {
        HarvestResult result = service.harvest(batchId, flushNo, request);
        String message = switch (result.result()) {
            case "UNCHANGED" -> "与已入账记录一致，未产生新的库存流水";
            case "DUPLICATED" -> "该请求已入账，重复提交已忽略";
            default -> "采收已入账";
        };
        return ApiResponse.success(message, result);
    }

    @GetMapping("/{batchId}/inventories")
    public ApiResponse<Map<String, Object>> inventories(@PathVariable Long batchId) {
        return ApiResponse.success(service.inventories(batchId));
    }

    @GetMapping("/room-yields")
    public ApiResponse<List<Map<String, Object>>> roomYields() {
        return ApiResponse.success(service.roomYieldStats());
    }
}
