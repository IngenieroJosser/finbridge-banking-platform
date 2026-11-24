-- Forward-only external clearing and provider delivery. Existing internal journals remain unique.
ALTER TABLE account DROP CONSTRAINT account_account_type_check;
ALTER TABLE account ADD CONSTRAINT account_account_type_check CHECK(account_type IN ('CUSTOMER_LIABILITY','BANK_ASSET','BANK_CLEARING_LIABILITY'));
INSERT INTO account(id,customer_id,currency,status,account_type) VALUES
 ('00000000-0000-4000-8000-000000000003','00000000-0000-4000-8000-000000000001','COP','ACTIVE','BANK_CLEARING_LIABILITY');
INSERT INTO account_balance(account_id,available_amount) VALUES ('00000000-0000-4000-8000-000000000003',0);
CREATE OR REPLACE FUNCTION fb_apply_posting() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE account_balance b SET available_amount=b.available_amount+
 CASE WHEN a.account_type IN ('CUSTOMER_LIABILITY','BANK_CLEARING_LIABILITY') THEN
  CASE WHEN NEW.side='CREDIT' THEN NEW.amount ELSE -NEW.amount END
 ELSE CASE WHEN NEW.side='DEBIT' THEN NEW.amount ELSE -NEW.amount END END, version=b.version+1
 FROM account a WHERE a.id=NEW.account_id AND b.account_id=a.id;
 IF NOT FOUND THEN RAISE EXCEPTION 'missing balance projection' USING ERRCODE='23503'; END IF;
 RETURN NEW;
END; $$;
ALTER TABLE transfer ADD COLUMN provider_bank_code varchar(20), ADD COLUMN destination_account_reference varchar(64),
 ADD COLUMN provider_transfer_id varchar(128), ADD COLUMN settlement_date date;
ALTER TABLE transfer ADD CONSTRAINT ck_external_details CHECK(transfer_type<>'EXTERNAL' OR
 (provider_bank_code IS NOT NULL AND destination_account_reference IS NOT NULL AND correlation_id IS NOT NULL AND
 status IN ('RESERVED','SUBMITTED','IN_REVIEW','SETTLED','REJECTED') AND
 (status NOT IN ('SUBMITTED','SETTLED','REJECTED') OR provider_transfer_id IS NOT NULL) AND
 ((status IN ('SETTLED','REJECTED')) = (settlement_date IS NOT NULL))));
CREATE UNIQUE INDEX ux_external_provider_id ON transfer(provider_transfer_id) WHERE provider_transfer_id IS NOT NULL;
CREATE INDEX idx_external_settlement ON transfer(settlement_date,id) WHERE transfer_type='EXTERNAL';
ALTER TABLE journal DROP CONSTRAINT journal_transfer_id_key;
ALTER TABLE journal ADD CONSTRAINT uq_journal_transfer_kind UNIQUE(transfer_id,journal_type);
CREATE UNIQUE INDEX ux_internal_single_journal ON journal(transfer_id) WHERE journal_type='INTERNAL_TRANSFER';
ALTER TABLE journal DROP CONSTRAINT journal_journal_type_check;
ALTER TABLE journal ADD CONSTRAINT journal_journal_type_check CHECK(journal_type IN ('OPENING_FUNDING','INTERNAL_TRANSFER','REVERSAL','EXTERNAL_RESERVE','EXTERNAL_SETTLE','EXTERNAL_RELEASE'));
ALTER TABLE journal ADD CONSTRAINT ck_external_journal_target CHECK(journal_type NOT IN ('EXTERNAL_RESERVE','EXTERNAL_SETTLE','EXTERNAL_RELEASE') OR transfer_id IS NOT NULL);
CREATE FUNCTION fb_external_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.transfer_type<>'EXTERNAL' THEN IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'external transfer immutable' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(OLD)-'status'-'provider_transfer_id'-'settlement_date') IS DISTINCT FROM (to_jsonb(NEW)-'status'-'provider_transfer_id'-'settlement_date')
 OR (OLD.provider_transfer_id IS NOT NULL AND OLD.provider_transfer_id IS DISTINCT FROM NEW.provider_transfer_id)
 OR OLD.status IN ('SETTLED','REJECTED')
 OR NOT ((OLD.status='RESERVED' AND NEW.status IN ('SUBMITTED','IN_REVIEW')) OR
 (OLD.status='SUBMITTED' AND NEW.status IN ('SETTLED','REJECTED','IN_REVIEW')) OR
 (OLD.status='IN_REVIEW' AND NEW.status IN ('SETTLED','REJECTED'))) THEN
 RAISE EXCEPTION 'illegal external transfer transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_external_transition BEFORE UPDATE OR DELETE ON transfer FOR EACH ROW EXECUTE FUNCTION fb_external_transition();
