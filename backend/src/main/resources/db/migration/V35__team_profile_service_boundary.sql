-- B6.2: deterministic name warnings and protected administrative profile reads.
-- No extension, new grantable permission, or changes to historical migrations.
CREATE FUNCTION public.epms_team_name_key(p_name text)
RETURNS text LANGUAGE sql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
 SELECT regexp_replace(lower(normalize(p_name,NFKC)), '[^[:alnum:]]', '', 'g')
$$;

-- Bounded one-edit comparison (including adjacent transposition).
CREATE FUNCTION public.epms_team_one_edit(p_left text,p_right text)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
DECLARE a text:=p_left; b text:=p_right; i integer; n integer; m integer; tmp text;
BEGIN
 IF length(a)>length(b) THEN tmp:=a; a:=b; b:=tmp; END IF;
 n:=length(a); m:=length(b);
 IF n<4 OR m-n>1 THEN RETURN false; END IF;
 FOR i IN 1..n LOOP
  IF substr(a,i,1)<>substr(b,i,1) THEN
   IF m>n THEN RETURN substr(a,i)=substr(b,i+1); END IF;
   IF substr(a,i+1)=substr(b,i+1) THEN RETURN true; END IF;
   RETURN i<n AND substr(a,i,1)=substr(b,i+1,1)
    AND substr(a,i+1,1)=substr(b,i,1) AND substr(a,i+2)=substr(b,i+2);
  END IF;
 END LOOP;
 RETURN true;
END $$;

-- English-oriented consonant grouping; deliberately conservative on short names.
CREATE FUNCTION public.epms_team_sound_key(p_name text)
RETURNS text LANGUAGE sql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
 SELECT CASE WHEN p_name ~ '^[a-z]{5,}$' THEN
  regexp_replace(regexp_replace(translate(p_name,
    'bfpvcgjkqsxzdtlmnraeiouyhw','11112222222233455600000000'), '0','','g'),
    '([1-6])\1+', '\1','g') ELSE NULL END
$$;

CREATE FUNCTION public.epms_team_names_similar(p_left text,p_right text)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
DECLARE a text:=public.epms_team_name_key(p_left); b text:=public.epms_team_name_key(p_right);
sa text; sb text;
BEGIN
 IF a=b OR public.epms_team_one_edit(a,b) THEN RETURN true; END IF;
 -- Common digit/letter look-alikes; not an exhaustive Unicode confusable map.
 IF translate(a,'013457','oieast')=translate(b,'013457','oieast') THEN RETURN true; END IF;
 sa:=public.epms_team_sound_key(a); sb:=public.epms_team_sound_key(b);
 RETURN coalesce(length(sa)>=3 AND sa=sb,false);
END $$;

CREATE OR REPLACE FUNCTION public.epms_team_profile_conflicts(p_org uuid,p_team uuid,p_name text)
RETURNS uuid[] LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE v_matches uuid[];
BEGIN
 IF p_name IS NULL OR length(btrim(p_name)) NOT BETWEEN 1 AND 200
  OR regexp_replace(lower(p_name),'[^[:alnum:]]','','g')='' THEN
  RAISE EXCEPTION 'Invalid Team name' USING ERRCODE='22023';
 END IF;
 IF EXISTS (SELECT 1 FROM public.teams t WHERE t.organization_id=p_org
  AND t.team_id IS DISTINCT FROM p_team
  AND lower(regexp_replace(btrim(t.name),'[[:space:]]+',' ','g'))=
      lower(regexp_replace(btrim(p_name),'[[:space:]]+',' ','g'))) THEN
  RAISE EXCEPTION 'Duplicate Team name' USING ERRCODE='23505';
 END IF;
 SELECT coalesce(array_agg(t.team_id ORDER BY t.team_id),ARRAY[]::uuid[]) INTO v_matches
 FROM public.teams t WHERE t.organization_id=p_org AND t.team_id IS DISTINCT FROM p_team
 AND public.epms_team_names_similar(t.name,p_name);
 RETURN v_matches;
END $$;

-- Administrative reads intentionally use the same current authority as profile writes.
-- Scope is derived in the database, not passed by the caller.
CREATE FUNCTION public.epms_team_command_profiles(p_actor uuid,p_team uuid,p_limit integer,p_offset integer)
RETURNS TABLE(team_id uuid,name text,description text,lifecycle_status text,version bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid;
BEGIN
 v_org:=public.epms_team_require_profile_admin(p_actor,'Read Team profiles');
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 OR p_offset IS NULL OR p_offset<0 THEN
  RAISE EXCEPTION 'Invalid Team page' USING ERRCODE='22023'; END IF;
 RETURN QUERY SELECT t.team_id,t.name::text,t.description::text,t.lifecycle_status::text,t.version::bigint
 FROM public.teams t WHERE t.organization_id=v_org AND (p_team IS NULL OR t.team_id=p_team)
 ORDER BY t.name,t.team_id LIMIT p_limit OFFSET p_offset;
END $$;

CREATE FUNCTION public.epms_team_command_profile_conflicts(p_actor uuid,p_team uuid,p_name text)
RETURNS TABLE(team_id uuid,name text) LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_org uuid; v_matches uuid[];
BEGIN
 v_org:=public.epms_team_require_profile_admin(p_actor,'Review Team name conflicts');
 IF p_team IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.teams t
  WHERE t.team_id=p_team AND t.organization_id=v_org AND NOT t.is_archived) THEN
  RAISE EXCEPTION 'Team unavailable' USING ERRCODE='42501'; END IF;
 v_matches:=public.epms_team_profile_conflicts(v_org,p_team,p_name);
 RETURN QUERY SELECT t.team_id,t.name::text FROM public.teams t
 WHERE t.organization_id=v_org AND t.team_id=ANY(v_matches) ORDER BY t.name,t.team_id;
END $$;

REVOKE ALL ON FUNCTION public.epms_team_name_key(text),public.epms_team_one_edit(text,text),
 public.epms_team_sound_key(text),public.epms_team_names_similar(text,text)
 FROM PUBLIC,epms_app,epms_rbac_runtime;
REVOKE ALL ON FUNCTION public.epms_team_command_profiles(uuid,uuid,integer,integer),
 public.epms_team_command_profile_conflicts(uuid,uuid,text) FROM PUBLIC,epms_app,epms_rbac_runtime;
GRANT EXECUTE ON FUNCTION public.epms_team_command_profiles(uuid,uuid,integer,integer),
 public.epms_team_command_profile_conflicts(uuid,uuid,text) TO epms_rbac_runtime;
