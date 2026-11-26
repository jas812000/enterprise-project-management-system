-- B8.1B: single-event confidential audit, with durable authorization outcomes.
-- No new credential access, runtime table grants or edits to applied migrations.
CREATE TABLE public.team_confidential_audit_access (
 access_id uuid PRIMARY KEY DEFAULT uuidv7(),
 actor_user_id uuid NOT NULL,
 requested_team_id uuid NOT NULL,
 requested_event_id uuid NOT NULL,
 source_kind text NOT NULL CHECK (source_kind IN ('OPERATION','LIFECYCLE')),
 outcome text NOT NULL CHECK (outcome IN ('GRANTED','DENIED')),
 access_scope text CHECK (access_scope IN ('SYSTEM_ADMIN','ORG_ADMIN')),
 justification text,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK ((outcome='GRANTED' AND access_scope IS NOT NULL AND justification IS NOT NULL
   AND length(btrim(justification)) BETWEEN 1 AND 500)
  OR (outcome='DENIED' AND access_scope IS NULL AND justification IS NULL))
);
CREATE INDEX ix_team_confidential_audit_actor ON public.team_confidential_audit_access(actor_user_id,occurred_at);
CREATE TRIGGER trg_team_confidential_audit_access_immutable BEFORE UPDATE OR DELETE
 ON public.team_confidential_audit_access FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();
REVOKE ALL ON public.team_confidential_audit_access FROM PUBLIC,epms_app,epms_rbac_runtime,epms_bootstrap_runtime;

