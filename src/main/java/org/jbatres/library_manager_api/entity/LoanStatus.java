package org.jbatres.library_manager_api.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Loan lifecycle: ACTIVO -> DEVUELTO, or ACTIVO -> ATRASADO -> DEVUELTO.
 * ACTIVO and ATRASADO both count as "not returned yet" (open loans).
 */
public enum LoanStatus {
    ACTIVO,
    DEVUELTO,
    ATRASADO;

    /** Statuses of loans that have not been returned yet. */
    public static final Set<LoanStatus> OPEN_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(ACTIVO, ATRASADO));

    public boolean isOpen() {
        return this != DEVUELTO;
    }
}