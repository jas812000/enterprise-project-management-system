-- B8.1: bounded system dispatch, append-only attempts, and durable alert escalation.
-- Existing source events and V38 recipient rules remain authoritative.
ALTER TABLE public.team_notification_dispatches ALTER COLUMN processed_by_user_id DROP NOT NULL;
ALTER TABLE public.team_notification_dispatches ADD COLUMN processor_kind text NOT NULL DEFAULT 'HUMAN_ADMIN';
ALTER TABLE public.team_notification_dispatches ADD CONSTRAINT ck_team_dispatch_processor CHECK (
 (processor_kind='HUMAN_ADMIN' AND processed_by_user_id IS NOT NULL) OR
 (processor_kind='SYSTEM' AND processed_by_user_id IS NULL));

CREATE TABLE public.team_notification_attempts (
 attempt_id uuid PRIMARY KEY DEFAULT uuidv7(),
 organization_id uuid NOT NULL,
 team_id uuid NOT NULL,
 operation_event_id uuid REFERENCES public.team_operation_events(event_id),
 lifecycle_event_id uuid REFERENCES public.team_lifecycle_events(event_id),
 attempt_no integer NOT NULL CHECK (attempt_no>0),
 processor_kind text NOT NULL,
 actor_user_id uuid,
 outcome text NOT NULL CHECK (outcome IN ('DELIVERED','FAILED')),
 failure_sqlstate text CHECK (failure_sqlstate ~ '^[A-Z0-9]{5}$'),
 attempted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 next_retry_at timestamptz,
 automatic_limit integer NOT NULL CHECK (automatic_limit BETWEEN 1 AND 20),
 CHECK (num_nonnulls(operation_event_id,lifecycle_event_id)=1),
 CHECK ((processor_kind='HUMAN_ADMIN' AND actor_user_id IS NOT NULL) OR
        (processor_kind='SYSTEM' AND actor_user_id IS NULL)),
 CHECK ((outcome='DELIVERED' AND failure_sqlstate IS NULL AND next_retry_at IS NULL) OR
        (outcome='FAILED' AND failure_sqlstate IS NOT NULL)),
 FOREIGN KEY (organization_id,actor_user_id) REFERENCES public.users(organization_id,user_id)
);
CREATE UNIQUE INDEX ux_team_notification_operation_attempt ON public.team_notification_attempts
 (operation_event_id,attempt_no) WHERE operation_event_id IS NOT NULL;
CREATE UNIQUE INDEX ux_team_notification_lifecycle_attempt ON public.team_notification_attempts
 (lifecycle_event_id,attempt_no) WHERE lifecycle_event_id IS NOT NULL;
CREATE INDEX ix_team_notification_attempts_org ON public.team_notification_attempts(organization_id,attempted_at);
CREATE TRIGGER trg_team_notification_attempts_immutable BEFORE UPDATE OR DELETE
 ON public.team_notification_attempts FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();

CREATE TABLE public.team_security_alert_escalations (
 alert_id uuid NOT NULL REFERENCES public.team_security_alerts(alert_id),
 escalation_no integer NOT NULL CHECK (escalation_no>0),
 stage text NOT NULL CHECK (stage IN ('UNACKNOWLEDGED','UNRESOLVED')),
 due_at timestamptz NOT NULL,
 escalated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(alert_id,escalation_no),
 CHECK (escalated_at>=due_at)
);
CREATE TRIGGER trg_team_alert_escalations_immutable BEFORE UPDATE OR DELETE
 ON public.team_security_alert_escalations FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();
REVOKE ALL ON public.team_notification_attempts,public.team_security_alert_escalations
 FROM PUBLIC,epms_app,epms_rbac_runtime,epms_bootstrap_runtime;

-- This helper is owner-only. The fixed entry points below establish the actor/provenance.
-- Each system command handles at most one source event in its own JDBC transaction.
-- Failed recipient writes and their marker roll back together; the attempt survives that subtransaction.
CREATE FUNCTION public.epms_team_dispatch_pending(p_org uuid,p_actor uuid,p_limit integer,
 p_max_attempts integer,p_base_seconds integer,p_max_seconds integer)
