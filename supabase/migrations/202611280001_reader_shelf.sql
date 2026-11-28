begin;

create table if not exists public.reader_books (
  owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  name text not null check (length(name) between 1 and 255),
  size bigint not null check (size > 0),
  primary key (owner_id, sha256)
);
create table if not exists public.reader_positions (
  owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  document text not null check (document ~ '^pr1_[0-9a-f]{64}$'),
  revision uuid not null,
  record jsonb not null check (jsonb_typeof(record) = 'object' and record ? 'id' and revision::text = record->>'id'),
  primary key (owner_id, document)
);
alter table public.reader_books enable row level security;
alter table public.reader_positions enable row level security;
revoke all on public.reader_books, public.reader_positions from public, anon, authenticated;
grant select, insert on public.reader_books to authenticated;
grant select, insert on public.reader_positions to authenticated;
grant update (revision, record) on public.reader_positions to authenticated;

create policy reader_books_owner on public.reader_books to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
create policy reader_positions_owner on public.reader_positions to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));

create or replace function public.reader_save_position(p_document text, p_revision uuid, p_record jsonb, p_expected uuid)
returns boolean language plpgsql security invoker set search_path = '' as $$
begin
  if p_expected is null then
    insert into public.reader_positions (document, revision, record) values (p_document, p_revision, p_record)
    on conflict (owner_id, document) do nothing;
  else
    update public.reader_positions set revision = p_revision, record = p_record
    where owner_id = auth.uid() and document = p_document and revision = p_expected;
  end if;
  return found;
end;
$$;
revoke all on function public.reader_save_position(text, uuid, jsonb, uuid) from public, anon;
grant execute on function public.reader_save_position(text, uuid, jsonb, uuid) to authenticated;

insert into storage.buckets(id, name, public) values ('readeraplus-books', 'readeraplus-books', false);
create policy reader_files_read on storage.objects for select to authenticated using (
  bucket_id = 'readeraplus-books' and (storage.foldername(name))[1] = (select auth.uid())::text
);
create policy reader_files_create on storage.objects for insert to authenticated with check (
  bucket_id = 'readeraplus-books' and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f]{64}$')
);
commit;
