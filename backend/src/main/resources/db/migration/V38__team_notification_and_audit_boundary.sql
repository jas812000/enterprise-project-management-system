-- B8: durable in-app notifications derived from committed Team events.
-- No notification trigger or external transport is placed on the security mutation path.
-- Dispatch runs in a separate transaction and uses a separate advisory lock.
CREATE TABLE public.team_notification_dispatches (
 dispatch_id uuid PRIMARY KEY DEFAULT uuidv7(),
 organization_id uuid NOT NULL,
 team_id uuid NOT NULL,
 operation_event_id uuid UNIQUE REFERENCES public.team_operation_events(event_id),
 lifecycle_event_id uuid UNIQUE REFERENCES public.team_lifecycle_events(event_id),
 processed_by_user_id uuid NOT NULL,
 processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(dispatch_id,organization_id),
 CHECK (num_nonnulls(operation_event_id,lifecycle_event_id)=1),
 FOREIGN KEY (organization_id,team_id) REFERENCES public.teams(organization_id,team_id),
 FOREIGN KEY (organization_id,processed_by_user_id) REFERENCES public.users(organization_id,user_id)
);
CREATE TABLE public.team_notifications (
 notification_id uuid PRIMARY KEY DEFAULT uuidv7(),
 dispatch_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 recipient_user_id uuid NOT NULL,
 audience text NOT NULL CHECK (audience IN ('ADMIN','PERSONAL','TEAM')),
 kind text NOT NULL CHECK (kind IN ('MEMBER_JOIN','MEMBER_LEAVE','LEADER_ASSIGN','LEADER_END',
  'LEADERSHIP_CHANGED','ALTERNATE_DESIGNATE','ALTERNATE_REVOKE','TEAM_ACTIVE','TEAM_INACTIVE',
  'TEAM_ARCHIVED','NO_ELIGIBLE_SUCCESSOR')),
 severity text NOT NULL CHECK (severity IN ('INFO','CRITICAL')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(dispatch_id,recipient_user_id),
 UNIQUE(notification_id,recipient_user_id),
 CHECK ((severity='CRITICAL')=(kind='NO_ELIGIBLE_SUCCESSOR')),
 CHECK (severity<>'CRITICAL' OR audience='ADMIN'),
 FOREIGN KEY (dispatch_id,organization_id)
  REFERENCES public.team_notification_dispatches(dispatch_id,organization_id),
 FOREIGN KEY (organization_id,recipient_user_id) REFERENCES public.users(organization_id,user_id)
);
CREATE INDEX ix_team_notifications_recipient ON public.team_notifications(recipient_user_id,created_at,notification_id);
CREATE TABLE public.team_notification_receipts (
 notification_id uuid NOT NULL,
 recipient_user_id uuid NOT NULL,
 action text NOT NULL CHECK (action IN ('READ','ACKNOWLEDGE')),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(notification_id,action),
 FOREIGN KEY (notification_id,recipient_user_id)
  REFERENCES public.team_notifications(notification_id,recipient_user_id)
);
CREATE TABLE public.team_security_alert_acknowledgements (
 alert_id uuid NOT NULL REFERENCES public.team_security_alerts(alert_id),
 actor_user_id uuid NOT NULL REFERENCES public.users(user_id),
 acknowledged_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(alert_id,actor_user_id)
);
CREATE TABLE public.team_security_alert_resolutions (
 alert_id uuid PRIMARY KEY REFERENCES public.team_security_alerts(alert_id),
 actor_user_id uuid NOT NULL REFERENCES public.users(user_id),
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
 resolved_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER trg_team_notification_dispatch_immutable BEFORE UPDATE OR DELETE
 ON public.team_notification_dispatches FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();
CREATE TRIGGER trg_team_notifications_immutable BEFORE UPDATE OR DELETE
 ON public.team_notifications FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();
CREATE TRIGGER trg_team_notification_receipts_immutable BEFORE UPDATE OR DELETE
 ON public.team_notification_receipts FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();
CREATE TRIGGER trg_team_alert_acknowledgements_immutable BEFORE UPDATE OR DELETE
 ON public.team_security_alert_acknowledgements FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();
CREATE TRIGGER trg_team_alert_resolutions_immutable BEFORE UPDATE OR DELETE
 ON public.team_security_alert_resolutions FOR EACH ROW EXECUTE FUNCTION public.epms_reject_security_history_mutation();
REVOKE ALL ON public.team_notification_dispatches,public.team_notifications,public.team_notification_receipts,
 public.team_security_alert_acknowledgements,public.team_security_alert_resolutions
 FROM PUBLIC,epms_app,epms_rbac_runtime,epms_bootstrap_runtime;

-- These helpers are owner-only. No client-supplied actor is accepted by Java or HTTP.
CREATE FUNCTION public.epms_team_notification_has_role(p_actor uuid,p_org uuid,p_roles text[])
RETURNS boolean LANGUAGE sql SET search_path=pg_catalog AS $$
 SELECT EXISTS (SELECT 1 FROM public.users u JOIN public.account_types a USING(account_type_id)
 JOIN public.security_user_roles x ON x.user_id=u.user_id JOIN public.security_roles r USING(role_id)
 WHERE u.user_id=p_actor AND u.organization_id=p_org AND u.is_active AND NOT u.is_archived AND a.code='HUMAN'
 AND r.is_active AND r.code=ANY(p_roles) AND x.revoked_at IS NULL AND x.valid_from<=clock_timestamp()
 AND (x.valid_until IS NULL OR x.valid_until>clock_timestamp()))
$$;
CREATE FUNCTION public.epms_team_notification_actor(p_actor uuid,p_admin boolean)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_roles text[];
BEGIN
 SELECT u.organization_id INTO v_org FROM public.users u WHERE u.user_id=p_actor;
 v_roles:=CASE WHEN p_admin THEN ARRAY['SYSTEM_ADMIN','ORG_ADMIN'] ELSE
  ARRAY['SYSTEM_ADMIN','ORG_ADMIN','PROJECT_MANAGER','TEAM_LEAD','MEMBER','VIEWER','AUDITOR'] END;
 IF v_org IS NULL OR NOT public.epms_team_notification_has_role(p_actor,v_org,v_roles) THEN
  RAISE EXCEPTION 'Team notification access denied' USING ERRCODE='42501'; END IF;
 RETURN v_org;
END $$;
CREATE FUNCTION public.epms_team_notification_scope(p_actor uuid,p_org uuid,p_team uuid,p_audience text)
RETURNS boolean LANGUAGE sql SET search_path=pg_catalog AS $$
 SELECT CASE p_audience
 WHEN 'ADMIN' THEN public.epms_team_notification_has_role(p_actor,p_org,ARRAY['SYSTEM_ADMIN','ORG_ADMIN'])
 WHEN 'PERSONAL' THEN public.epms_team_notification_has_role(p_actor,p_org,
  ARRAY['SYSTEM_ADMIN','ORG_ADMIN','PROJECT_MANAGER','TEAM_LEAD','MEMBER','VIEWER','AUDITOR'])
 WHEN 'TEAM' THEN public.epms_team_notification_has_role(p_actor,p_org,ARRAY['MEMBER','TEAM_LEAD'])
  AND EXISTS (SELECT 1 FROM public.team_memberships m JOIN public.teams t ON t.team_id=m.team_id
   WHERE m.user_id=p_actor AND m.organization_id=p_org AND m.team_id=p_team AND NOT t.is_archived
   AND m.left_at IS NULL AND m.joined_at<=clock_timestamp())
 ELSE false END
$$;
CREATE FUNCTION public.epms_team_notification_page(p_limit integer,p_offset integer)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 OR p_offset IS NULL OR p_offset<0 THEN
  RAISE EXCEPTION 'Invalid Team notification page' USING ERRCODE='22023'; END IF;
END $$;

-- The committed source event is the durable pending work item. The dispatch marker and all
-- of its notification rows commit atomically. A failed attempt leaves the source pending.
-- No external calls, shared RBAC advisory lock, or mutation of Team state in this function.
CREATE FUNCTION public.epms_team_command_dispatch_notifications(p_actor uuid,p_limit integer)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_dispatch uuid; v_count integer:=0; e record;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,0);
 PERFORM pg_advisory_xact_lock(1832,38);
 v_org:=public.epms_team_notification_actor(p_actor,true);
 FOR e IN
  SELECT src.* FROM (
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
  ) src ORDER BY src.occurred_at,src.source_id,src.source_kind LIMIT p_limit
 LOOP
  INSERT INTO public.team_notification_dispatches(organization_id,team_id,operation_event_id,
   lifecycle_event_id,processed_by_user_id)
  VALUES(v_org,e.team_id,CASE WHEN e.source_kind='OPERATION' THEN e.source_id END,
   CASE WHEN e.source_kind='LIFECYCLE' THEN e.source_id END,p_actor) RETURNING dispatch_id INTO v_dispatch;
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
 END LOOP;
 RETURN v_count;
END $$;

CREATE FUNCTION public.epms_team_command_notification_inbox(p_actor uuid,p_limit integer,p_offset integer)
RETURNS TABLE(notification_id uuid,team_id uuid,kind text,severity text,occurred_at timestamptz,
 read_at timestamptz,acknowledged_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,p_offset);
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,false);
 RETURN QUERY SELECT n.notification_id,d.team_id,n.kind,n.severity,coalesce(o.occurred_at,l.occurred_at),
  rd.occurred_at,ack.occurred_at
 FROM public.team_notifications n JOIN public.team_notification_dispatches d USING(dispatch_id)
 LEFT JOIN public.team_operation_events o ON o.event_id=d.operation_event_id
 LEFT JOIN public.team_lifecycle_events l ON l.event_id=d.lifecycle_event_id
 LEFT JOIN public.team_notification_receipts rd ON rd.notification_id=n.notification_id AND rd.action='READ'
 LEFT JOIN public.team_notification_receipts ack ON ack.notification_id=n.notification_id AND ack.action='ACKNOWLEDGE'
 WHERE n.recipient_user_id=p_actor AND n.organization_id=v_org
 AND public.epms_team_notification_scope(p_actor,v_org,d.team_id,n.audience)
 ORDER BY n.created_at DESC,n.notification_id DESC LIMIT p_limit OFFSET p_offset;
