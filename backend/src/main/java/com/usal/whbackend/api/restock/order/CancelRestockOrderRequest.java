package com.usal.whbackend.api.restock.order;

/** Body opcional de {@code POST /restock/orders/{id}/cancel}. */
public record CancelRestockOrderRequest(String reason) {}
