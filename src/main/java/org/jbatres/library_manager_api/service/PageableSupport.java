package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.exception.BusinessRuleException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Makes client-supplied paging safe: caps the page size, applies a default sort, accepts ONLY
 * whitelisted sort properties (so "?sort=user.password" or a typo cannot reach the query) and
 * always adds "id" as a final tie-breaker so pages are stable.
 */
final class PageableSupport {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private PageableSupport() {
    }

    static Pageable sanitize(Pageable requested, Set<String> allowedSortFields, Sort defaultSort) {
        int page = requested.isPaged() ? requested.getPageNumber() : 0;
        int size = requested.isPaged() ? Math.min(requested.getPageSize(), MAX_SIZE) : DEFAULT_SIZE;

        Sort sort = defaultSort;
        if (requested.getSort().isSorted()) {
            List<Sort.Order> orders = new ArrayList<>();
            for (Sort.Order order : requested.getSort()) {
                if (!allowedSortFields.contains(order.getProperty())) {
                    throw new BusinessRuleException("Unsupported sort property '" + order.getProperty()
                            + "'. Allowed: " + new TreeSet<>(allowedSortFields));
                }
                // Rebuilt on purpose: drops ignoreCase / null-handling flags that make no sense here.
                orders.add(new Sort.Order(order.getDirection(), order.getProperty()));
            }
            sort = Sort.by(orders);
            if (sort.getOrderFor("id") == null) {
                sort = sort.and(Sort.by(Sort.Order.asc("id")));
            }
        }
        return PageRequest.of(page, size, sort);
    }
}