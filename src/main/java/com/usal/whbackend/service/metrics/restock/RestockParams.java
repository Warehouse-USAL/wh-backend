package com.usal.whbackend.service.metrics.restock;

/**
 * The business decisions behind a restock suggestion. None of these are fixed by the method — each
 * team sends its own on every request (RFC_Metricas_Calculadas.md §6.1).
 *
 * @param alpha weight of recent demand against long-term demand, 0–1
 * @param recentDays length of the recent window
 * @param longDays length of the long window; it contains the recent one, which is excluded from
 *     the long-term average so no day counts twice
 * @param safetyDays size of the safety cushion, in days of long-term demand
 * @param leadTimeDays days a restock takes to arrive
 * @param coverageDays days of stock to hold after restocking
 */
public record RestockParams(
    double alpha,
    int recentDays,
    int longDays,
    double safetyDays,
    double leadTimeDays,
    double coverageDays) {}
