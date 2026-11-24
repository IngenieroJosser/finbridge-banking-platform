-- Preserve all historical audit events; migrate legacy internal idempotency scope only.
-- Before JWT, one synthetic actor owned all keys; transfers already enforced same customer.
-- PK collision intentionally fails migration rather than dropping a key or allowing a double debit.
UPDATE idempotency_record AS i
SET actor_id = s.customer_id
FROM transfer AS t JOIN account AS s ON s.id=t.source_id
                   JOIN account AS d ON d.id=t.destination_id
WHERE i.operation='INTERNAL_TRANSFER'
  AND i.actor_id='00000000-0000-4000-8000-000000000091'
  AND i.resource_id=t.id AND t.transfer_type='INTERNAL'
  AND s.customer_id=d.customer_id;

CREATE INDEX idx_audit_correlation ON audit_event(correlation_id, occurred_at, id);
-- UPDATE/DELETE already blocked by V2. TRUNCATE uses a statement trigger.
CREATE TRIGGER trg_audit_no_truncate BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION fb_append_only();

-- An existing account never changes holder in this MVP; close/open instead.
CREATE FUNCTION fb_account_owner_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.customer_id IS DISTINCT FROM OLD.customer_id THEN
        RAISE EXCEPTION 'account owner is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_account_owner_immutable BEFORE UPDATE OF customer_id ON account
    FOR EACH ROW EXECUTE FUNCTION fb_account_owner_immutable();