ALTER TABLE outbox_event ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN published_at timestamptz, ADD COLUMN last_error varchar(64);
CREATE UNIQUE INDEX ux_external_outbox ON outbox_event(aggregate_id,event_type);
CREATE INDEX idx_outbox_due ON outbox_event(next_attempt_at,created_at,id) WHERE status='PENDING';
ALTER TABLE provider_event ADD COLUMN payload_hash char(64), ADD COLUMN provider_transfer_id varchar(128), ADD COLUMN outcome varchar(32);
CREATE TRIGGER trg_provider_event_immutable BEFORE UPDATE OR DELETE ON provider_event FOR EACH ROW EXECUTE FUNCTION fb_append_only();
-- Provider simulator has its own transaction and independent durable receipt (no FK to bank transaction).
CREATE TABLE simulated_provider_receipt (
 event_id uuid PRIMARY KEY, transfer_id uuid NOT NULL UNIQUE, provider_transfer_id varchar(128) NOT NULL UNIQUE,
 result varchar(16) NOT NULL CHECK(result IN ('SETTLED','REJECTED')), occurred_at timestamptz NOT NULL DEFAULT now(),
 delivered_at timestamptz, callback_attempts integer NOT NULL DEFAULT 0, callback_due_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_simulator_due ON simulated_provider_receipt(callback_due_at) WHERE delivered_at IS NULL;
-- A deferred invariant validates the final persisted state, not intermediate transitions.
CREATE FUNCTION fb_validate_external() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid; t transfer%ROWTYPE; j record; v_debit uuid; v_credit uuid; v_ok boolean; v_count bigint;
BEGIN
 IF TG_TABLE_NAME='transfer' THEN v_id:=NEW.id; ELSIF TG_TABLE_NAME='journal' THEN v_id:=NEW.transfer_id; ELSE v_id:=NEW.aggregate_id; END IF;
 IF v_id IS NULL THEN RETURN NULL; END IF;
 SELECT * INTO t FROM transfer WHERE id=v_id;
 IF NOT FOUND THEN RETURN NULL; END IF;
 IF t.transfer_type<>'EXTERNAL' THEN
  IF EXISTS(SELECT 1 FROM journal x WHERE x.transfer_id=v_id AND x.journal_type IN ('EXTERNAL_RESERVE','EXTERNAL_SETTLE','EXTERNAL_RELEASE')) THEN RAISE EXCEPTION 'external journal on internal transfer' USING ERRCODE='23514'; END IF;
  RETURN NULL;
 END IF;
 SELECT count(*) INTO v_count FROM journal x WHERE x.transfer_id=v_id;
 IF v_count <> (CASE WHEN t.status IN ('SETTLED','REJECTED') THEN 2 ELSE 1 END) OR
 NOT EXISTS(SELECT 1 FROM journal x WHERE x.transfer_id=v_id AND x.journal_type='EXTERNAL_RESERVE') OR
 (t.status='SETTLED' AND NOT EXISTS(SELECT 1 FROM journal x WHERE x.transfer_id=v_id AND x.journal_type='EXTERNAL_SETTLE')) OR
 (t.status='REJECTED' AND NOT EXISTS(SELECT 1 FROM journal x WHERE x.transfer_id=v_id AND x.journal_type='EXTERNAL_RELEASE')) OR
 NOT EXISTS(SELECT 1 FROM outbox_event o WHERE o.aggregate_id=v_id AND o.event_type='TransferRequested') THEN
 RAISE EXCEPTION 'external transfer missing required journal or outbox' USING ERRCODE='23514'; END IF;
 FOR j IN SELECT x.id,x.journal_type,x.status FROM journal x WHERE x.transfer_id=v_id LOOP
  CASE j.journal_type
   WHEN 'EXTERNAL_RESERVE' THEN v_debit:=t.source_id; v_credit:='00000000-0000-4000-8000-000000000003';
   WHEN 'EXTERNAL_SETTLE' THEN v_debit:='00000000-0000-4000-8000-000000000003'; v_credit:='00000000-0000-4000-8000-000000000002';
   WHEN 'EXTERNAL_RELEASE' THEN v_debit:='00000000-0000-4000-8000-000000000003'; v_credit:=t.source_id;
   ELSE RAISE EXCEPTION 'unexpected external journal' USING ERRCODE='23514';
  END CASE;
  SELECT count(*)=2 AND count(*) FILTER(WHERE p.side='DEBIT' AND p.account_id=v_debit AND p.amount=t.amount)=1 AND
   count(*) FILTER(WHERE p.side='CREDIT' AND p.account_id=v_credit AND p.amount=t.amount)=1 INTO v_ok FROM posting p WHERE p.journal_id=j.id;
  IF j.status<>'POSTED' OR NOT v_ok THEN RAISE EXCEPTION 'external postings mismatch' USING ERRCODE='23514'; END IF;
 END LOOP;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER ck_external_transfer AFTER INSERT OR UPDATE ON transfer DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fb_validate_external();
CREATE CONSTRAINT TRIGGER ck_external_journal AFTER INSERT OR UPDATE ON journal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fb_validate_external();
CREATE FUNCTION fb_guard_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-'status'-'attempts'-'next_attempt_at'-'published_at'-'last_error') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'attempts'-'next_attempt_at'-'published_at'-'last_error') OR OLD.status<>'PENDING' OR NEW.attempts<OLD.attempts THEN
  RAISE EXCEPTION 'outbox identity or terminal status immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_guard_outbox BEFORE UPDATE ON outbox_event FOR EACH ROW EXECUTE FUNCTION fb_guard_outbox();
