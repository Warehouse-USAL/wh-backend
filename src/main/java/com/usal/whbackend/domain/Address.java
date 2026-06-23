package com.usal.whbackend.domain;

public record Address(
    String street,
    String department,
    String floor,
    String postalCode) {}
