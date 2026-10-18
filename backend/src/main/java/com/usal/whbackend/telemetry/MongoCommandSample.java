package com.usal.whbackend.telemetry;

/**
 * One finished MongoDB command on a collection. {@code outcome} is {@code ok}, {@code slow} or
 * {@code error}; the collection comes from a fixed set of commands, so the tag stays bounded.
 */
public record MongoCommandSample(
    String collection, String command, String outcome, double durationMs) {}
