package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.request.CreateLoanRequest;
import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.jbatres.library_manager_api.exception.BusinessRuleException;
import org.jbatres.library_manager_api.exception.ResourceNotFoundException;
import org.jbatres.library_manager_api.exception.UserSanctionedException;
import org.jbatres.library_manager_api.mapper.LoanMapper;
import org.jbatres.library_manager_api.repository.BookRepository;
import org.jbatres.library_manager_api.repository.LoanRepository;
import org.jbatres.library_manager_api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    static final int MAX_ACTIVE_LOANS = 3;
    static final int LOAN_DAYS = 14;

    private final UserRepository userRepository;
    private final BookRepository bookRepository;
    private final LoanRepository loanRepository;
    private final LoanMapper loanMapper;
    private final Clock clock;

    public LoanService(UserRepository userRepository,
                       BookRepository bookRepository,
                       LoanRepository loanRepository,
                       LoanMapper loanMapper,
                       Clock clock) {
        this.userRepository = userRepository;
        this.bookRepository = bookRepository;
        this.loanRepository = loanRepository;
        this.loanMapper = loanMapper;
        this.clock = clock;
    }

    /**
     * Registers a loan. One short transaction, always in this order:
     * <ol>
     *   <li>lock the reader row (FOR UPDATE): requests of the same reader are serialized, so the
     *       3-loan limit cannot be bypassed by concurrent requests;</li>
     *   <li>role / sanction / overdue checks;</li>
     *   <li>loan limit check;</li>
     *   <li>atomic conditional stock decrement (UPDATE ... WHERE available_stock > 0);</li>
     *   <li>insert the loan.</li>
     * </ol>
     * Lock order is always reader -> book, so there are no deadlocks. Any failure after the
     * decrement rolls back the whole transaction (stock included). The only exception is
     * {@link UserSanctionedException}: it is thrown before any stock change and the sanction must persist.
     */
    @Transactional(noRollbackFor = UserSanctionedException.class)
    public LoanResponse createLoan(CreateLoanRequest request) {
        LocalDate today = LocalDate.now(clock);
        Long userId = request.userId();
        Long bookId = request.bookId();

        User reader = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + userId));

        if (reader.getRole() != Role.LECTOR) {
            throw new BusinessRuleException("Loans can only be registered for users with role LECTOR");
        }
        if (reader.getStatus() == UserStatus.SANCIONADO) {
            throw new UserSanctionedException("User is sanctioned and cannot borrow books");
        }

        // Automatic sanction: an open loan past its due date is detected when a new loan is attempted.
        if (loanRepository.existsByUser_IdAndStatusInAndExpectedReturnDateBefore(
                userId, LoanStatus.OPEN_STATUSES, today)) {
            loanRepository.markOverdueByUserId(userId, today);
            reader.setStatus(UserStatus.SANCIONADO);
            log.warn("Reader sanctioned for overdue loans userId={}", userId);
            throw new UserSanctionedException(
                    "User has overdue loans and has been sanctioned; return them before borrowing again");
        }

        long openLoans = loanRepository.countByUser_IdAndStatusIn(userId, LoanStatus.OPEN_STATUSES);
        if (openLoans >= MAX_ACTIVE_LOANS) {
            throw new BusinessRuleException(
                    "User already has the maximum of " + MAX_ACTIVE_LOANS + " active loans");
        }

        if (bookRepository.decrementAvailableStock(bookId) == 0) {
            // 0 rows: either the book does not exist / is deleted, or it has no copies left.
            if (bookRepository.findByIdAndDeletedFalse(bookId).isEmpty()) {
                throw new ResourceNotFoundException("Book not found with id " + bookId);
            }
            throw new BusinessRuleException("Book has no available copies");
        }

        // Loaded AFTER the update, so availableStock reflects the decrement.
        Book book = bookRepository.findByIdAndDeletedFalse(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Book not found with id " + bookId));

        Loan loan = Loan.builder()
                .user(reader)
                .book(book)
                .loanDate(today)
                .expectedReturnDate(today.plusDays(LOAN_DAYS))
                .status(LoanStatus.ACTIVO)
                .build();
        loanRepository.save(loan);

        log.info("Loan created id={} userId={} bookId={}", loan.getId(), userId, bookId);
        return loanMapper.toResponse(loan, today);
    }
}