END $$;
CREATE FUNCTION public.epms_team_command_notification_receipt(p_actor uuid,p_notification uuid,p_action text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_alert uuid; v_severity text;
BEGIN
 IF p_action IS NULL OR p_action NOT IN ('READ','ACKNOWLEDGE') THEN
  RAISE EXCEPTION 'Invalid notification receipt' USING ERRCODE='22023'; END IF;
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,false);
 SELECT a.alert_id,n.severity INTO v_alert,v_severity FROM public.team_notifications n
 JOIN public.team_notification_dispatches d USING(dispatch_id)
 LEFT JOIN public.team_security_alerts a ON a.event_id=d.operation_event_id
 WHERE n.notification_id=p_notification AND n.recipient_user_id=p_actor AND n.organization_id=v_org
 AND public.epms_team_notification_scope(p_actor,v_org,d.team_id,n.audience);
 IF NOT FOUND THEN RAISE EXCEPTION 'Notification unavailable' USING ERRCODE='42501'; END IF;
 INSERT INTO public.team_notification_receipts(notification_id,recipient_user_id,action)
 VALUES(p_notification,p_actor,'READ') ON CONFLICT DO NOTHING;
 IF p_action='ACKNOWLEDGE' THEN
  INSERT INTO public.team_notification_receipts(notification_id,recipient_user_id,action)
  VALUES(p_notification,p_actor,'ACKNOWLEDGE') ON CONFLICT DO NOTHING;
  IF v_severity='CRITICAL' AND v_alert IS NOT NULL THEN
   INSERT INTO public.team_security_alert_acknowledgements(alert_id,actor_user_id)
   VALUES(v_alert,p_actor) ON CONFLICT DO NOTHING;
  END IF;
 END IF;
