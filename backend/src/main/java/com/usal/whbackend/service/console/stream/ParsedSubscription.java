package com.usal.whbackend.service.console.stream;

/** A validated subscription: the client's id and the source it reads. */
record ParsedSubscription(String id, SourceKey key) {}
