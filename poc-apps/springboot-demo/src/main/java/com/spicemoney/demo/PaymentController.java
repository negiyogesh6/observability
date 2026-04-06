package com.observabilityy.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private final Random random = new Random();

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "springboot-demo"));
    }

    @PostMapping("/payment")
    public ResponseEntity<Map<String, Object>> processPayment(@RequestBody(required = false) Map<String, Object> body) {
        int delay = random.nextInt(300) + 50;
        sleep(delay);

        if (random.nextDouble() < 0.1) {
            log.error("Payment processing failed after {}ms", delay);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "FAILED", "error", "Payment gateway timeout"));
        }

        log.info("Payment processed successfully in {}ms", delay);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "latency_ms", delay,
                "transactionId", "TXN-" + System.currentTimeMillis()
        ));
    }

    @GetMapping("/payment/validate")
    public ResponseEntity<Map<String, Object>> validatePayment(
            @RequestParam(defaultValue = "TXN-123") String txnId) {
        int delay = random.nextInt(100) + 20;
        sleep(delay);
        log.info("Payment validated: {} in {}ms", txnId, delay);
        return ResponseEntity.ok(Map.of("valid", true, "txnId", txnId));
    }

    @GetMapping("/payment/history")
    public ResponseEntity<Map<String, Object>> paymentHistory() {
        int delay = random.nextInt(200) + 100;
        sleep(delay);
        log.info("Payment history fetched in {}ms", delay);
        return ResponseEntity.ok(Map.of("count", random.nextInt(50), "latency_ms", delay));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable String userId) {
        int delay = random.nextInt(150) + 30;
        sleep(delay);
        log.info("User {} fetched in {}ms", userId, delay);
        return ResponseEntity.ok(Map.of("userId", userId, "name", "User-" + userId));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        int delay = random.nextInt(250) + 80;
        sleep(delay);

        if (random.nextDouble() < 0.05) {
            log.error("Order {} not found", orderId);
            return ResponseEntity.notFound().build();
        }

        log.info("Order {} fetched in {}ms", orderId, delay);
        return ResponseEntity.ok(Map.of("orderId", orderId, "status", "COMPLETED", "latency_ms", delay));
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