END $$;

-- The red-alert queue is independent of dispatch progress, recipient changes and delivery.
-- An acknowledgement never resolves an alert. New current admins can see outstanding alerts.
CREATE FUNCTION public.epms_team_command_security_alerts(p_actor uuid,p_include_resolved boolean,
 p_limit integer,p_offset integer)
RETURNS TABLE(alert_id uuid,team_id uuid,alert_code text,created_at timestamptz,
 acknowledged_at timestamptz,resolved_at timestamptz,acknowledged_by_any boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,p_offset);
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,true);
 RETURN QUERY SELECT a.alert_id,e.team_id,a.alert_code,a.created_at,ack.acknowledged_at,r.resolved_at,
  EXISTS (SELECT 1 FROM public.team_security_alert_acknowledgements all_ack WHERE all_ack.alert_id=a.alert_id)
 FROM public.team_security_alerts a JOIN public.team_operation_events e ON e.event_id=a.event_id
 LEFT JOIN public.team_security_alert_acknowledgements ack ON ack.alert_id=a.alert_id AND ack.actor_user_id=p_actor
 LEFT JOIN public.team_security_alert_resolutions r ON r.alert_id=a.alert_id
 WHERE e.organization_id=v_org AND (p_include_resolved IS TRUE OR r.alert_id IS NULL)
 ORDER BY a.created_at,a.alert_id LIMIT p_limit OFFSET p_offset;
END $$;
CREATE FUNCTION public.epms_team_notification_alert_team(p_org uuid,p_alert uuid)
RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_team uuid;
BEGIN
 SELECT e.team_id INTO v_team FROM public.team_security_alerts a
 JOIN public.team_operation_events e ON e.event_id=a.event_id
 WHERE a.alert_id=p_alert AND e.organization_id=p_org;
 IF v_team IS NULL THEN RAISE EXCEPTION 'Alert unavailable' USING ERRCODE='42501'; END IF;
 RETURN v_team;