RETURNS integer LANGUAGE plpgsql SET search_path=pg_catalog SET lock_timeout='2s' AS $$
DECLARE v_org uuid:=p_org; v_dispatch uuid; v_count integer:=0; e record;
 v_attempt integer; v_state text; v_retry timestamptz; v_processor text;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,0);
 IF p_max_attempts IS NULL OR p_max_attempts NOT BETWEEN 1 AND 20
 OR p_base_seconds IS NULL OR p_base_seconds NOT BETWEEN 1 AND 86400
 OR p_max_seconds IS NULL OR p_max_seconds NOT BETWEEN p_base_seconds AND 86400 THEN
  RAISE EXCEPTION 'Invalid dispatch retry configuration' USING ERRCODE='22023'; END IF;
 v_processor:=CASE WHEN p_actor IS NULL THEN 'SYSTEM' ELSE 'HUMAN_ADMIN' END;
 IF v_org IS NULL THEN RETURN 0; END IF;
 FOR e IN
  SELECT src.*,coalesce(previous.attempt_no,0) AS previous_attempt FROM (
   SELECT o.event_id AS source_id,'OPERATION'::text AS source_kind,o.team_id,o.target_user_id,
    o.operation AS kind,o.occurred_at,a.alert_id
   FROM public.team_operation_events o LEFT JOIN public.team_security_alerts a ON a.event_id=o.event_id
   WHERE o.organization_id=v_org AND o.operation IN ('MEMBER_JOIN','MEMBER_LEAVE','LEADER_ASSIGN',
    'LEADER_END','ALTERNATE_DESIGNATE','ALTERNATE_REVOKE','EMERGENCY')
   AND NOT EXISTS (SELECT 1 FROM public.team_notification_dispatches d WHERE d.operation_event_id=o.event_id)
   UNION ALL
   SELECT l.event_id,'LIFECYCLE',l.team_id,NULL::uuid,'TEAM_'||l.to_status,l.occurred_at,NULL::uuid
   FROM public.team_lifecycle_events l WHERE l.organization_id=v_org
   AND NOT EXISTS (SELECT 1 FROM public.team_notification_dispatches d WHERE d.lifecycle_event_id=l.event_id)
  ) src LEFT JOIN LATERAL (
   SELECT a.attempt_no,a.next_retry_at FROM public.team_notification_attempts a
   WHERE (src.source_kind='OPERATION' AND a.operation_event_id=src.source_id)
      OR (src.source_kind='LIFECYCLE' AND a.lifecycle_event_id=src.source_id)
   ORDER BY a.attempt_no DESC LIMIT 1
  ) previous ON true
  WHERE p_actor IS NOT NULL OR (coalesce(previous.attempt_no,0)<p_max_attempts
    AND (previous.next_retry_at IS NULL OR previous.next_retry_at<=clock_timestamp()))
  ORDER BY src.occurred_at,src.source_id,src.source_kind LIMIT p_limit
 LOOP
  v_attempt:=e.previous_attempt+1;
  v_state:=NULL;
  v_retry:=NULL;
  BEGIN
   INSERT INTO public.team_notification_dispatches(organization_id,team_id,operation_event_id,
    lifecycle_event_id,processed_by_user_id,processor_kind)
   VALUES(v_org,e.team_id,CASE WHEN e.source_kind='OPERATION' THEN e.source_id END,
    CASE WHEN e.source_kind='LIFECYCLE' THEN e.source_id END,p_actor,v_processor)
   RETURNING dispatch_id INTO v_dispatch;
  -- ADMIN is the approved fallback for project-manager routing. Direct personal notices
  -- do not authorize Team history. Ordinary members must have both event-time and current membership.
  -- Alternate identities and account/removal reasons are never put in recipient payloads.
  INSERT INTO public.team_notifications(dispatch_id,organization_id,recipient_user_id,audience,kind,severity)
  SELECT v_dispatch,v_org,c.user_id,c.audience,
   CASE WHEN c.audience='ADMIN' AND e.alert_id IS NOT NULL THEN 'NO_ELIGIBLE_SUCCESSOR'
        WHEN e.kind='EMERGENCY' THEN 'LEADERSHIP_CHANGED' ELSE e.kind END,
   CASE WHEN c.audience='ADMIN' AND e.alert_id IS NOT NULL THEN 'CRITICAL' ELSE 'INFO' END
  FROM (
   SELECT DISTINCT ON (eligible.user_id) eligible.user_id,eligible.audience FROM (
    SELECT u.user_id,'ADMIN'::text AS audience,1 AS precedence FROM public.users u WHERE u.organization_id=v_org
    UNION ALL
    SELECT e.target_user_id,'PERSONAL',2 WHERE e.target_user_id IS NOT NULL
    UNION ALL
    SELECT l.user_id,'PERSONAL',2 FROM public.team_leadership_assignments l
     WHERE e.kind='EMERGENCY' AND l.assignment_event_id=e.source_id AND l.team_id=e.team_id
    UNION ALL
    SELECT m.user_id,'TEAM',3 FROM public.team_memberships m
     WHERE m.organization_id=v_org AND m.team_id=e.team_id AND m.joined_at<=e.occurred_at
     AND (m.left_at IS NULL OR m.left_at>=e.occurred_at)
     AND (e.kind IN ('LEADER_ASSIGN','LEADER_END','EMERGENCY','TEAM_ACTIVE','TEAM_INACTIVE','TEAM_ARCHIVED')
      OR (e.kind IN ('MEMBER_JOIN','MEMBER_LEAVE') AND EXISTS (
       SELECT 1 FROM public.team_leadership_assignments l WHERE l.team_id=e.team_id AND l.user_id=m.user_id
       AND l.assigned_at<=e.occurred_at AND l.ended_at IS NULL
       AND public.epms_team_has_leadership_scope(m.user_id,e.team_id))))
   ) eligible WHERE public.epms_team_notification_scope(eligible.user_id,v_org,e.team_id,eligible.audience)
   ORDER BY eligible.user_id,eligible.precedence
  ) c;
   v_count:=v_count+1;
  EXCEPTION WHEN OTHERS THEN
   GET STACKED DIAGNOSTICS v_state=RETURNED_SQLSTATE;
   IF v_attempt<p_max_attempts THEN
    v_retry:=clock_timestamp()+make_interval(secs=>least(p_max_seconds::numeric,
     p_base_seconds::numeric*power(2::numeric,least(v_attempt-1,20)))::double precision);
   END IF;
  END;
  INSERT INTO public.team_notification_attempts(organization_id,team_id,operation_event_id,
   lifecycle_event_id,attempt_no,processor_kind,actor_user_id,outcome,failure_sqlstate,next_retry_at,automatic_limit)
  VALUES(v_org,e.team_id,CASE WHEN e.source_kind='OPERATION' THEN e.source_id END,
   CASE WHEN e.source_kind='LIFECYCLE' THEN e.source_id END,v_attempt,v_processor,p_actor,
   CASE WHEN v_state IS NULL THEN 'DELIVERED' ELSE 'FAILED' END,v_state,v_retry,p_max_attempts);
 END LOOP;
 RETURN v_count;
