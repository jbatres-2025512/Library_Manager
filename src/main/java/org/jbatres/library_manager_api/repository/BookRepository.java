package org.jbatres.library_manager_api.repository;

import org.jbatres.library_manager_api.entity.Book;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Catalog search uses {@link JpaSpecificationExecutor} (see {@link BookSpecifications}) so only
 * the filters actually provided end up in the SQL, which keeps the plans index-friendly.
 */
public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

    Optional<Book> findByIdAndDeletedFalse(Long id);

    Optional<Book> findByIsbn(String isbn);

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, Long id);

    /** Row lock used by admin updates (PUT) so total/available stock are recalculated safely. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Book b where b.id = :id and b.deleted = false")
    Optional<Book> findByIdForUpdate(@Param("id") Long id);

    /**
     * Atomic, conditional stock decrement. Returns 1 if a copy was reserved, 0 if the book does
     * not exist, is deleted or has no stock. Never produces a negative stock, even under
     * heavy concurrency, because check and update happen in one SQL statement.
     * The persistence context is NOT cleared on purpose (it would detach the locked reader);
     * callers must not rely on a previously loaded Book instance after this call.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Book b set b.availableStock = b.availableStock - 1
            where b.id = :id and b.deleted = false and b.availableStock > 0
            """)
    int decrementAvailableStock(@Param("id") Long id);

    /**
     * Atomic stock increment, capped at totalStock. Returns 0 if the cap would be exceeded
     * (data inconsistency that the caller must treat as an error).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Book b set b.availableStock = b.availableStock + 1
            where b.id = :id and b.availableStock < b.totalStock
            """)
    int incrementAvailableStock(@Param("id") Long id);


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Book b
               SET b.isbn = :isbn,
                   b.title = :title,
                   b.author = :author,
                   b.category = :category,
                   b.availableStock = b.availableStock + (:totalStock - b.totalStock),
                   b.totalStock = :totalStock
             WHERE b.id = :id
               AND b.deleted = false
               AND b.availableStock + (:totalStock - b.totalStock) >= 0
            """)
    int updateBook(@Param("id") Long id,
                   @Param("isbn") String isbn,
                   @Param("title") String title,
                   @Param("author") String author,
                   @Param("category") String category,
                   @Param("totalStock") int totalStock);

    /**
     * Atomic soft delete. availableStock = totalStock means no copy is on loan.
     * Returns 0 if the book does not exist, is already deleted, or has copies on loan.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Book b
               SET b.deleted = true
             WHERE b.id = :id
               AND b.deleted = false
               AND b.availableStock = b.totalStock
            """)
    int softDeleteIfNoCopiesOnLoan(@Param("id") Long id);
}