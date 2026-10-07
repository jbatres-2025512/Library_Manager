package org.jbatres.library_manager_api.exception;

/**
 * The loan was already returned. Extends BusinessRuleException so it is a 400 even without a
 * dedicated mapping; Phase 12 maps it to 409 CONFLICT.
 */
public class LoanAlreadyReturnedException extends BusinessRuleException {

    public LoanAlreadyReturnedException(String message) {
        super(message);
    }
}