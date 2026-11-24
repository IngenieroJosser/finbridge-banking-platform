-- Hotfix Sprint 2 (forward-only): PostgreSQL SQLSTATE 42702 durante COMMIT.
-- V4 se conserva: ya pudo ser aplicada a las bases de datos existentes.
-- Una variable local "journal_id" colisionaba con la columna posting.journal_id.
-- CREATE OR REPLACE FUNCTION preserva el trigger ck_internal_transfer_integrity
-- y permite que Flyway aplique el arreglo en instalaciones nuevas y existentes.
CREATE OR REPLACE FUNCTION fb_validate_completed_internal_transfer()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_posted_journal_id UUID;
    v_postings_match BOOLEAN;
BEGIN
    IF NEW.transfer_type <> 'INTERNAL' OR NEW.status <> 'COMPLETED' THEN
        RETURN NULL;
    END IF;

    SELECT j.id
      INTO v_posted_journal_id
      FROM journal AS j
     WHERE j.transfer_id = NEW.id
       AND j.journal_type = 'INTERNAL_TRANSFER'
       AND j.status = 'POSTED';

    IF v_posted_journal_id IS NULL THEN
        RAISE EXCEPTION 'transferencia interna % sin journal POSTED', NEW.id
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*) = 2
           AND COUNT(*) FILTER (
               WHERE p.account_id = NEW.source_id
                 AND p.side = 'DEBIT'
                 AND p.amount = NEW.amount
           ) = 1
           AND COUNT(*) FILTER (
               WHERE p.account_id = NEW.destination_id
                 AND p.side = 'CREDIT'
                 AND p.amount = NEW.amount
           ) = 1
      INTO v_postings_match
      FROM posting AS p
     WHERE p.journal_id = v_posted_journal_id;

    IF NOT COALESCE(v_postings_match, FALSE) THEN
        RAISE EXCEPTION 'transferencia interna %: postings no corresponden al movimiento', NEW.id
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$function$;
