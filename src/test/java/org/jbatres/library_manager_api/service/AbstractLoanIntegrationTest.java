package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.request.CreateLoanRequest;
import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.jbatres.library_manager_api.repository.BookRepository;
import org.jbatres.library_manager_api.repository.LoanRepository;
import org.jbatres.library_manager_api.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base for loan tests that run REAL transactions against the real database (not rolled back), so
 * concurrency tests are meaningful. Tests only touch their own rows (emails it-*@test.local,
 * ISBNs 97899*), which are deleted before and after every test; seed data is never modified.
 * Needs the same environment variables as the app (DB_PASSWORD, JWT_SECRET).
 */
@SpringBootTest
public abstract class AbstractLoanIntegrationTest {

    private static final AtomicInteger ISBN_SEQ = new AtomicInteger(1);

    @Autowired
    protected LoanService loanService;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected BookRepository bookRepository;
    @Autowired
    protected LoanRepository loanRepository;
    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    protected void cleanUp() {
        jdbc.update("DELETE FROM loans WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'it-%@test.local')");
        jdbc.update("DELETE FROM loans WHERE book_id IN (SELECT id FROM books WHERE isbn LIKE '97899%')");
        jdbc.update("DELETE FROM users WHERE email LIKE 'it-%@test.local'");
        jdbc.update("DELETE FROM books WHERE isbn LIKE '97899%'");
    }

    protected User newUser(Role role, UserStatus status) {
        return userRepository.save(User.builder()
                .name("IT User")
                .email("it-" + UUID.randomUUID() + "@test.local")
                .password("not-a-real-hash")
                .role(role)
                .status(status)
                .build());
    }

    protected User newReader() {
        return newUser(Role.LECTOR, UserStatus.ACTIVO);
    }

    protected Book newBook(int total, int available) {
        String isbn = String.format("97899%08d", ISBN_SEQ.getAndIncrement());
        return bookRepository.save(Book.builder()
                .isbn(isbn).title("IT Book " + isbn).author("IT Author").category("Testing")
                .totalStock(total).availableStock(available)
                .build());
    }

    /** Inserts an open ACTIVO loan whose due date was {@code daysOverdue} days ago. */
    protected Loan insertPastDueLoan(User reader, Book book, int daysOverdue) {
        LocalDate today = LocalDate.now();
        return loanRepository.save(Loan.builder()
                .user(reader).book(book)
                .loanDate(today.minusDays(14L + daysOverdue))
                .expectedReturnDate(today.minusDays(daysOverdue))
                .status(LoanStatus.ACTIVO)
                .build());
    }

    protected int availableStock(Book book) {
        return bookRepository.findById(book.getId()).orElseThrow().getAvailableStock();
    }

    protected UserStatus statusOf(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getStatus();
    }

    protected String loanStatusInDb(Long loanId) {
        return jdbc.queryForObject("SELECT status FROM loans WHERE id = ?", String.class, loanId);
    }

    protected int loanCount(User user) {
        return jdbc.queryForObject("SELECT count(*) FROM loans WHERE user_id = ?", Integer.class, user.getId());
    }

    protected LoanResponse borrow(User user, Book book) {
        return loanService.createLoan(new CreateLoanRequest(user.getId(), book.getId()));
    }

    /** Fires all tasks at the same instant; returns each result (LoanResponse or the thrown exception). */
    protected List<Object> runConcurrently(List<Callable<LoanResponse>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Callable<LoanResponse> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}