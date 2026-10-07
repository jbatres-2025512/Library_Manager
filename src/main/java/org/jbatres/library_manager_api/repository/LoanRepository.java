package org.jbatres.library_manager_api.repository;

import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    /** Number of loans of a reader in the given statuses (use {@link LoanStatus#OPEN_STATUSES}). */
    long countByUser_IdAndStatusIn(Long userId, Collection<LoanStatus> statuses);

    /** True if the reader has an open loan whose expected return date is before {@code date}. */
    boolean existsByUser_IdAndStatusInAndExpectedReturnDateBefore(
            Long userId, Collection<LoanStatus> statuses, LocalDate date);

    /** True if the book has loans in the given statuses (blocks soft-deleting a book). */
    boolean existsByBook_IdAndStatusIn(Long bookId, Collection<LoanStatus> statuses);

    /**
     * Flags the reader's past-due ACTIVO loans as ATRASADO in one statement.
     * Persistence context is not cleared (the locked reader entity must stay managed).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Loan l set l.status = org.jbatres.library_manager_api.entity.LoanStatus.ATRASADO
            where l.user.id = :userId
              and l.status = org.jbatres.library_manager_api.entity.LoanStatus.ACTIVO
              and l.expectedReturnDate < :today
            """)
    int markOverdueByUserId(@Param("userId") Long userId, @Param("today") LocalDate today);

    /**
     * Atomic return: only succeeds (returns 1) if the loan is still open, so a loan can never be
     * returned twice and stock can never be restored twice. Returns 0 if absent or already returned.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Loan l
            set l.status = org.jbatres.library_manager_api.entity.LoanStatus.DEVUELTO,
                l.actualReturnDate = :today
            where l.id = :id
              and l.status in (org.jbatres.library_manager_api.entity.LoanStatus.ACTIVO,
                               org.jbatres.library_manager_api.entity.LoanStatus.ATRASADO)
            """)
    int markReturned(@Param("id") Long id, @Param("today") LocalDate today);

    /** Loan with its user and book loaded in a single query (no N+1). */
    @Query("""
            select l from Loan l
            join fetch l.user
            join fetch l.book
            where l.id = :id
            """)
    Optional<Loan> findByIdWithDetails(@Param("id") Long id);

    /** Reader history with book and user loaded (LoanMapper needs both). Only to-one associations are fetched, so pagination happens in SQL. */
    @Query(value = """
            select l from Loan l
            join fetch l.book
            join fetch l.user
            where l.user.id = :userId
            """,
            countQuery = "select count(l) from Loan l where l.user.id = :userId")
    Page<Loan> findByUserIdWithBook(@Param("userId") Long userId, Pageable pageable);

    /** Open loans past their expected return date, for GET /prestamos/atrasados. */
    @Query(value = """
            select l from Loan l
            join fetch l.user
            join fetch l.book
            where l.status in :statuses and l.expectedReturnDate < :today
            """,
            countQuery = """
            select count(l) from Loan l
            where l.status in :statuses and l.expectedReturnDate < :today
            """)
    Page<Loan> findOverdue(@Param("statuses") Collection<LoanStatus> statuses,
                           @Param("today") LocalDate today,
                           Pageable pageable);
}