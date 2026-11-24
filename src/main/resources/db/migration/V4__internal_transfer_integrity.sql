-- Sprint 2: forward-only. NO modifica V1, V2 ni V3 ya aplicadas.
-- Un journal interno siempre pertenece a una transferencia.
ALTER TABLE journal ADD CONSTRAINT ck_internal_journal_requires_transfer
    CHECK (journal_type <> 'INTERNAL_TRANSFER' OR transfer_id IS NOT NULL);

-- No basta un journal balanceado: sus dos partidas deben corresponder EXACTAMENTE
-- a origen/destino, importe y sentido de la transferencia confirmada.
CREATE FUNCTION fb_validate_completed_internal_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    journal_id UUID;
    ok BOOLEAN;
BEGIN
    IF NEW.transfer_type <> 'INTERNAL' OR NEW.status <> 'COMPLETED' THEN
        RETURN NULL;
    END IF;
    SELECT j.id INTO journal_id FROM journal j
      WHERE j.transfer_id=NEW.id AND j.journal_type='INTERNAL_TRANSFER' AND j.status='POSTED';
    IF journal_id IS NULL THEN
        RAISE EXCEPTION 'transferencia interna % sin journal POSTED', NEW.id
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) = 2
       AND count(*) FILTER (WHERE p.account_id=NEW.source_id
                           AND p.side='DEBIT' AND p.amount=NEW.amount) = 1
       AND count(*) FILTER (WHERE p.account_id=NEW.destination_id
                           AND p.side='CREDIT' AND p.amount=NEW.amount) = 1
      INTO ok FROM posting p WHERE p.journal_id=journal_id;
    IF NOT ok THEN
        RAISE EXCEPTION 'transferencia interna %: postings no corresponden al movimiento', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ck_internal_transfer_integrity
    AFTER INSERT OR UPDATE ON transfer
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fb_validate_completed_internal_transfer();

-- Una transferencia interna confirmada es inmutable; se corrige con asiento compensatorio.
-- Los estados de transferencias EXTERNAS tendrán su propia máquina de estados en Sprint 4.
CREATE FUNCTION fb_immutable_completed_internal_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.transfer_type='INTERNAL' AND OLD.status='COMPLETED' THEN
        RAISE EXCEPTION 'transferencia interna confirmada es inmutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP='UPDATE' THEN RETURN NEW; END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER trg_internal_transfer_immutable BEFORE UPDATE OR DELETE ON transfer
    FOR EACH ROW EXECUTE FUNCTION fb_immutable_completed_internal_transfer();
