package com.usal.whbackend.api;

import org.springframework.data.domain.Page;

public record Pagination(int page, int size, long total_elements, int total_pages) {

  public static Pagination from(Page<?> p) {
    return new Pagination(p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
  }
}
