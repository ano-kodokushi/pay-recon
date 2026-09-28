package com.payrecon.controller;

import com.payrecon.dto.ReconcileRequest;
import com.payrecon.dto.ReconcileResult;
import com.payrecon.entity.ReconcileDetail;
import com.payrecon.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 对账接口：跑一次"差异发现 + 补偿"，以及查询差异明细。
 *
 * <p>只做差异发现与补偿，<b>不做真实资金退款</b>，也没有多渠道路由与分账。
 */
@Slf4j
@RestController
@RequestMapping("/api/reconcile")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileService reconcileService;

    /**
     * 跑一次对账：先发现差异，再对可安全补偿的差异做补偿。
     *
     * @param request 含 {@code billDate}（ISO yyyy-MM-dd）
     * @return 合并后的结果（差异计数 + 补偿计数）；日期非法返回 400
     */
    @PostMapping("/run")
    public ResponseEntity<ReconcileResult> run(@RequestBody ReconcileRequest request) {
        String billDate = request == null ? null : request.getBillDate();
        if (!isValidDate(billDate)) {
            return ResponseEntity.badRequest().build();
        }

        ReconcileResult discovery = reconcileService.reconcile(billDate);
        ReconcileResult compensation = reconcileService.compensate(billDate);

        // 合并：差异计数来自发现阶段，补偿计数来自补偿阶段
        discovery.setCompensatedCount(compensation.getCompensatedCount());
        discovery.setStillUnresolvedCount(compensation.getStillUnresolvedCount());
        return ResponseEntity.ok(discovery);
    }

    /** 查询某账单日的差异明细，供人工核对与测试取证。 */
    @GetMapping("/details")
    public ResponseEntity<List<ReconcileDetail>> details(@RequestParam String billDate) {
        if (!isValidDate(billDate)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(reconcileService.details(billDate));
    }

    /** 账单日必须是合法的 ISO 日期，避免把畸形字符串带进 SQL。 */
    private boolean isValidDate(String billDate) {
        if (billDate == null || billDate.isBlank()) {
            return false;
        }
        try {
            LocalDate.parse(billDate);
            return true;
        } catch (DateTimeParseException e) {
            log.warn("对账入参 billDate 非法: {}", billDate);
            return false;
        }
    }
}
