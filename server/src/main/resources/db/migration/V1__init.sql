-- Apex: contas e sincronização.

create table users (
    id             uuid        primary key default gen_random_uuid(),
    email          text        not null,
    email_verified boolean     not null default false,
    -- Nulo para quem só entra com Google/Twitch.
    password_hash  text,
    display_name   text,
    avatar_url     text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

create unique index users_email_key on users (lower(email));

-- Sessões: guardamos só o hash do token. Cada renovação gira o token (o antigo é revogado).
create table refresh_tokens (
    id         uuid        primary key default gen_random_uuid(),
    user_id    uuid        not null references users (id) on delete cascade,
    token_hash text        not null unique,
    device     text,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz
);

create index refresh_tokens_user_idx on refresh_tokens (user_id);

-- Links de confirmar e-mail e redefinir senha (uso único).
create table email_tokens (
    token_hash text        primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    purpose    text        not null check (purpose in ('verify', 'reset')),
    expires_at timestamptz not null,
    used_at    timestamptz
);

-- Dados sincronizados: uma linha por item.
create table sync_items (
    user_id     uuid        not null references users (id) on delete cascade,
    collection  text        not null check (collection in ('subscriptions', 'history', 'watch_later', 'reactions', 'playlists', 'settings')),
    key         text        not null check (char_length(key) between 1 and 200),
    data        jsonb       check (data is null or pg_column_size(data) < 65536),
    -- Relógio do aparelho: decide quem ganha num conflito (a edição mais recente).
    modified_at bigint      not null,
    deleted     boolean     not null default false,
    deleted_at  timestamptz,
    -- Relógio do servidor: é o cursor do "me dê o que mudou".
    updated_at  timestamptz not null default clock_timestamp(),
    primary key (user_id, collection, key)
);

create index sync_items_pull_idx on sync_items (user_id, updated_at);
