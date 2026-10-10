package com.raphael.beachcleanup.services;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import com.raphael.beachcleanup.exceptions.FieldValidationException;

public final class SearchPagination {
    private SearchPagination() {}

    public static Pageable of(int page, int size, String sortBy, String direction, Set<String> fields) {
        if (page < 0) {
            throw new FieldValidationException("page", "A página deve ser zero ou positiva.");
        }
        if (size < 1 || size > 100) {
            throw new FieldValidationException("size", "O tamanho deve estar entre 1 e 100.");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new FieldValidationException("page", "A página excede o limite de consulta.");
        }
        if (!fields.contains(sortBy)) {
            throw new FieldValidationException("sortBy", "Campo de ordenação não permitido.");
        }
        Sort.Direction order;
        try {
            order = Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException("direction", "Use asc ou desc.");
        }
        Sort sort = Sort.by(order, sortBy);
        if (!sortBy.equals("id")) {
            sort = sort.and(Sort.by("id"));
        }
        return PageRequest.of(page, size, sort);
    }
}
