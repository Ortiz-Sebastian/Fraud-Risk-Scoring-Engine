package com.riskengine.producer;

import com.riskengine.common.model.TransactionEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates TransactionEvent instances for two operational modes:
 *
 * NORMAL — realistic e-commerce traffic across large user/device/IP pools.
 *
 * ATTACK — three distinct fraud patterns designed to trigger the risk engine rules:
 *   1. Velocity burst  — a small set of users fires many transactions rapidly (triggers velocity windows)
 *   2. IP burst        — diverse users all originate from 2-3 hot IPs (triggers IP burst detection)
 *   3. New device      — an unknown device makes a large first purchase (triggers device profiling)
 */
public class EventGenerator {

    // ── Normal pool sizes ────────────────────────────────────────────────────
    private static final int USER_POOL_SIZE   = 500;
    private static final int MERCHANT_POOL    = 50;
    private static final int DEVICE_POOL_SIZE = 400;
    private static final int IP_POOL_SIZE     = 300;

    // ── Attack constants ─────────────────────────────────────────────────────
    /** Small user set — same IDs fire repeatedly to exceed velocity thresholds. */
    private static final List<String> VELOCITY_USERS = List.of(
            "atk_vel_001", "atk_vel_002", "atk_vel_003"
    );

    /** Known-bad IPs — diverse users routed through these to trigger IP burst. */
    private static final List<String> HOT_IPS = List.of(
            "185.220.101.42",
            "198.54.117.196",
            "104.244.72.115"
    );

    /** Attack device prefix — outside the normal device pool, so always "new". */
    private static final String NEW_DEVICE_PREFIX = "NEW_DEV_";

    // ── Pre-built static pools (seeded for reproducibility) ──────────────────
    private static final String[] USER_POOL   = buildStringPool("user_",   USER_POOL_SIZE,   4);
    private static final String[] DEVICE_POOL = buildStringPool("device_", DEVICE_POOL_SIZE, 4);
    private static final String[] IP_POOL     = buildIpPool(IP_POOL_SIZE);

    private static final List<String> CURRENCIES = List.of(
            "USD", "USD", "USD", "USD", "USD", "EUR", "GBP"
    );

    private static final List<String> LOCATIONS = List.of(
            "New York", "Los Angeles", "Chicago", "Houston", "Phoenix",
            "Philadelphia", "San Antonio", "San Diego", "Dallas", "San Jose",
            "Austin", "Jacksonville", "Fort Worth", "Columbus", "Charlotte",
            "Miami", "Seattle", "Denver", "Boston", "Nashville"
    );

    // ────────────────────────────────────────────────────────────────────────
    private final ProducerMode mode;
    private final Random rng = new Random();

    public EventGenerator(ProducerMode mode) {
        this.mode = mode;
    }

    public TransactionEvent next() {
        return mode == ProducerMode.ATTACK ? generateAttackEvent() : generateNormalEvent();
    }

    // ── Normal event ─────────────────────────────────────────────────────────

    private TransactionEvent generateNormalEvent() {
        return new TransactionEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                pick(USER_POOL),
                merchantId(),
                normalAmount(),
                randomCurrency(),
                pick(IP_POOL),
                pick(DEVICE_POOL),
                randomLocation()
        );
    }

    // ── Attack dispatch ───────────────────────────────────────────────────────

    private TransactionEvent generateAttackEvent() {
        double p = rng.nextDouble();
        if (p < 0.50) return generateVelocityBurst();
        if (p < 0.80) return generateIpBurst();
        return generateNewDeviceAttack();
    }

    /**
     * Velocity burst: a handful of user IDs fire rapidly with above-average amounts.
     * Intended to exceed the 5-minute sliding window transaction count threshold.
     */
    private TransactionEvent generateVelocityBurst() {
        return new TransactionEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                pick(VELOCITY_USERS),
                merchantId(),
                amountBetween(150.0, 1500.0),
                "USD",
                pick(IP_POOL),
                pick(DEVICE_POOL),
                randomLocation()
        );
    }

    /**
     * IP burst: normal-looking users but all originate from the same 2-3 hot IPs.
     * Intended to exceed the 2-minute sliding window distinct-user-per-IP threshold.
     */
    private TransactionEvent generateIpBurst() {
        return new TransactionEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                pick(USER_POOL),
                merchantId(),
                amountBetween(50.0, 600.0),
                "USD",
                pick(HOT_IPS),
                pick(DEVICE_POOL),
                randomLocation()
        );
    }

    /**
     * New device: a fresh, never-seen device_id makes a large single purchase.
     * Intended to trigger the device profiling / first-purchase anomaly rule.
     */
    private TransactionEvent generateNewDeviceAttack() {
        return new TransactionEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                pick(USER_POOL),
                merchantId(),
                amountBetween(500.0, 3000.0),
                "USD",
                pick(IP_POOL),
                NEW_DEVICE_PREFIX + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                randomLocation()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal normalAmount() {
        // Weighted distribution: most transactions are small, a few are large
        double p = rng.nextDouble();
        if (p < 0.70) return amountBetween(5.0,   150.0);
        if (p < 0.95) return amountBetween(150.0,  500.0);
        return             amountBetween(500.0, 3000.0);
    }

    private BigDecimal amountBetween(double min, double max) {
        double value = min + (max - min) * rng.nextDouble();
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String merchantId() {
        return String.format("merchant_%03d", rng.nextInt(MERCHANT_POOL) + 1);
    }

    private String randomCurrency() {
        return CURRENCIES.get(rng.nextInt(CURRENCIES.size()));
    }

    private String randomLocation() {
        return LOCATIONS.get(rng.nextInt(LOCATIONS.size()));
    }

    private <T> T pick(List<T> list) {
        return list.get(rng.nextInt(list.size()));
    }

    private String pick(String[] arr) {
        return arr[rng.nextInt(arr.length)];
    }

    // ── Static pool builders ──────────────────────────────────────────────────

    private static String[] buildStringPool(String prefix, int size, int zeroPad) {
        String fmt = prefix + "%0" + zeroPad + "d";
        String[] pool = new String[size];
        for (int i = 0; i < size; i++) {
            pool[i] = String.format(fmt, i + 1);
        }
        return pool;
    }

    /**
     * Builds a stable pool of realistic-looking public IP addresses.
     * Uses a seeded random so the pool is identical across JVM restarts,
     * which lets Flink's state accumulate recognisable patterns across test runs.
     */
    private static String[] buildIpPool(int size) {
        Random seeded = new Random(0xDEADBEEFL);
        String[] pool = new String[size];
        for (int i = 0; i < size; i++) {
            int a = 70  + seeded.nextInt(130); // 70–199 (avoids private ranges)
            int b = seeded.nextInt(256);
            int c = seeded.nextInt(256);
            int d = 1   + seeded.nextInt(254);
            pool[i] = a + "." + b + "." + c + "." + d;
        }
        return pool;
    }
}
