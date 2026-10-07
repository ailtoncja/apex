-- Registro de que a pessoa aceitou os Termos de Uso e a Política de Privacidade (e qual versão).
alter table users add column terms_accepted_at timestamptz;
alter table users add column terms_version text;

-- Apagar contas e limpar links vencidos procura por usuário.
create index email_tokens_user_idx on email_tokens (user_id);
create index sync_items_deleted_idx on sync_items (deleted_at) where deleted;
