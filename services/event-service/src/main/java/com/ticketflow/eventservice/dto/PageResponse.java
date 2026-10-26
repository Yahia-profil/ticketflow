package com.ticketflow.eventservice.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Minimal pagination envelope: keeps the API stable without leaking Spring's Page JSON. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
