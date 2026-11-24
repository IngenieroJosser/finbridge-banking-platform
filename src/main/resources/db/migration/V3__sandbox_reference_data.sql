-- Datos exclusivamente FICTICIOS para hacer el ejercicio repetible desde una base vacía.
-- En producción, reemplazar por onboarding gobernado, no llevar seeds de sandbox.
INSERT INTO customer(id, external_ref, status) VALUES
('00000000-0000-4000-8000-000000000001','NOVABANK_SANDBOX','ACTIVE'),
('00000000-0000-4000-8000-000000000011','CUSTOMER_SANDBOX_A','ACTIVE'),
('00000000-0000-4000-8000-000000000012','CUSTOMER_SANDBOX_B','ACTIVE');
INSERT INTO account(id, customer_id, currency, status, account_type) VALUES
('00000000-0000-4000-8000-000000000002',
 '00000000-0000-4000-8000-000000000001','COP','ACTIVE','BANK_ASSET');
INSERT INTO account_balance(account_id,available_amount) VALUES
('00000000-0000-4000-8000-000000000002',0.00);
