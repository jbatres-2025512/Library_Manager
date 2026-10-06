-- =====================================================================
-- Library Manager API - seed data (DEVELOPMENT / EVALUATION ONLY)
-- Idempotent: safe to run on every startup (no explicit IDs, ON CONFLICT /
-- NOT EXISTS guards). Change these passwords outside local testing!
--
--   admin@library.com      / Admin123!      (ADMIN)
--   librarian@library.com  / Librarian123!  (BIBLIOTECARIO)
--   reader1..4@library.com / Reader123!     (LECTOR)
-- =====================================================================

INSERT INTO users (name, email, password, status, role) VALUES
    ('System Admin',       'admin@library.com',     '$2a$10$fwkYeH67ejCMEosMQI0Um.zmUk.3Nx1BO7lqIfu8cF3gLri7mW2MO',  'ACTIVO',     'ADMIN'),
    ('Lucia Librarian',    'librarian@library.com', '$2a$10$DDDjSq5bOuZU934Yn/Z4NOl8280M8d1ANtLYHms47KYhNUUHhMWLS',    'ACTIVO',     'BIBLIOTECARIO'),
    ('Reader One',         'reader1@library.com',   '$2a$10$TsHE71VUnomhFVdxUqNaxOdevSczTbFHUCb48NUNSpvo3cynlMlIe', 'ACTIVO',     'LECTOR'),
    ('Reader Two (limit)', 'reader2@library.com',   '$2a$10$TsHE71VUnomhFVdxUqNaxOdevSczTbFHUCb48NUNSpvo3cynlMlIe', 'ACTIVO',     'LECTOR'),
    ('Reader Three (overdue)', 'reader3@library.com', '$2a$10$TsHE71VUnomhFVdxUqNaxOdevSczTbFHUCb48NUNSpvo3cynlMlIe', 'ACTIVO',   'LECTOR'),
    ('Reader Four (sanctioned)', 'reader4@library.com', '$2a$10$TsHE71VUnomhFVdxUqNaxOdevSczTbFHUCb48NUNSpvo3cynlMlIe', 'SANCIONADO', 'LECTOR')
ON CONFLICT (email) DO NOTHING;

-- available_stock already accounts for the seeded not-returned loans below.
INSERT INTO books (isbn, title, author, category, total_stock, available_stock, deleted) VALUES
    ('9780134685991', 'Effective Java',                 'Joshua Bloch',        'Programming',           5, 4, FALSE),
    ('9780132350884', 'Clean Code',                     'Robert C. Martin',    'Programming',           4, 4, FALSE),
    ('9780201633610', 'Design Patterns',                'Erich Gamma et al.',  'Programming',           3, 2, FALSE),
    ('9780596007126', 'Head First Design Patterns',     'Eric Freeman',        'Programming',           3, 2, FALSE),
    ('9780321125217', 'Domain-Driven Design',           'Eric Evans',          'Software Architecture', 2, 1, FALSE),
    ('9780262033848', 'Introduction to Algorithms',     'Thomas H. Cormen',    'Computer Science',      4, 3, FALSE),
    ('9780060850524', 'Brave New World',                'Aldous Huxley',       'Fiction',               1, 0, FALSE),
    ('9780451524935', '1984',                           'George Orwell',       'Fiction',               3, 3, FALSE),
    ('9788437604947', 'Cien años de soledad',           'Gabriel García Márquez', 'Literature',         2, 2, FALSE),
    ('9780000000002', 'Single Copy Test Book',          'Test Author',         'Testing',               1, 1, FALSE)
ON CONFLICT (isbn) DO NOTHING;

-- Seed loans (dates relative to today so scenarios stay valid):
--   reader1: 1 active + 1 returned          -> happy path / history
--   reader2: 3 active                       -> hits the 3-loan limit
--   reader3: 1 ACTIVO but past due date     -> gets SANCIONADO on next loan attempt
--   reader4: SANCIONADO with ATRASADO loan  -> loan rejected; 'Brave New World' has no stock
INSERT INTO loans (user_id, book_id, loan_date, expected_return_date, actual_return_date, status)
SELECT u.id, b.id, v.loan_date, v.expected_return_date, v.actual_return_date, v.status
FROM (VALUES
    ('reader1@library.com', '9780134685991', CURRENT_DATE - 2,  CURRENT_DATE + 12, NULL::date,         'ACTIVO'),
    ('reader1@library.com', '9780132350884', CURRENT_DATE - 30, CURRENT_DATE - 16, CURRENT_DATE - 20,  'DEVUELTO'),
    ('reader2@library.com', '9780201633610', CURRENT_DATE - 3,  CURRENT_DATE + 11, NULL::date,         'ACTIVO'),
    ('reader2@library.com', '9780596007126', CURRENT_DATE - 3,  CURRENT_DATE + 11, NULL::date,         'ACTIVO'),
    ('reader2@library.com', '9780321125217', CURRENT_DATE - 3,  CURRENT_DATE + 11, NULL::date,         'ACTIVO'),
    ('reader3@library.com', '9780262033848', CURRENT_DATE - 20, CURRENT_DATE - 6,  NULL::date,         'ACTIVO'),
    ('reader4@library.com', '9780060850524', CURRENT_DATE - 35, CURRENT_DATE - 21, NULL::date,         'ATRASADO')
) AS v(email, isbn, loan_date, expected_return_date, actual_return_date, status)
JOIN users u ON u.email = v.email
JOIN books b ON b.isbn  = v.isbn
WHERE NOT EXISTS (
    SELECT 1 FROM loans l WHERE l.user_id = u.id AND l.book_id = b.id
);