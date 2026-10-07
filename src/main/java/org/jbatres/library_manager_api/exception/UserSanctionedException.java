package org.jbatres.library_manager_api.exception;

/**
 * The reader is sanctioned (already, or just now because of overdue loans).
 * LoanService declares noRollbackFor this exception: the sanction must be COMMITTED
 * even though the loan request itself is rejected.
 */
public class UserSanctionedException extends BusinessRuleException {

    public UserSanctionedException(String message) {
        super(message);
    }
}