END $$;
CREATE FUNCTION public.epms_team_command_acknowledge_alert(p_actor uuid,p_alert uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,true);
 PERFORM public.epms_team_notification_alert_team(v_org,p_alert);
 INSERT INTO public.team_security_alert_acknowledgements(alert_id,actor_user_id)
 VALUES(p_alert,p_actor) ON CONFLICT DO NOTHING;
END $$;
CREATE FUNCTION public.epms_team_command_resolve_alert(p_actor uuid,p_alert uuid,p_reason text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_team uuid;
BEGIN
 IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'Resolution requires a reason' USING ERRCODE='22023'; END IF;
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,true);
 v_team:=public.epms_team_notification_alert_team(v_org,p_alert);
 IF EXISTS (SELECT 1 FROM public.team_security_alert_resolutions r WHERE r.alert_id=p_alert) THEN RETURN; END IF;
 -- Conservative resolution: never clear a no-successor alert while the Team is still stranded.
 IF NOT EXISTS (SELECT 1 FROM public.teams t WHERE t.team_id=v_team AND t.is_archived)
 AND NOT public.epms_team_has_eligible_leader(v_team) THEN
  RAISE EXCEPTION 'No-successor condition remains unresolved' USING ERRCODE='23514'; END IF;
 INSERT INTO public.team_security_alert_acknowledgements(alert_id,actor_user_id)
 VALUES(p_alert,p_actor) ON CONFLICT DO NOTHING;
 INSERT INTO public.team_security_alert_resolutions(alert_id,actor_user_id,reason)
 VALUES(p_alert,p_actor,btrim(p_reason));
END $$;

CREATE FUNCTION public.epms_team_command_alternate_readiness(p_actor uuid,p_team uuid)
RETURNS TABLE(priority integer,readiness text) LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,true);
 IF NOT EXISTS (SELECT 1 FROM public.teams t WHERE t.team_id=p_team AND t.organization_id=v_org
  AND NOT t.is_archived) THEN RAISE EXCEPTION 'Team unavailable' USING ERRCODE='42501'; END IF;
 RETURN QUERY SELECT r.priority,r.readiness FROM public.epms_team_alternate_readiness(p_team) r;
END $$;
-- Metadata-only administrative history. The original confidential reasons, snapshots,
-- identity details and account lifecycle links remain protected in immutable source tables.
CREATE FUNCTION public.epms_team_command_operation_history(p_actor uuid,p_team uuid,p_limit integer,p_offset integer)
RETURNS TABLE(event_id uuid,source_kind text,event_kind text,occurred_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 PERFORM public.epms_team_notification_page(p_limit,p_offset);
 PERFORM pg_advisory_xact_lock(1832,12);
 v_org:=public.epms_team_notification_actor(p_actor,true);
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

REVOKE ALL ON FUNCTION public.epms_team_notification_has_role(uuid,uuid,text[]),
 public.epms_team_notification_actor(uuid,boolean),public.epms_team_notification_scope(uuid,uuid,uuid,text),
 public.epms_team_notification_page(integer,integer),public.epms_team_notification_alert_team(uuid,uuid)
 FROM PUBLIC,epms_app,epms_rbac_runtime,epms_bootstrap_runtime;
REVOKE ALL ON FUNCTION public.epms_team_command_dispatch_notifications(uuid,integer),
 public.epms_team_command_notification_inbox(uuid,integer,integer),
 public.epms_team_command_notification_receipt(uuid,uuid,text),
 public.epms_team_command_security_alerts(uuid,boolean,integer,integer),
 public.epms_team_command_acknowledge_alert(uuid,uuid),public.epms_team_command_resolve_alert(uuid,uuid,text),
 public.epms_team_command_alternate_readiness(uuid,uuid),public.epms_team_command_operation_history(uuid,uuid,integer,integer)
 FROM PUBLIC,epms_app,epms_bootstrap_runtime,epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_dispatch_notifications(uuid,integer),
 public.epms_team_command_notification_inbox(uuid,integer,integer),
 public.epms_team_command_notification_receipt(uuid,uuid,text),
 public.epms_team_command_security_alerts(uuid,boolean,integer,integer),
 public.epms_team_command_acknowledge_alert(uuid,uuid),public.epms_team_command_resolve_alert(uuid,uuid,text),
 public.epms_team_command_alternate_readiness(uuid,uuid),public.epms_team_command_operation_history(uuid,uuid,integer,integer)
 TO epms_rbac_runtime;