CREATE TRIGGER trg_outbox_no_truncate BEFORE TRUNCATE ON outbox_event FOR EACH STATEMENT EXECUTE FUNCTION fb_append_only();
CREATE TRIGGER trg_provider_event_no_truncate BEFORE TRUNCATE ON provider_event FOR EACH STATEMENT EXECUTE FUNCTION fb_append_only();
CREATE FUNCTION fb_account_kind_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.account_type IS DISTINCT FROM OLD.account_type OR NEW.currency IS DISTINCT FROM OLD.currency THEN RAISE EXCEPTION 'account kind immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_account_kind_immutable BEFORE UPDATE OF account_type,currency ON account FOR EACH ROW EXECUTE FUNCTION fb_account_kind_immutable();
CREATE TRIGGER trg_outbox_no_delete BEFORE DELETE ON outbox_event FOR EACH ROW EXECUTE FUNCTION fb_append_only();
CREATE TRIGGER trg_journal_no_truncate BEFORE TRUNCATE ON journal FOR EACH STATEMENT EXECUTE FUNCTION fb_append_only();
CREATE TRIGGER trg_posting_no_truncate BEFORE TRUNCATE ON posting FOR EACH STATEMENT EXECUTE FUNCTION fb_append_only();
-- Report rows are bounded, sanitized evidence; never mutate the ledger during reconciliation.
ALTER TABLE reconciliation_item ADD COLUMN row_number integer, ADD COLUMN provider_transfer_id varchar(128);
CREATE INDEX idx_reconciliation_page ON reconciliation_item(batch_id,row_number,id);