END $$;

-- Manual dispatch remains human-authorized and can explicitly retry exhausted work.
CREATE OR REPLACE FUNCTION public.epms_team_command_dispatch_notifications(p_actor uuid,p_limit integer)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,0);
 PERFORM pg_advisory_xact_lock(1832,38);
 v_org:=public.epms_team_notification_actor(p_actor,true);
 RETURN public.epms_team_dispatch_pending(v_org,p_actor,p_limit,5,30,900);
END $$;

-- Only the trusted restricted application datasource can call this fixed, actor-free command.
-- There is deliberately no HTTP route to it. No human identity or organization is accepted.
CREATE FUNCTION public.epms_team_system_dispatch(p_max_attempts integer,p_base_seconds integer,p_max_seconds integer)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 IF NOT pg_try_advisory_xact_lock(1832,38) THEN RETURN 0; END IF;
 SELECT o.organization_id INTO v_org FROM public.organizations o WHERE o.is_active;
 RETURN public.epms_team_dispatch_pending(v_org,NULL,1,p_max_attempts,p_base_seconds,p_max_seconds);
END $$;

CREATE FUNCTION public.epms_team_command_dispatch_failures(p_actor uuid,p_limit integer,p_offset integer)
RETURNS TABLE(source_id uuid,team_id uuid,source_kind text,attempt_count integer,failure_sqlstate text,
 attempted_at timestamptz,next_retry_at timestamptz,automatic_exhausted boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,p_offset);
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,true);
 RETURN QUERY SELECT coalesce(a.operation_event_id,a.lifecycle_event_id),a.team_id,
  CASE WHEN a.operation_event_id IS NOT NULL THEN 'OPERATION'::text ELSE 'LIFECYCLE'::text END,
  a.attempt_no,a.failure_sqlstate,a.attempted_at,a.next_retry_at,a.attempt_no>=a.automatic_limit
 FROM public.team_notification_attempts a
 WHERE a.organization_id=v_org AND a.outcome='FAILED'
 AND NOT EXISTS (SELECT 1 FROM public.team_notification_attempts newer
  WHERE newer.attempt_no>a.attempt_no AND (newer.operation_event_id=a.operation_event_id
   OR newer.lifecycle_event_id=a.lifecycle_event_id))
 AND NOT EXISTS (SELECT 1 FROM public.team_notification_dispatches d
  WHERE d.operation_event_id=a.operation_event_id OR d.lifecycle_event_id=a.lifecycle_event_id)
 ORDER BY a.attempted_at,a.attempt_id LIMIT p_limit OFFSET p_offset;
END $$;

