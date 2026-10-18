-- Metadata-only capability for an independent verifier. No user values are exposed.
-- PUBLIC cannot execute it; deployment grants EXECUTE only to its dedicated login.
CREATE FUNCTION public.recovery_observation(initial_check boolean DEFAULT false)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
 WITH migration AS (
   SELECT coalesce(string_agg(installed_rank::text || ':' ||
     coalesce(checksum::text,'') || ':' || success::text, ',' ORDER BY installed_rank),'') AS signature,
     count(*) FILTER (WHERE NOT success) AS failures
   FROM public.flyway_schema_history
 ), identity AS (
   SELECT c.system_identifier::text || ':' || d.oid::text || ':' ||
     (extract(epoch from pg_postmaster_start_time())*1000000)::bigint::text || ':' || m.signature AS physical,
     nullif(current_setting('neon.tenant_id',true),'') || ':' ||
     nullif(current_setting('neon.timeline_id',true),'') || ':' ||
     d.oid::text || ':' || m.signature AS stable,
     m.failures
   FROM pg_control_system() c, pg_database d, migration m
   WHERE d.datname=current_database()
 )
 SELECT jsonb_build_object('physical', physical, 'stable', stable,
   'migrationFailures', failures,
   'empty', CASE WHEN initial_check THEN
     NOT EXISTS(SELECT 1 FROM public.users) AND
     NOT EXISTS(SELECT 1 FROM privacy.deletion_ledger) AND
     NOT EXISTS(SELECT 1 FROM public.auth_sessions) AND
     NOT EXISTS(SELECT 1 FROM public.refresh_tokens) AND
     NOT EXISTS(SELECT 1 FROM public.operation_keys) AND
     NOT EXISTS(SELECT 1 FROM public.categories) AND
     NOT EXISTS(SELECT 1 FROM public.places) AND
     NOT EXISTS(SELECT 1 FROM public.transactions) AND
     NOT EXISTS(SELECT 1 FROM public.transaction_locations)
     ELSE NULL END)
 FROM identity;
$function$;
REVOKE ALL ON FUNCTION public.recovery_observation(boolean) FROM PUBLIC;