-- SQL NULL is a denied outcome, not an exception: the denial record must commit before
-- the application converts it to HTTP 403. Call via the existing autocommit JDBC boundary.
CREATE FUNCTION public.epms_team_command_confidential_event(p_actor uuid,p_team uuid,p_event uuid,
 p_source text,p_justification text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_scope text; v_detail jsonb;
BEGIN
 IF p_actor IS NULL OR p_team IS NULL OR p_event IS NULL OR p_source IS NULL
  OR p_source NOT IN ('OPERATION','LIFECYCLE') OR p_justification IS NULL
  OR length(btrim(p_justification)) NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'Invalid confidential audit request' USING ERRCODE='22023'; END IF;
 PERFORM pg_advisory_xact_lock(1832,12);
 SELECT u.organization_id INTO v_org FROM public.users u WHERE u.user_id=p_actor;
 IF public.epms_team_notification_has_role(p_actor,v_org,ARRAY['SYSTEM_ADMIN']) THEN
  v_scope:='SYSTEM_ADMIN';
 ELSIF public.epms_team_notification_has_role(p_actor,v_org,ARRAY['ORG_ADMIN']) THEN
  v_scope:='ORG_ADMIN';
 END IF;
 IF v_scope IS NOT NULL AND p_source='OPERATION' THEN
  SELECT jsonb_build_object('eventId',o.event_id,'teamId',o.team_id,'sourceKind','OPERATION',
   'eventKind',o.operation,'occurredAt',o.occurred_at,'actorUserId',o.actor_user_id,
   'targetUserId',o.target_user_id,'reason',o.reason,
   'profileBefore',o.profile_before,'profileAfter',o.profile_after,
   'similarTeamIds',o.similar_team_ids,'reviewedNameConflicts',o.reviewed_name_conflicts)
   || CASE WHEN v_scope='SYSTEM_ADMIN' AND a.event_id IS NOT NULL THEN
    jsonb_build_object('accountLifecycle',jsonb_build_object('eventId',a.event_id,
     'eventType',a.event_type,'actorUserId',a.actor_user_id,'targetUserId',a.target_user_id,
     'reason',a.reason,'occurredAt',a.occurred_at)) ELSE '{}'::jsonb END
   INTO v_detail FROM public.team_operation_events o
   LEFT JOIN public.team_account_operation_links l ON l.operation_event_id=o.event_id
   LEFT JOIN public.team_account_lifecycle_events a ON a.event_id=l.account_event_id
   WHERE o.organization_id=v_org AND o.team_id=p_team AND o.event_id=p_event
   AND (v_scope='SYSTEM_ADMIN' OR (l.operation_event_id IS NULL AND o.operation IN
    ('TEAM_CREATE','TEAM_PROFILE_UPDATE','MEMBER_JOIN','MEMBER_LEAVE','LEADER_ASSIGN',
     'LEADER_END','ALTERNATE_DESIGNATE','ALTERNATE_REVOKE')));
 ELSIF v_scope IS NOT NULL AND p_source='LIFECYCLE' THEN
  -- Lifecycle reasons may contain emergency context: restrict full reasons to SYSTEM_ADMIN.
  SELECT jsonb_build_object('eventId',l.event_id,'teamId',l.team_id,'sourceKind','LIFECYCLE',
   'eventKind','TEAM_'||l.to_status,'occurredAt',l.occurred_at,'actorUserId',l.actor_user_id,
   'fromStatus',l.from_status,'toStatus',l.to_status,'resultingVersion',l.resulting_version)
   || CASE WHEN v_scope='SYSTEM_ADMIN' THEN jsonb_build_object('reason',l.reason) ELSE '{}'::jsonb END
   INTO v_detail FROM public.team_lifecycle_events l
   WHERE l.organization_id=v_org AND l.team_id=p_team AND l.event_id=p_event;
 END IF;
 INSERT INTO public.team_confidential_audit_access(actor_user_id,requested_team_id,requested_event_id,
  source_kind,outcome,access_scope,justification)
 VALUES(p_actor,p_team,p_event,p_source,CASE WHEN v_detail IS NULL THEN 'DENIED' ELSE 'GRANTED' END,
  CASE WHEN v_detail IS NOT NULL THEN v_scope END,
  CASE WHEN v_detail IS NOT NULL THEN btrim(p_justification) END);
 RETURN v_detail::text;
END $$;
REVOKE ALL ON FUNCTION public.epms_team_command_confidential_event(uuid,uuid,uuid,text,text)
 FROM PUBLIC,epms_app,epms_rbac_runtime,epms_bootstrap_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_confidential_event(uuid,uuid,uuid,text,text)
 TO epms_rbac_runtime;

-- Metadata history for current TEAM_LEAD relationships; never expose reason or identities here.
CREATE OR REPLACE FUNCTION public.epms_team_command_operation_history(p_actor uuid,p_team uuid,p_limit integer,p_offset integer)
RETURNS TABLE(event_id uuid,source_kind text,event_kind text,occurred_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,p_offset);
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,false);
 IF NOT public.epms_team_notification_has_role(p_actor,v_org,ARRAY['SYSTEM_ADMIN','ORG_ADMIN'])
  AND NOT (public.epms_team_notification_has_role(p_actor,v_org,ARRAY['TEAM_LEAD'])
   AND EXISTS (SELECT 1 FROM public.epms_team_command_profiles(p_actor,p_team,1,0))) THEN
  RAISE EXCEPTION 'Team history access denied' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.teams t WHERE t.team_id=p_team AND t.organization_id=v_org) THEN
  RAISE EXCEPTION 'Team unavailable' USING ERRCODE='42501'; END IF;
 RETURN QUERY SELECT h.event_id,h.source_kind,h.event_kind,h.occurred_at FROM (
  SELECT o.event_id,'OPERATION'::text AS source_kind,o.operation AS event_kind,o.occurred_at
  FROM public.team_operation_events o WHERE o.team_id=p_team AND o.organization_id=v_org
  UNION ALL
  SELECT l.event_id,'LIFECYCLE'::text,'TEAM_'||l.to_status,l.occurred_at
  FROM public.team_lifecycle_events l WHERE l.team_id=p_team AND l.organization_id=v_org
 ) h ORDER BY h.occurred_at DESC,h.event_id,h.source_kind LIMIT p_limit OFFSET p_offset;
END $$;