-- Critical queue escalation is a separate operation, independent of dispatch success.
-- It records the observation of a due, unresolved condition; it never changes alert resolution.
CREATE FUNCTION public.epms_team_system_escalate(p_limit integer,p_ack_seconds integer,p_repeat_seconds integer)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog SET lock_timeout='2s' AS $$
DECLARE v_count integer; v_now timestamptz:=clock_timestamp();
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,0);
 IF p_ack_seconds IS NULL OR p_ack_seconds NOT BETWEEN 1 AND 86400
 OR p_repeat_seconds IS NULL OR p_repeat_seconds NOT BETWEEN 1 AND 86400 THEN
  RAISE EXCEPTION 'Invalid alert escalation configuration' USING ERRCODE='22023'; END IF;
 IF NOT pg_try_advisory_xact_lock(1832,39) THEN RETURN 0; END IF;
 WITH candidates AS (
  SELECT a.alert_id,coalesce(previous.escalation_no,0)+1 AS next_no,
   CASE WHEN ack.first_ack IS NULL THEN 'UNACKNOWLEDGED' ELSE 'UNRESOLVED' END AS stage,
   CASE WHEN previous.escalated_at IS NOT NULL THEN previous.escalated_at+make_interval(secs=>p_repeat_seconds)
    WHEN ack.first_ack IS NOT NULL THEN ack.first_ack+make_interval(secs=>p_repeat_seconds)
    ELSE a.created_at+make_interval(secs=>p_ack_seconds) END AS due_at
  FROM public.team_security_alerts a JOIN public.team_operation_events e ON e.event_id=a.event_id
  JOIN public.organizations o ON o.organization_id=e.organization_id AND o.is_active
  LEFT JOIN LATERAL (SELECT min(x.acknowledged_at) AS first_ack
   FROM public.team_security_alert_acknowledgements x WHERE x.alert_id=a.alert_id) ack ON true
  LEFT JOIN LATERAL (SELECT x.escalation_no,x.escalated_at FROM public.team_security_alert_escalations x
   WHERE x.alert_id=a.alert_id ORDER BY x.escalation_no DESC LIMIT 1) previous ON true
  WHERE NOT EXISTS (SELECT 1 FROM public.team_security_alert_resolutions r WHERE r.alert_id=a.alert_id)
 )
 INSERT INTO public.team_security_alert_escalations(alert_id,escalation_no,stage,due_at)
 SELECT c.alert_id,c.next_no,c.stage,c.due_at FROM candidates c WHERE c.due_at<=v_now
 ORDER BY c.due_at,c.alert_id LIMIT p_limit ON CONFLICT DO NOTHING;
 GET DIAGNOSTICS v_count=ROW_COUNT;
 RETURN v_count;
END $$;

DROP FUNCTION public.epms_team_command_security_alerts(uuid,boolean,integer,integer);
CREATE FUNCTION public.epms_team_command_security_alerts(p_actor uuid,p_include_resolved boolean,
 p_limit integer,p_offset integer)
RETURNS TABLE(alert_id uuid,team_id uuid,alert_code text,created_at timestamptz,
 acknowledged_at timestamptz,resolved_at timestamptz,acknowledged_by_any boolean,
 escalation_count integer,last_escalated_at timestamptz,last_escalation_stage text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,p_offset);
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,true);
 RETURN QUERY SELECT a.alert_id,e.team_id,a.alert_code,a.created_at,ack.acknowledged_at,r.resolved_at,
  EXISTS (SELECT 1 FROM public.team_security_alert_acknowledgements all_ack WHERE all_ack.alert_id=a.alert_id),
  coalesce(escalation.escalation_no,0),escalation.escalated_at,escalation.stage
 FROM public.team_security_alerts a JOIN public.team_operation_events e ON e.event_id=a.event_id
 LEFT JOIN public.team_security_alert_acknowledgements ack ON ack.alert_id=a.alert_id AND ack.actor_user_id=p_actor
 LEFT JOIN public.team_security_alert_resolutions r ON r.alert_id=a.alert_id
 LEFT JOIN LATERAL (SELECT x.escalation_no,x.escalated_at,x.stage
  FROM public.team_security_alert_escalations x WHERE x.alert_id=a.alert_id
  ORDER BY x.escalation_no DESC LIMIT 1) escalation ON true
 WHERE e.organization_id=v_org AND (p_include_resolved IS TRUE OR r.alert_id IS NULL)
 ORDER BY a.created_at,a.alert_id LIMIT p_limit OFFSET p_offset;
END $$;

REVOKE ALL ON FUNCTION public.epms_team_dispatch_pending(uuid,uuid,integer,integer,integer,integer),
 public.epms_team_system_dispatch(integer,integer,integer),public.epms_team_system_escalate(integer,integer,integer),
 public.epms_team_command_dispatch_failures(uuid,integer,integer),
 public.epms_team_command_security_alerts(uuid,boolean,integer,integer)
 FROM PUBLIC,epms_app,epms_rbac_runtime,epms_bootstrap_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_system_dispatch(integer,integer,integer),
 public.epms_team_system_escalate(integer,integer,integer),public.epms_team_command_dispatch_failures(uuid,integer,integer),
 public.epms_team_command_security_alerts(uuid,boolean,integer,integer) TO epms_rbac_runtime;
