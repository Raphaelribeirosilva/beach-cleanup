package com.raphael.beachcleanup.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageDTO<T>(List<T> content, int number, int size, long totalElements, int totalPages) {
    public static <T> PageDTO<T> of(Page<?> page, List<T> content) {
        return new PageDTO<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
