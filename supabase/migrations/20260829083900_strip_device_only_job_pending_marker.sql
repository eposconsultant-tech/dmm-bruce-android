create or replace function public.dmm_strip_device_only_job_fields()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.entity_type = 'jobs' then
    new.data := coalesce(new.data, '{}'::jsonb) - '_pendingCloudUpload';
  end if;
  return new;
end;
$$;

revoke all on function public.dmm_strip_device_only_job_fields() from public, anon, authenticated;

drop trigger if exists dmm_strip_device_only_job_fields_trigger on public.dmm_entities;
create trigger dmm_strip_device_only_job_fields_trigger
before insert or update on public.dmm_entities
for each row
execute function public.dmm_strip_device_only_job_fields();

update public.dmm_entities
set data = data - '_pendingCloudUpload'
where entity_type = 'jobs' and data ? '_pendingCloudUpload';

comment on function public.dmm_strip_device_only_job_fields() is
'Prevents Android device-local sync markers from being persisted in shared cloud job JSON.';
