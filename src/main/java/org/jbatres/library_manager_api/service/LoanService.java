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
import org.jbatres.library_manager_api.exception.LoanAlreadyReturnedException;
import org.jbatres.library_manager_api.exception.ResourceNotFoundException;
import org.jbatres.library_manager_api.exception.UserSanctionedException;
import org.jbatres.library_manager_api.mapper.LoanMapper;
import org.jbatres.library_manager_api.repository.BookRepository;
import org.jbatres.library_manager_api.repository.LoanRepository;
import org.jbatres.library_manager_api.repository.UserRepository;
import org.jbatres.library_manager_api.dto.response.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;

@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    static final int MAX_ACTIVE_LOANS = 3;
    static final int LOAN_DAYS = 14;

    /** Properties a client may sort loans by (direct columns only). */
    private static final Set<String> LOAN_SORT_FIELDS =
            Set.of("id", "loanDate", "expectedReturnDate", "actualReturnDate", "status");
    private static final Sort MY_LOANS_DEFAULT_SORT =
            Sort.by(Sort.Order.desc("loanDate"), Sort.Order.desc("id"));
    private static final Sort OVERDUE_DEFAULT_SORT =
            Sort.by(Sort.Order.asc("expectedReturnDate"), Sort.Order.asc("id"));

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
     * GLOBAL LOCK ORDER (also followed by {@link #returnLoan}): reader row -> loan rows -> book row.
     * Any failure after the decrement rolls back the whole transaction (stock included). The only
     * exception is {@link UserSanctionedException}: it is thrown before any stock change and the
     * sanction must persist.
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

    /**
     * Registers the return of a book, in one transaction:
     * <ol>
     *   <li>read the loan (plain read, no lock) to learn the reader and the book;</li>
     *   <li>lock the reader row first, keeping the global lock order of {@link #createLoan}
     *       (reader -> book), which rules out deadlocks between loans and returns;</li>
     *   <li>atomic "close the loan only if it is still open" update. If it affects 0 rows another
     *       request returned it first: the stock is NOT restored a second time;</li>
     *   <li>atomic stock increment (capped at totalStock);</li>
     *   <li>lift the reader's sanction if no overdue loan remains open.</li>
     * </ol>
     */
    @Transactional
    public LoanResponse returnLoan(Long loanId) {
        LocalDate today = LocalDate.now(clock);

        Loan loan = loanRepository.findByIdWithDetails(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found with id " + loanId));
        Long userId = loan.getUser().getId();
        Long bookId = loan.getBook().getId();

        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + userId));

        if (loanRepository.markReturned(loanId, today) == 0) {
            throw new LoanAlreadyReturnedException("Loan " + loanId + " has already been returned");
        }

        if (bookRepository.incrementAvailableStock(bookId) == 0) {
            // available_stock was already equal to total_stock: data is inconsistent. Roll everything back.
            log.error("Stock inconsistency while returning loanId={} bookId={}", loanId, bookId);
            throw new IllegalStateException("Stock inconsistency detected for book " + bookId);
        }

        liftSanctionIfEligible(userId, today);

        // markReturned cleared the persistence context, so this reads the fresh state.
        Loan returned = loanRepository.findByIdWithDetails(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found with id " + loanId));
        log.info("Loan returned id={} userId={} bookId={}", loanId, userId, bookId);
        return loanMapper.toResponse(returned, today);
    }

    /**
     * Design decision (not stated in the evaluation): a sanctioned reader is reactivated as soon as
     * no overdue open loan is left. Without this rule a sanction would be permanent.
     * To disable it, remove the call in {@link #returnLoan}.
     * The reader row is already locked by the caller.
     */
    private void liftSanctionIfEligible(Long userId, LocalDate today) {
        User reader = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + userId));
        if (reader.getStatus() == UserStatus.SANCIONADO
                && !loanRepository.existsByUser_IdAndStatusInAndExpectedReturnDateBefore(
                userId, LoanStatus.OPEN_STATUSES, today)) {
            reader.setStatus(UserStatus.ACTIVO);
            log.info("Reader sanction lifted userId={}", userId);
        }
    }

    /**
     * Loan history of one reader, newest first by default. The reader id always comes from the
     * authenticated principal (never from the request), so a reader can only see their own loans.
     * Read-only transaction; user and book are fetched in the same query (no N+1).
     */
    @Transactional(readOnly = true)
    public PageResponse<LoanResponse> getMyLoans(Long userId, Pageable pageable) {
        Pageable safe = PageableSupport.sanitize(pageable, LOAN_SORT_FIELDS, MY_LOANS_DEFAULT_SORT);
        LocalDate today = LocalDate.now(clock);
        Page<LoanResponse> page = loanRepository.findByUserIdWithBook(userId, safe)
                .map(loan -> loanMapper.toResponse(loan, today));
        return PageResponse.from(page);
    }

    /**
     * Open loans (ACTIVO or ATRASADO) whose expected return date is before today, most overdue
     * first. A past-due loan can still be stored as ACTIVO until its reader tries a new loan
     * (that is when the status is flipped), so the {@code overdue} flag of the response is the
     * reliable indicator.
     */
    @Transactional(readOnly = true)
    public PageResponse<LoanResponse> getOverdueLoans(Pageable pageable) {
        Pageable safe = PageableSupport.sanitize(pageable, LOAN_SORT_FIELDS, OVERDUE_DEFAULT_SORT);
        LocalDate today = LocalDate.now(clock);
        Page<LoanResponse> page = loanRepository.findOverdue(LoanStatus.OPEN_STATUSES, today, safe)
                .map(loan -> loanMapper.toResponse(loan, today));
        return PageResponse.from(page);
    }
}