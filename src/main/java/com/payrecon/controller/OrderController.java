package com.payrecon.controller;

import com.payrecon.dto.OrderCreateRequest;
import com.payrecon.dto.OrderCreateResponse;
import com.payrecon.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 下单接口。
 *
 * <p>并发/重复提交同一 merchantOrderNo 一律返回 HTTP 200 与同一个 orderId
 * （响应体中的 {@code reused} 区分「本次插入」与「复用已存在」），
 * 只有参数非法才返回 HTTP 400。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 创建（或复用）支付订单。
     *
     * @param request 下单请求
     * @return 200 + 订单信息；参数非法时 400
     */
    @PostMapping(value = "/create",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody OrderCreateRequest request) {
        if (request == null) {
            return badRequest("请求体不能为空");
        }
        String merchantOrderNo = request.getMerchantOrderNo();
        if (merchantOrderNo == null || merchantOrderNo.trim().isEmpty()) {
            return badRequest("merchantOrderNo 不能为空");
        }
        BigDecimal amount = request.getAmount();
        if (amount == null) {
            return badRequest("amount 不能为空");
        }
        // 金额比较必须用 compareTo：equals 会把 10.0 与 10.00 判为不等（scale 敏感）。
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return badRequest("amount 必须大于 0");
        }

        request.setMerchantOrderNo(merchantOrderNo.trim());
        OrderCreateResponse response = orderService.createOrder(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 组装 400 响应。
     *
     * @param message 错误说明
     * @return HTTP 400
     */
    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        log.info("下单参数校验失败：{}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", 400, "message", message));
    }
}
