package com.usal.whbackend.service.metrics.conversion;

/**
 * One calendar bucket of the suggestion-conversion metric. A rate is 0 when its denominator is 0,
 * so the series has no holes.
 *
 * @param bucket first day of the bucket, {@code YYYY-MM-DD}, in the requested time zone
 * @param orders non-cancelled orders created in the bucket
 * @param ordersWithRecommendation of those, the ones with at least one line taken from a suggestion
 * @param lines order lines of those orders
 * @param linesFromRecommendation of those, the lines taken from a suggestion
 * @param units units ordered
 * @param unitsFromRecommendation of those, the units on lines taken from a suggestion
 */
public record ConversionBucket(
    String bucket,
    int orders,
    int ordersWithRecommendation,
    double orderConversionRate,
    int lines,
    int linesFromRecommendation,
    double lineConversionRate,
    long units,
    long unitsFromRecommendation,
    double unitConversionRate) {}
