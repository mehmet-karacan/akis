-- Transfer-memory budget is independent of total dataset size and JVM RSS.
update akis.ortam set politika=jsonb_set(coalesce(politika,'{}'::jsonb),'{transferMemoryBudgetMiB}','1024'::jsonb)
where not coalesce(politika,'{}'::jsonb) ? 'transferMemoryBudgetMiB';

alter table akis.ortam alter column politika set default '{"transferMemoryBudgetMiB":1024}'::jsonb;
