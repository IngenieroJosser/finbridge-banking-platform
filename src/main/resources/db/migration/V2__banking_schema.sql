-- FB-001: modelo contable, invariantes financieros y controles de auditoría.
-- Nunca reescribir V1. Diseñado para PostgreSQL 16; importes NUMERIC(19,2).
CREATE TABLE customer (
    id UUID PRIMARY KEY,
    external_ref VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'BLOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE account (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customer(id),
    currency VARCHAR(3) NOT NULL DEFAULT 'COP' CHECK (currency = 'COP'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    -- Un depósito de cliente es PASIVO; la contrapartida de fondeo es ACTIVO del banco.
    account_type VARCHAR(24) NOT NULL CHECK (account_type IN ('CUSTOMER_LIABILITY', 'BANK_ASSET')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_id_currency UNIQUE (id, currency)
);
CREATE INDEX idx_account_customer ON account(customer_id, created_at DESC);

-- Proyección derivada SOLO mediante el trigger de posting; no representa un libro contable.
CREATE TABLE account_balance (
    account_id UUID PRIMARY KEY REFERENCES account(id),
    available_amount NUMERIC(19,2) NOT NULL DEFAULT 0.00 CHECK (available_amount >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE transfer (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES account(id),
    destination_id UUID REFERENCES account(id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0 AND amount <= 50000000.00),
    currency VARCHAR(3) NOT NULL DEFAULT 'COP' CHECK (currency = 'COP'),
    transfer_type VARCHAR(16) NOT NULL CHECK (transfer_type IN ('INTERNAL', 'EXTERNAL')),
    status VARCHAR(16) NOT NULL CHECK (status IN
        ('RECEIVED','RESERVED','SUBMITTED','COMPLETED','SETTLED','REJECTED','IN_REVIEW')),
    reference VARCHAR(140),
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_transfer_endpoints CHECK (
        (transfer_type = 'INTERNAL' AND destination_id IS NOT NULL AND source_id <> destination_id)
        OR (transfer_type = 'EXTERNAL' AND destination_id IS NULL)
    )
);
CREATE INDEX idx_transfer_source_date ON transfer(source_id, created_at DESC, id DESC);
CREATE INDEX idx_transfer_destination_date ON transfer(destination_id, created_at DESC, id DESC);

CREATE TABLE journal (
    id UUID PRIMARY KEY,
    transfer_id UUID UNIQUE REFERENCES transfer(id),
    opening_account_id UUID REFERENCES account(id),
    journal_type VARCHAR(32) NOT NULL
        CHECK (journal_type IN ('OPENING_FUNDING', 'INTERNAL_TRANSFER', 'REVERSAL')),
    currency VARCHAR(3) NOT NULL DEFAULT 'COP' CHECK (currency = 'COP'),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'POSTED')),
    business_key VARCHAR(64) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at TIMESTAMPTZ,
    CONSTRAINT uq_journal_id_currency UNIQUE (id, currency),
    CONSTRAINT ck_journal_completion CHECK (
        (status = 'DRAFT' AND posted_at IS NULL)
        OR (status = 'POSTED' AND posted_at IS NOT NULL)
    ),
    CONSTRAINT ck_journal_business_target CHECK (
        (journal_type = 'OPENING_FUNDING' AND opening_account_id IS NOT NULL AND transfer_id IS NULL)
        OR (journal_type <> 'OPENING_FUNDING' AND opening_account_id IS NULL)
    )
);
-- Un único fondeo de apertura por cuenta; los reintentos usan idempotency_record.
CREATE UNIQUE INDEX ux_journal_one_opening ON journal(opening_account_id)
    WHERE journal_type = 'OPENING_FUNDING';

CREATE TABLE posting (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL,
    account_id UUID NOT NULL,
    side VARCHAR(6) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'COP' CHECK (currency = 'COP'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_posting_journal_currency FOREIGN KEY (journal_id, currency)
        REFERENCES journal(id, currency),
    CONSTRAINT fk_posting_account_currency FOREIGN KEY (account_id, currency)
        REFERENCES account(id, currency)
);
CREATE INDEX idx_posting_account_date ON posting(account_id, occurred_at DESC, id DESC);
CREATE INDEX idx_posting_journal ON posting(journal_id);

CREATE TABLE idempotency_record (
    actor_id UUID NOT NULL,
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    request_hash CHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, operation, idempotency_key)
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_audit_aggregate ON audit_event(aggregate_type, aggregate_id, occurred_at DESC);

-- Tablas de continuidad arquitectónica (sprints posteriores); sin flujos activos en Sprint 1.
CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON outbox_event(created_at, id) WHERE status = 'PENDING';

CREATE TABLE provider_event (
    id UUID PRIMARY KEY,
    provider VARCHAR(80) NOT NULL,
    external_event_id VARCHAR(128) NOT NULL,
    transfer_id UUID NOT NULL REFERENCES transfer(id),
    status VARCHAR(20) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_event UNIQUE(provider, external_event_id)
);

CREATE TABLE reconciliation_batch (
    id UUID PRIMARY KEY,
    report_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RECEIVED','PROCESSING','COMPLETED','FAILED')),
    matched_count INTEGER NOT NULL DEFAULT 0 CHECK (matched_count >= 0),
    mismatched_count INTEGER NOT NULL DEFAULT 0 CHECK (mismatched_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE reconciliation_item (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES reconciliation_batch(id),
    transfer_id UUID REFERENCES transfer(id),
    category VARCHAR(40) NOT NULL,
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reconciliation_item_batch ON reconciliation_item(batch_id, id);

-- Ninguna operación del ledger puede reescribir o eliminar una partida ya registrada.
CREATE FUNCTION fb_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'append-only: % no permite %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_posting_immutable BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION fb_append_only();
CREATE TRIGGER trg_audit_immutable BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION fb_append_only();

-- Journal: únicamente DRAFT -> POSTED, sin alterar identidades ni campos financieros.
CREATE FUNCTION fb_journal_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'journal append-only' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'DRAFT' OR NEW.status <> 'POSTED' OR NEW.posted_at IS NULL
       OR (to_jsonb(NEW) - 'status' - 'posted_at')
          IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'posted_at') THEN
        RAISE EXCEPTION 'journal: solo se permite DRAFT -> POSTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_journal_transition BEFORE UPDATE OR DELETE ON journal
    FOR EACH ROW EXECUTE FUNCTION fb_journal_transition();

-- Serializa cada journal frente a publicaciones tardías o concurrentes.
CREATE FUNCTION fb_guard_posting_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_status VARCHAR(16);
BEGIN
    SELECT status INTO current_status FROM journal WHERE id = NEW.journal_id FOR UPDATE;
    IF current_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'no se pueden agregar postings a un journal publicado'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_guard_posting BEFORE INSERT ON posting
    FOR EACH ROW EXECUTE FUNCTION fb_guard_posting_insert();

-- La única forma programada de actualizar una proyección es insertar un posting.
-- pg_trigger_depth discrimina esta ruta interna, NO reemplaza roles/permisos de BD.
CREATE FUNCTION fb_balance_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'account_balance es una proyección del posting'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_guard_balance BEFORE UPDATE OR DELETE ON account_balance
    FOR EACH ROW EXECUTE FUNCTION fb_balance_write_guard();

-- Proyección contable: pasivo (CREDIT aumenta, DEBIT reduce),
-- activo (DEBIT aumenta, CREDIT reduce). El CHECK impide sobregiro.
CREATE FUNCTION fb_apply_posting() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE account_balance b
       SET available_amount = b.available_amount +
           CASE WHEN a.account_type = 'CUSTOMER_LIABILITY' THEN
               CASE WHEN NEW.side = 'CREDIT' THEN NEW.amount ELSE -NEW.amount END
           ELSE
               CASE WHEN NEW.side = 'DEBIT' THEN NEW.amount ELSE -NEW.amount END
           END,
           version = b.version + 1
      FROM account a
     WHERE a.id = NEW.account_id AND b.account_id = a.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'sin proyección para la cuenta %', NEW.account_id
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apply_posting AFTER INSERT ON posting
    FOR EACH ROW EXECUTE FUNCTION fb_apply_posting();

-- Restricciones agregadas validadas en COMMIT, no con un CHECK por fila.
-- Se verifica el estado final del journal aunque haya empezado como DRAFT.
CREATE FUNCTION fb_validate_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    j_id UUID;
    j_status VARCHAR(16);
    n BIGINT;
    debits NUMERIC(30,2);
    credits NUMERIC(30,2);
BEGIN
    IF TG_TABLE_NAME = 'journal' THEN j_id := NEW.id;
    ELSE j_id := NEW.journal_id;
    END IF;
    SELECT status INTO j_status FROM journal WHERE id = j_id;
    SELECT count(*),
           coalesce(sum(amount) FILTER (WHERE side = 'DEBIT'), 0),
           coalesce(sum(amount) FILTER (WHERE side = 'CREDIT'), 0)
      INTO n, debits, credits FROM posting WHERE journal_id = j_id;
    IF j_status <> 'POSTED' OR n < 2 OR debits <> credits OR debits <= 0 THEN
        RAISE EXCEPTION 'journal % incompleto o desbalanceado (status %, n %, debit %, credit %)',
            j_id, j_status, n, debits, credits USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ck_posted_journal AFTER INSERT OR UPDATE ON journal
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fb_validate_journal();
CREATE CONSTRAINT TRIGGER ck_balanced_postings AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fb_validate_journal();
