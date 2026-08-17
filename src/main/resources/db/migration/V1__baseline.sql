-- =============================================================
-- V1 - Baseline schema + seed data
-- =============================================================
-- Squashed from the original V1-V42 migration history on 2026-08-09
-- as part of the auth/nav consolidation (see AUTH_AND_NAV_PLAN.md).
-- Generated from a validated live schema (pg_dump --schema-only +
-- --data-only), then diffed table-by-table (row counts, table list)
-- against the source database to confirm an exact match before
-- replacing the prior history. The pre-squash database was demo/
-- seed data only (speedwheels, tastybites) -- no production data
-- existed at the time of this squash.
--
-- Rule going forward: every new page/route MUST have a row in
-- feature_catalog (and, if it has a backend API, a row in
-- feature_api_path) before it is wired into any nav. See
-- CODEBASE_CONTEXT.md.
-- =============================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 16.13 (Debian 16.13-1.pgdg12+1)
-- Dumped by pg_dump version 16.13 (Debian 16.13-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: apply_actual_cost_to_budget_line(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.apply_actual_cost_to_budget_line() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM refresh_budget_line_actual(NEW.budget_line_id);
    RETURN NEW;
END;
$$;


--
-- Name: notify_approval_transition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.notify_approval_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status='PENDING' AND NEW.current_step IS DISTINCT FROM OLD.current_step THEN
        INSERT INTO workflow_notification_outbox(
            tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,
            target_user_id,target_organization_id,target_party_role,payload)
        SELECT NEW.tenant_id,d.project_id,NEW.document_id,NEW.id,s.id,'APPROVAL_ASSIGNED',
               CASE WHEN s.assignment_type='USER' THEN u.id END,
               CASE WHEN s.assignment_type='ORGANIZATION' THEN s.assignment_organization_id END,
               CASE WHEN s.assignment_type='PARTY_ROLE' THEN s.assignment_party_role END,
               jsonb_build_object('documentCode',d.document_code,'title',d.title,'stepName',s.step_name,
                                  'authority',s.authority_type,'dueAt',s.due_at,'parallelGroup',s.parallel_group)
          FROM documents d
          JOIN document_approval_steps current_s ON current_s.approval_id=NEW.id AND current_s.step_index=NEW.current_step
          JOIN document_approval_steps s ON s.approval_id=NEW.id AND s.decision IS NULL
          LEFT JOIN tenant_users u ON u.tenant_id=NEW.tenant_id AND u.active=true AND lower(u.email)=lower(s.reviewer_email)
         WHERE d.id=NEW.document_id
           AND (s.step_index=NEW.current_step OR (current_s.parallel_group IS NOT NULL AND s.parallel_group=current_s.parallel_group))
           AND (s.assignment_type<>'USER' OR u.id IS NOT NULL)
        ON CONFLICT DO NOTHING;
    END IF;

    IF NEW.status IN ('APPROVED','REJECTED') AND NEW.status IS DISTINCT FROM OLD.status AND NEW.initiated_by IS NOT NULL THEN
        INSERT INTO workflow_notification_outbox(
            tenant_id,project_id,document_id,approval_id,event_type,target_user_id,payload)
        SELECT NEW.tenant_id,d.project_id,NEW.document_id,NEW.id,'APPROVAL_RESULT',NEW.initiated_by,
               jsonb_build_object('documentCode',d.document_code,'title',d.title,'status',NEW.status,
                                  'completedAt',NEW.completed_at,'reviewOutcome',d.review_outcome)
          FROM documents d WHERE d.id=NEW.document_id
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END; $$;


--
-- Name: notify_new_approval_step(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.notify_new_approval_step() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    a document_approvals%ROWTYPE;
    current_group VARCHAR(80);
    target_user UUID;
BEGIN
    SELECT * INTO a FROM document_approvals WHERE id=NEW.approval_id;
    IF a.id IS NULL OR a.status <> 'PENDING' THEN RETURN NEW; END IF;
    SELECT parallel_group INTO current_group FROM document_approval_steps
      WHERE approval_id=a.id AND step_index=a.current_step LIMIT 1;
    IF NEW.step_index <> a.current_step AND (current_group IS NULL OR NEW.parallel_group IS DISTINCT FROM current_group) THEN
        RETURN NEW;
    END IF;
    IF NEW.assignment_type='USER' THEN
        SELECT id INTO target_user FROM tenant_users WHERE tenant_id=a.tenant_id AND active=true AND lower(email)=lower(NEW.reviewer_email) LIMIT 1;
        IF target_user IS NULL THEN RETURN NEW; END IF;
    END IF;
    INSERT INTO workflow_notification_outbox(
        tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,
        target_user_id,target_organization_id,target_party_role,payload)
    SELECT a.tenant_id,d.project_id,a.document_id,a.id,NEW.id,'APPROVAL_ASSIGNED',
           CASE WHEN NEW.assignment_type='USER' THEN target_user END,
           CASE WHEN NEW.assignment_type='ORGANIZATION' THEN NEW.assignment_organization_id END,
           CASE WHEN NEW.assignment_type='PARTY_ROLE' THEN NEW.assignment_party_role END,
           jsonb_build_object('documentCode',d.document_code,'title',d.title,'stepName',NEW.step_name,
                              'authority',NEW.authority_type,'dueAt',NEW.due_at,'parallelGroup',NEW.parallel_group)
      FROM documents d WHERE d.id=a.document_id
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END; $$;


--
-- Name: notify_transmittal_acknowledgement(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.notify_transmittal_acknowledgement() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE sender_org UUID; project UUID; no_text VARCHAR(100); subject_text VARCHAR(300); tenant UUID;
BEGIN
    IF OLD.acknowledged_at IS NULL AND NEW.acknowledged_at IS NOT NULL THEN
        SELECT sender_organization_id,project_id,transmittal_no,subject,tenant_id
          INTO sender_org,project,no_text,subject_text,tenant
          FROM document_transmittals WHERE id=NEW.transmittal_id;
        INSERT INTO workflow_notification_outbox(
            tenant_id,project_id,transmittal_id,event_type,target_organization_id,payload)
        VALUES(tenant,project,NEW.transmittal_id,'TRANSMITTAL_ACKNOWLEDGED',sender_org,
               jsonb_build_object('transmittalNo',no_text,'subject',subject_text,
                                  'recipientOrganizationId',NEW.recipient_organization_id,'acknowledgedAt',NEW.acknowledged_at));
    END IF;
    RETURN NEW;
END; $$;


--
-- Name: notify_transmittal_transition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.notify_transmittal_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status='ISSUED' AND OLD.status='DRAFT' THEN
        INSERT INTO workflow_notification_outbox(
            tenant_id,project_id,transmittal_id,event_type,target_organization_id,payload)
        SELECT NEW.tenant_id,NEW.project_id,NEW.id,'TRANSMITTAL_ISSUED',r.recipient_organization_id,
               jsonb_build_object('transmittalNo',NEW.transmittal_no,'subject',NEW.subject,'purpose',NEW.purpose,
                                  'senderOrganizationId',NEW.sender_organization_id,'issuedAt',NEW.issued_at)
          FROM document_transmittal_recipients r WHERE r.transmittal_id=NEW.id
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END; $$;


--
-- Name: refresh_budget_line_actual(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.refresh_budget_line_actual(p_budget_line uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF p_budget_line IS NULL THEN RETURN; END IF;
    UPDATE budget_lines b
       SET actual_cost = b.baseline_actual_cost
         + COALESCE((SELECT SUM(a.amount) FROM actual_cost_entries a WHERE a.budget_line_id = p_budget_line),0)
         + COALESCE((SELECT SUM(m.amount) FROM material_receipts m WHERE m.budget_line_id = p_budget_line AND m.status='ACCEPTED'),0),
           updated_at = NOW()
     WHERE b.id = p_budget_line;
END;
$$;


--
-- Name: refresh_budget_line_commitment(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.refresh_budget_line_commitment(p_budget_line uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF p_budget_line IS NULL THEN RETURN; END IF;
    UPDATE budget_lines b
       SET committed_cost = b.baseline_committed_cost + COALESCE((
           SELECT SUM(c.original_amount + c.approved_changes)
             FROM project_commitments c
            WHERE c.budget_line_id = p_budget_line AND c.status = 'ACTIVE'
       ),0), updated_at = NOW()
     WHERE b.id = p_budget_line;
END;
$$;


--
-- Name: refresh_budget_line_variations(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.refresh_budget_line_variations(p_budget_line uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF p_budget_line IS NULL THEN RETURN; END IF;
    UPDATE budget_lines b
       SET approved_changes = b.baseline_approved_changes + COALESCE((
           SELECT SUM(v.approved_amount)
             FROM project_variations v
            WHERE v.budget_line_id = p_budget_line AND v.status='APPROVED'
       ),0), updated_at = NOW()
     WHERE b.id = p_budget_line;
END;
$$;


--
-- Name: start_initial_parallel_step_sla(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.start_initial_parallel_step_sla() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    current_idx INTEGER;
    current_group VARCHAR(80);
BEGIN
    IF NEW.sla_hours IS NULL OR NEW.due_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    SELECT a.current_step
      INTO current_idx
      FROM document_approvals a
     WHERE a.id = NEW.approval_id;

    IF NEW.step_index = current_idx THEN
        NEW.due_at := NOW() + make_interval(hours => NEW.sla_hours);
        RETURN NEW;
    END IF;

    SELECT s.parallel_group
      INTO current_group
      FROM document_approval_steps s
     WHERE s.approval_id = NEW.approval_id
       AND s.step_index = current_idx;

    IF current_group IS NOT NULL AND NEW.parallel_group = current_group THEN
        NEW.due_at := NOW() + make_interval(hours => NEW.sla_hours);
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: trg_refresh_commitment_budget(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_refresh_commitment_budget() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    target_line UUID;
BEGIN
    target_line := CASE WHEN TG_OP = 'DELETE' THEN OLD.budget_line_id ELSE NEW.budget_line_id END;
    PERFORM refresh_budget_line_commitment(target_line);
    IF TG_OP = 'UPDATE' AND OLD.budget_line_id IS DISTINCT FROM NEW.budget_line_id THEN
        PERFORM refresh_budget_line_commitment(OLD.budget_line_id);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: trg_refresh_material_actual(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_refresh_material_actual() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    target_line UUID;
BEGIN
    target_line := CASE WHEN TG_OP = 'DELETE' THEN OLD.budget_line_id ELSE NEW.budget_line_id END;
    PERFORM refresh_budget_line_actual(target_line);
    IF TG_OP = 'UPDATE' AND OLD.budget_line_id IS DISTINCT FROM NEW.budget_line_id THEN
        PERFORM refresh_budget_line_actual(OLD.budget_line_id);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: trg_refresh_variation_budget(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_refresh_variation_budget() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    target_line UUID;
BEGIN
    target_line := CASE WHEN TG_OP = 'DELETE' THEN OLD.budget_line_id ELSE NEW.budget_line_id END;
    PERFORM refresh_budget_line_variations(target_line);
    IF TG_OP = 'UPDATE' AND OLD.budget_line_id IS DISTINCT FROM NEW.budget_line_id THEN
        PERFORM refresh_budget_line_variations(OLD.budget_line_id);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: actual_cost_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.actual_cost_entries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    budget_line_id uuid,
    resource_id uuid,
    source_type character varying(40) NOT NULL,
    source_id uuid,
    cost_date date NOT NULL,
    quantity numeric(18,3) DEFAULT 0 NOT NULL,
    unit_rate numeric(18,2) DEFAULT 0 NOT NULL,
    amount numeric(18,2) NOT NULL,
    currency character varying(10) DEFAULT 'AED'::character varying NOT NULL,
    description character varying(500),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_actual_cost_non_negative CHECK ((amount >= (0)::numeric))
);


--
-- Name: analytics_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analytics_snapshots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    publish_job_id uuid,
    platform_code character varying(50) NOT NULL,
    views bigint DEFAULT 0 NOT NULL,
    likes bigint DEFAULT 0 NOT NULL,
    comments bigint DEFAULT 0 NOT NULL,
    shares bigint DEFAULT 0 NOT NULL,
    clicks bigint DEFAULT 0 NOT NULL,
    leads bigint DEFAULT 0 NOT NULL,
    captured_at timestamp without time zone DEFAULT now() NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: approval_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_tasks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    content_idea_id uuid NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    reviewer_note text,
    reviewed_by character varying(200),
    reviewed_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: background_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.background_jobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    job_type character varying(100) NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    priority integer DEFAULT 5 NOT NULL,
    run_after timestamp without time zone DEFAULT now() NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    max_retries integer DEFAULT 3 NOT NULL,
    locked_by character varying(200),
    locked_at timestamp without time zone,
    error_message text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: budget_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.budget_lines (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    budget_version_id uuid NOT NULL,
    parent_line_id uuid,
    cost_code character varying(80) NOT NULL,
    name character varying(300) NOT NULL,
    original_budget numeric(18,2) DEFAULT 0 NOT NULL,
    approved_changes numeric(18,2) DEFAULT 0 NOT NULL,
    committed_cost numeric(18,2) DEFAULT 0 NOT NULL,
    actual_cost numeric(18,2) DEFAULT 0 NOT NULL,
    estimate_to_complete numeric(18,2) DEFAULT 0 NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    baseline_committed_cost numeric(18,2) DEFAULT 0 NOT NULL,
    baseline_actual_cost numeric(18,2) DEFAULT 0 NOT NULL,
    baseline_approved_changes numeric(18,2) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_budget_line_values CHECK (((original_budget >= (0)::numeric) AND (committed_cost >= (0)::numeric) AND (actual_cost >= (0)::numeric) AND (estimate_to_complete >= (0)::numeric)))
);


--
-- Name: budget_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.budget_versions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    version_no integer NOT NULL,
    label character varying(150) NOT NULL,
    status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL,
    effective_date date,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    organization_id uuid
);


--
-- Name: campaigns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaigns (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    goal character varying(50) NOT NULL,
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    platform_codes text[] DEFAULT '{}'::text[] NOT NULL,
    brief text,
    start_date date,
    end_date date,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: canned_responses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.canned_responses (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    shortcut character varying(100) NOT NULL,
    title character varying(200) NOT NULL,
    body text NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: consultant_kpi_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consultant_kpi_snapshots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    snapshot_date date NOT NULL,
    document_sla_health numeric(6,2) DEFAULT 100 NOT NULL,
    forecast_alignment numeric(6,2) DEFAULT 100 NOT NULL,
    overall_control_health numeric(6,2) DEFAULT 100 NOT NULL,
    overdue_documents integer DEFAULT 0 NOT NULL,
    due_documents integer DEFAULT 0 NOT NULL,
    latest_party_forecast numeric(18,2),
    control_forecast numeric(18,2),
    forecast_gap numeric(18,2),
    methodology_version character varying(30) DEFAULT 'KPI_V1'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_consultant_kpi_scores CHECK ((((document_sla_health >= (0)::numeric) AND (document_sla_health <= (100)::numeric)) AND ((forecast_alignment >= (0)::numeric) AND (forecast_alignment <= (100)::numeric)) AND ((overall_control_health >= (0)::numeric) AND (overall_control_health <= (100)::numeric))))
);


--
-- Name: contacts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contacts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    wa_id character varying(50) NOT NULL,
    phone_number character varying(50) NOT NULL,
    display_name character varying(200),
    last_seen_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    language character varying(20) DEFAULT 'en'::character varying NOT NULL
);


--
-- Name: content_ideas; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.content_ideas (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    campaign_id uuid,
    platform_code character varying(50) NOT NULL,
    content_type character varying(50) NOT NULL,
    status character varying(50) DEFAULT 'GENERATED'::character varying NOT NULL,
    topic text NOT NULL,
    generated_at timestamp without time zone DEFAULT now() NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: content_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.content_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    title character varying(500),
    niche character varying(200),
    platform character varying(100),
    content_type character varying(100),
    source_trend_id uuid,
    caption text,
    hashtags text[] DEFAULT '{}'::text[] NOT NULL,
    script_text text,
    generation_instructions jsonb,
    template_id uuid,
    final_asset_id uuid,
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    scheduled_at timestamp without time zone,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: content_variants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.content_variants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    content_idea_id uuid NOT NULL,
    body text NOT NULL,
    hashtags text[] DEFAULT '{}'::text[] NOT NULL,
    call_to_action character varying(500),
    version integer DEFAULT 1 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: control_forecast_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.control_forecast_snapshots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    snapshot_date date NOT NULL,
    current_budget numeric(18,2) DEFAULT 0 NOT NULL,
    actual_cost numeric(18,2) DEFAULT 0 NOT NULL,
    committed_cost numeric(18,2) DEFAULT 0 NOT NULL,
    estimate_to_complete numeric(18,2) DEFAULT 0 NOT NULL,
    pending_variation_exposure numeric(18,2) DEFAULT 0 NOT NULL,
    base_eac numeric(18,2) DEFAULT 0 NOT NULL,
    exposure_eac numeric(18,2) DEFAULT 0 NOT NULL,
    forecast_variance numeric(18,2) DEFAULT 0 NOT NULL,
    physical_progress_percent numeric(6,2),
    schedule_progress_percent numeric(6,2),
    cost_consumption_percent numeric(6,2),
    source_version character varying(30) DEFAULT 'CONTROL_V1'::character varying NOT NULL,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: conversation_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversation_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    event_type character varying(100) NOT NULL,
    from_agent_id uuid,
    to_agent_id uuid,
    notes text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    contact_id uuid NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    assigned_agent_id uuid,
    bot_enabled boolean DEFAULT true NOT NULL,
    priority character varying(30) DEFAULT 'NORMAL'::character varying NOT NULL,
    last_message_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    unread_count integer DEFAULT 0 NOT NULL,
    last_message_preview text
);


--
-- Name: document_access_grants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_access_grants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    user_id uuid,
    role_code character varying(100),
    permission_code character varying(100) NOT NULL,
    granted_by uuid,
    expires_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    organization_id uuid,
    CONSTRAINT ck_document_grant_principal CHECK (((user_id IS NOT NULL) OR (role_code IS NOT NULL) OR (organization_id IS NOT NULL)))
);


--
-- Name: document_approval_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_approval_steps (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    approval_id uuid NOT NULL,
    step_index integer NOT NULL,
    step_name character varying(200),
    reviewer_id uuid,
    reviewer_email character varying(320),
    decision character varying(50),
    comments text,
    decided_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    authority_type character varying(40) DEFAULT 'TECHNICAL_REVIEW'::character varying NOT NULL,
    assignment_type character varying(30) DEFAULT 'USER'::character varying NOT NULL,
    assignment_organization_id uuid,
    assignment_party_role character varying(40),
    required boolean DEFAULT true NOT NULL,
    parallel_group character varying(80),
    sla_hours integer,
    due_at timestamp without time zone,
    escalated_at timestamp without time zone,
    CONSTRAINT ck_approval_assignment CHECK (((assignment_type)::text = ANY ((ARRAY['USER'::character varying, 'ORGANIZATION'::character varying, 'PARTY_ROLE'::character varying])::text[]))),
    CONSTRAINT ck_approval_authority CHECK (((authority_type)::text = ANY ((ARRAY['INTERNAL_REVIEW'::character varying, 'TECHNICAL_REVIEW'::character varying, 'CLIENT_APPROVAL'::character varying, 'COMMERCIAL_CERTIFICATION'::character varying])::text[]))),
    CONSTRAINT ck_approval_sla CHECK (((sla_hours IS NULL) OR (sla_hours > 0)))
);


--
-- Name: document_approvals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_approvals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    document_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    workflow_id uuid,
    current_step integer DEFAULT 0 NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    started_at timestamp without time zone DEFAULT now() NOT NULL,
    completed_at timestamp without time zone,
    initiated_by uuid
);


--
-- Name: document_audit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_audit_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    actor_user_id uuid,
    event_type character varying(100) NOT NULL,
    event_payload jsonb,
    event_hash character varying(128),
    previous_event_hash character varying(128),
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_comments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_comments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    document_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    author_id uuid,
    body text NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_control_workflows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_control_workflows (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    doc_type character varying(100) NOT NULL,
    steps jsonb DEFAULT '[]'::jsonb NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_encryption_metadata; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_encryption_metadata (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    asset_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    encryption_alg character varying(100) DEFAULT 'AES-GCM-256'::character varying NOT NULL,
    key_id character varying(200),
    encrypted_file_key text,
    iv_base64 text NOT NULL,
    auth_tag_base64 text,
    ciphertext_sha256 character varying(128) NOT NULL,
    plaintext_sha256 character varying(128),
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_number_series; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_number_series (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    doc_type character varying(100) NOT NULL,
    prefix character varying(30) NOT NULL,
    next_number integer DEFAULT 1 NOT NULL,
    padding integer DEFAULT 4 NOT NULL,
    response_days integer,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_number_series_next CHECK ((next_number >= 1)),
    CONSTRAINT ck_number_series_padding CHECK (((padding >= 1) AND (padding <= 10)))
);


--
-- Name: document_transmittal_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_transmittal_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    transmittal_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_transmittal_recipients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_transmittal_recipients (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    transmittal_id uuid NOT NULL,
    recipient_organization_id uuid NOT NULL,
    acknowledged_at timestamp without time zone,
    acknowledged_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_transmittals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_transmittals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    transmittal_no character varying(100) NOT NULL,
    sender_organization_id uuid NOT NULL,
    purpose character varying(40) NOT NULL,
    subject character varying(300),
    message text,
    status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL,
    issued_at timestamp without time zone,
    issued_by uuid,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_transmittal_purpose CHECK (((purpose)::text = ANY ((ARRAY['FOR_REVIEW'::character varying, 'FOR_APPROVAL'::character varying, 'FOR_INFORMATION'::character varying, 'FOR_CONSTRUCTION'::character varying, 'AS_BUILT'::character varying])::text[]))),
    CONSTRAINT ck_transmittal_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ISSUED'::character varying, 'PARTIALLY_ACKNOWLEDGED'::character varying, 'ACKNOWLEDGED'::character varying, 'CLOSED'::character varying])::text[])))
);


--
-- Name: document_upload_link_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_upload_link_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    link_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    event_type character varying(30) NOT NULL,
    document_id uuid,
    uploader_name character varying(255),
    uploader_email character varying(320),
    ip_address character varying(64),
    detail character varying(500),
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_upload_link_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_upload_link_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    link_id uuid NOT NULL,
    token character varying(64) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_upload_links; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_upload_links (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid,
    doc_type character varying(100) NOT NULL,
    label character varying(200) NOT NULL,
    token character varying(64) NOT NULL,
    password_hash character varying(100),
    max_uploads integer,
    upload_count integer DEFAULT 0 NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    revoked_at timestamp without time zone,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: document_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_versions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    document_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    version_num integer NOT NULL,
    asset_id uuid,
    change_notes text,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    revision_code character varying(30) NOT NULL,
    issue_status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL,
    issue_purpose character varying(40),
    issued_at timestamp without time zone,
    issued_by uuid,
    CONSTRAINT ck_document_version_issue_status CHECK (((issue_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ISSUED'::character varying, 'SUPERSEDED'::character varying])::text[])))
);


--
-- Name: documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.documents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    title character varying(500) NOT NULL,
    doc_type character varying(100) DEFAULT 'GENERAL'::character varying NOT NULL,
    description text,
    tags text[],
    current_version integer DEFAULT 1 NOT NULL,
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    workflow_id uuid,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    project_id uuid,
    originator_org_id uuid,
    document_code character varying(80),
    due_at timestamp without time zone,
    review_outcome character varying(20),
    approved_value numeric(18,2),
    security_classification character varying(30) DEFAULT 'PROJECT'::character varying NOT NULL,
    discipline character varying(80),
    package_code character varying(80),
    location_code character varying(80),
    issue_purpose character varying(40),
    current_revision_code character varying(30) DEFAULT '01'::character varying NOT NULL,
    issued_at timestamp without time zone,
    issued_by uuid,
    upload_link_id uuid,
    uploader_name character varying(255),
    uploader_email character varying(320),
    intake_channel character varying(30) DEFAULT 'PORTAL'::character varying NOT NULL,
    CONSTRAINT ck_document_issue_purpose CHECK (((issue_purpose IS NULL) OR ((issue_purpose)::text = ANY ((ARRAY['FOR_REVIEW'::character varying, 'FOR_APPROVAL'::character varying, 'FOR_INFORMATION'::character varying, 'FOR_CONSTRUCTION'::character varying, 'AS_BUILT'::character varying])::text[])))),
    CONSTRAINT ck_document_security CHECK (((security_classification)::text = ANY ((ARRAY['PROJECT'::character varying, 'ORGANIZATION'::character varying, 'RESTRICTED'::character varying])::text[])))
);


--
-- Name: COLUMN documents.approved_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.documents.approved_value IS 'Value of work this document evidences. When set, a claim against it cannot exceed this.';


--
-- Name: early_warning_signals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.early_warning_signals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    forecast_snapshot_id uuid NOT NULL,
    signal_code character varying(80) NOT NULL,
    severity character varying(20) NOT NULL,
    title character varying(300) NOT NULL,
    description character varying(1000) NOT NULL,
    metric_value numeric(18,4),
    threshold_value numeric(18,4),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_warning_severity CHECK (((severity)::text = ANY ((ARRAY['INFO'::character varying, 'ATTENTION'::character varying, 'CRITICAL'::character varying])::text[])))
);


--
-- Name: equipment_usage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.equipment_usage (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    usage_date date NOT NULL,
    running_hours numeric(10,2) DEFAULT 0 NOT NULL,
    quantity numeric(18,3),
    status character varying(30) DEFAULT 'APPROVED'::character varying NOT NULL,
    notes character varying(500),
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_equipment_running_hours CHECK (((running_hours >= (0)::numeric) AND (running_hours <= (24)::numeric)))
);


--
-- Name: feature_api_path; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feature_api_path (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    feature_code character varying(100) NOT NULL,
    path_pattern character varying(200) NOT NULL
);


--
-- Name: feature_catalog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feature_catalog (
    feature_code character varying(100) NOT NULL,
    module character varying(50) NOT NULL,
    nav_section character varying(100),
    nav_label character varying(150) NOT NULL,
    nav_icon character varying(50),
    route character varying(200),
    min_role character varying(50) DEFAULT 'VIEWER'::character varying NOT NULL,
    is_core boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: forecast_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.forecast_snapshots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    source_organization_id uuid,
    snapshot_date date NOT NULL,
    forecast_final_cost numeric(18,2) NOT NULL,
    estimate_to_complete numeric(18,2) DEFAULT 0 NOT NULL,
    physical_progress_percent numeric(5,2),
    schedule_progress_percent numeric(5,2),
    notes text,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_forecast_progress CHECK ((((physical_progress_percent IS NULL) OR ((physical_progress_percent >= (0)::numeric) AND (physical_progress_percent <= (100)::numeric))) AND ((schedule_progress_percent IS NULL) OR ((schedule_progress_percent >= (0)::numeric) AND (schedule_progress_percent <= (100)::numeric)))))
);


--
-- Name: knowledge_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_documents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    title character varying(300) NOT NULL,
    document_type character varying(100) NOT NULL,
    source_type character varying(100) NOT NULL,
    content text NOT NULL,
    metadata jsonb,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: knowledge_embeddings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_embeddings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    embedding public.vector(384) NOT NULL,
    metadata jsonb,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: lead_signals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.lead_signals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    contact_id uuid,
    conversation_id uuid,
    signal_type character varying(50) NOT NULL,
    intent_category character varying(50),
    message_text text,
    score double precision DEFAULT 0.0 NOT NULL,
    platform_code character varying(50) DEFAULT 'WHATSAPP'::character varying NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    captured_at timestamp without time zone DEFAULT now() NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: learning_insights; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.learning_insights (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    insight_type character varying(100) NOT NULL,
    title character varying(300) NOT NULL,
    summary text NOT NULL,
    recommendation text,
    confidence_score double precision DEFAULT 0.0 NOT NULL,
    supporting_data jsonb DEFAULT '{}'::jsonb NOT NULL,
    generated_at timestamp without time zone DEFAULT now() NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: material_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_receipts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    commitment_id uuid,
    budget_line_id uuid,
    receipt_ref character varying(100) NOT NULL,
    material_code character varying(100),
    description character varying(500) NOT NULL,
    receipt_date date NOT NULL,
    quantity numeric(18,3) DEFAULT 0 NOT NULL,
    unit character varying(30),
    unit_cost numeric(18,2) DEFAULT 0 NOT NULL,
    amount numeric(18,2) DEFAULT 0 NOT NULL,
    currency character varying(10) DEFAULT 'AED'::character varying NOT NULL,
    status character varying(30) DEFAULT 'ACCEPTED'::character varying NOT NULL,
    document_id uuid,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_material_receipt_values CHECK (((quantity >= (0)::numeric) AND (unit_cost >= (0)::numeric) AND (amount >= (0)::numeric)))
);


--
-- Name: media_assets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.media_assets (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    original_name character varying(500) NOT NULL,
    stored_path character varying(1000) NOT NULL,
    content_type character varying(255) NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    asset_type character varying(100) DEFAULT 'DOCUMENT'::character varying NOT NULL,
    ref_id uuid,
    uploaded_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    storage_provider character varying(100) DEFAULT 'LOCAL'::character varying NOT NULL,
    bucket_name character varying(255),
    object_key text,
    checksum_sha256 character varying(128),
    visibility character varying(50) DEFAULT 'PRIVATE'::character varying NOT NULL,
    status character varying(50) DEFAULT 'UPLOADED'::character varying NOT NULL,
    created_by uuid,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    scan_status character varying(30) DEFAULT 'CLEAN'::character varying NOT NULL,
    scanned_at timestamp without time zone
);


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    wa_message_id character varying(200),
    direction character varying(20) NOT NULL,
    message_type character varying(50) NOT NULL,
    text_body text,
    raw_payload text,
    ai_generated boolean DEFAULT false NOT NULL,
    sent_by_agent_id uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    intent character varying(120),
    confidence_score double precision,
    action_type character varying(120),
    buttons_json text
);


--
-- Name: organizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organizations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(300) NOT NULL,
    org_code character varying(50) NOT NULL,
    trade_license character varying(100),
    contact_email character varying(320),
    contact_phone character varying(50),
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: payment_application_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_application_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    payment_application_id uuid NOT NULL,
    document_id uuid,
    description character varying(500),
    amount numeric(18,2) DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: payment_applications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_applications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    application_ref character varying(80) NOT NULL,
    claimed_by_org_id uuid NOT NULL,
    period_start date,
    period_end date,
    gross_claimed numeric(18,2) DEFAULT 0 NOT NULL,
    previously_certified numeric(18,2) DEFAULT 0 NOT NULL,
    retention_percent numeric(5,2) DEFAULT 0 NOT NULL,
    retention_amount numeric(18,2) DEFAULT 0 NOT NULL,
    net_certified numeric(18,2) DEFAULT 0 NOT NULL,
    currency character varying(10) DEFAULT 'AED'::character varying NOT NULL,
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    submitted_at timestamp without time zone,
    certified_by uuid,
    certified_at timestamp without time zone,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    paid_by uuid,
    paid_at timestamp without time zone,
    payment_reference character varying(120),
    CONSTRAINT ck_payment_app_retention CHECK (((retention_percent >= (0)::numeric) AND (retention_percent <= (100)::numeric)))
);


--
-- Name: payment_audit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_audit_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    payment_application_id uuid NOT NULL,
    actor_user_id uuid,
    event_type character varying(100) NOT NULL,
    event_payload jsonb,
    event_hash character varying(128),
    previous_event_hash character varying(128),
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: permission_audit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.permission_audit_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid,
    document_id uuid,
    actor_user_id uuid,
    event_type character varying(80) NOT NULL,
    principal_type character varying(40),
    principal_value character varying(320),
    permission_code character varying(100),
    old_value jsonb,
    new_value jsonb,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: plan_features; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_features (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    plan_code character varying(100) NOT NULL,
    feature_code character varying(100) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    limits jsonb
);


--
-- Name: platform_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    platform_code character varying(50) NOT NULL,
    external_account_id character varying(200),
    account_name character varying(200),
    account_handle character varying(200),
    credential_ref character varying(300),
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: platform_admins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_admins (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email character varying(320) NOT NULL,
    password_hash text NOT NULL,
    full_name character varying(200),
    active boolean DEFAULT true NOT NULL,
    last_login_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: platforms; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platforms (
    code character varying(50) NOT NULL,
    display_name character varying(100) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    capability_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: product_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_categories (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    sort_order integer DEFAULT 0,
    category_id uuid,
    retailer_id character varying(200),
    whatsapp_catalog_id character varying(200),
    inventory_count integer,
    metadata jsonb,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: products; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.products (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    category_id uuid,
    name character varying(200) NOT NULL,
    description text,
    price numeric(12,2),
    currency character varying(10) DEFAULT 'AED'::character varying,
    retailer_id character varying(200),
    whatsapp_catalog_id character varying(200),
    image_url text,
    inventory_count integer,
    metadata jsonb,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: project_capability_overrides; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_capability_overrides (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    party_role character varying(40) NOT NULL,
    user_role character varying(40) NOT NULL,
    permission_code character varying(100) NOT NULL,
    effect character varying(10) NOT NULL,
    data_scope character varying(20),
    created_by uuid,
    updated_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_capability_effect CHECK (((effect)::text = ANY ((ARRAY['ALLOW'::character varying, 'DENY'::character varying])::text[]))),
    CONSTRAINT ck_capability_party_role CHECK (((party_role)::text = ANY ((ARRAY['CLIENT'::character varying, 'CONSULTANT'::character varying, 'CONTRACTOR'::character varying, 'SUBCONTRACTOR'::character varying])::text[]))),
    CONSTRAINT ck_capability_scope CHECK (((data_scope IS NULL) OR ((data_scope)::text = ANY ((ARRAY['PROJECT'::character varying, 'ORGANIZATION'::character varying, 'ASSIGNED'::character varying])::text[])))),
    CONSTRAINT ck_capability_user_role CHECK (((user_role)::text = ANY ((ARRAY['ADMIN'::character varying, 'MANAGER'::character varying, 'REVIEWER'::character varying, 'VIEWER'::character varying])::text[])))
);


--
-- Name: project_commitments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_commitments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    budget_line_id uuid,
    commitment_type character varying(30) NOT NULL,
    reference_no character varying(100) NOT NULL,
    description character varying(500),
    original_amount numeric(18,2) DEFAULT 0 NOT NULL,
    approved_changes numeric(18,2) DEFAULT 0 NOT NULL,
    currency character varying(10) DEFAULT 'AED'::character varying NOT NULL,
    status character varying(30) DEFAULT 'ACTIVE'::character varying NOT NULL,
    start_date date,
    end_date date,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_commitment_amounts CHECK (((original_amount >= (0)::numeric) AND (approved_changes >= (0)::numeric))),
    CONSTRAINT ck_commitment_type CHECK (((commitment_type)::text = ANY ((ARRAY['PURCHASE_ORDER'::character varying, 'SUBCONTRACT'::character varying, 'OTHER'::character varying])::text[])))
);


--
-- Name: project_contracts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_contracts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    participant_id uuid NOT NULL,
    contract_ref character varying(100) NOT NULL,
    commercial_model character varying(40) NOT NULL,
    original_value numeric(18,2) DEFAULT 0 NOT NULL,
    approved_variations numeric(18,2) DEFAULT 0 NOT NULL,
    currency character varying(10) DEFAULT 'AED'::character varying NOT NULL,
    start_date date,
    end_date date,
    status character varying(30) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_contract_value CHECK ((original_value >= (0)::numeric))
);


--
-- Name: project_participants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_participants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    party_role character varying(50) NOT NULL,
    parent_participant_id uuid,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: project_resources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_resources (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    resource_type character varying(30) NOT NULL,
    resource_code character varying(80) NOT NULL,
    display_name character varying(300) NOT NULL,
    user_id uuid,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_project_resource_type CHECK (((resource_type)::text = ANY ((ARRAY['PERSON'::character varying, 'EQUIPMENT'::character varying, 'MACHINE'::character varying, 'VEHICLE'::character varying])::text[])))
);


--
-- Name: project_variations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_variations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid,
    budget_line_id uuid,
    variation_ref character varying(100) NOT NULL,
    title character varying(300) NOT NULL,
    description text,
    source_type character varying(40),
    source_document_id uuid,
    requested_amount numeric(18,2) DEFAULT 0 NOT NULL,
    approved_amount numeric(18,2),
    currency character varying(10) DEFAULT 'AED'::character varying NOT NULL,
    status character varying(30) DEFAULT 'PROPOSED'::character varying NOT NULL,
    submitted_at timestamp without time zone,
    approved_at timestamp without time zone,
    approved_by uuid,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_variation_approved CHECK (((approved_amount IS NULL) OR (approved_amount >= (0)::numeric))),
    CONSTRAINT ck_variation_requested CHECK ((requested_amount >= (0)::numeric))
);


--
-- Name: projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.projects (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(300) NOT NULL,
    project_code character varying(50) NOT NULL,
    description text,
    contract_value numeric(18,2),
    currency character varying(10) DEFAULT 'AED'::character varying NOT NULL,
    retention_percent numeric(5,2) DEFAULT 10.00 NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    start_date date,
    end_date date,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_projects_retention CHECK (((retention_percent >= (0)::numeric) AND (retention_percent <= (100)::numeric)))
);


--
-- Name: publish_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.publish_jobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    content_idea_id uuid NOT NULL,
    platform_account_id uuid NOT NULL,
    platform_code character varying(50) NOT NULL,
    status character varying(50) DEFAULT 'SCHEDULED'::character varying NOT NULL,
    scheduled_at timestamp without time zone NOT NULL,
    content_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: publish_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.publish_results (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    job_id uuid NOT NULL,
    success boolean NOT NULL,
    external_publish_id character varying(300),
    error_message text,
    published_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: publishing_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.publishing_jobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    content_item_id uuid NOT NULL,
    social_account_id uuid NOT NULL,
    platform character varying(100) NOT NULL,
    asset_id uuid,
    caption text,
    hashtags text[] DEFAULT '{}'::text[] NOT NULL,
    scheduled_at timestamp without time zone NOT NULL,
    status character varying(50) DEFAULT 'SCHEDULED'::character varying NOT NULL,
    external_post_id character varying(300),
    external_post_url text,
    retry_count integer DEFAULT 0 NOT NULL,
    error_message text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: render_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.render_jobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    content_item_id uuid NOT NULL,
    template_id uuid,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    render_instructions jsonb DEFAULT '{}'::jsonb NOT NULL,
    output_asset_id uuid,
    error_message text,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: resource_rates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resource_rates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    rate_type character varying(30) NOT NULL,
    rate_amount numeric(18,2) NOT NULL,
    currency character varying(10) DEFAULT 'AED'::character varying NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_resource_rate_amount CHECK ((rate_amount >= (0)::numeric)),
    CONSTRAINT ck_resource_rate_type CHECK (((rate_type)::text = ANY ((ARRAY['HOURLY'::character varying, 'DAILY'::character varying, 'MONTHLY'::character varying, 'UNIT'::character varying])::text[])))
);


--
-- Name: role_permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role_permissions (
    role character varying(50) NOT NULL,
    feature_code character varying(100) NOT NULL,
    action character varying(50) NOT NULL,
    allowed boolean DEFAULT false NOT NULL
);


--
-- Name: service_appointments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_appointments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    contact_id uuid,
    vehicle_id uuid,
    service_type character varying(100) DEFAULT 'ANY'::character varying NOT NULL,
    appointment_date date NOT NULL,
    time_slot character varying(30) NOT NULL,
    status character varying(50) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    customer_phone character varying(50),
    customer_name character varying(200),
    notes text,
    metadata jsonb,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_service_appointments_status CHECK (((status)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'BOOKED'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: service_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_records (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    vehicle_id uuid NOT NULL,
    contact_id uuid NOT NULL,
    service_type character varying(100) NOT NULL,
    description text NOT NULL,
    technician_name character varying(150),
    mileage_at_service integer,
    cost numeric(12,2),
    currency character varying(10) DEFAULT 'AED'::character varying,
    notes text,
    service_date timestamp without time zone NOT NULL,
    next_service_date timestamp without time zone,
    status character varying(50) DEFAULT 'COMPLETED'::character varying NOT NULL,
    metadata jsonb,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: social_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.social_accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    platform character varying(100) NOT NULL,
    account_name character varying(300),
    external_account_id character varying(300),
    access_token_encrypted text,
    refresh_token_encrypted text,
    token_expires_at timestamp without time zone,
    status character varying(50) DEFAULT 'CONNECTED'::character varying NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: storage_upload_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.storage_upload_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    token character varying(200) NOT NULL,
    object_key text NOT NULL,
    content_type character varying(255),
    size_bytes bigint,
    expires_at timestamp without time zone NOT NULL,
    used boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: subscription_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscription_plans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    monthly_price numeric(12,2),
    active boolean DEFAULT true NOT NULL,
    limits jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: tenant_agents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_agents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(150) NOT NULL,
    email character varying(200) NOT NULL,
    role character varying(50) DEFAULT 'AGENT'::character varying NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: tenant_features; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_features (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    feature_code character varying(100) NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    config jsonb,
    enabled_at timestamp without time zone,
    disabled_at timestamp without time zone,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: tenant_notification_contacts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_notification_contacts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    purpose character varying(100) NOT NULL,
    display_name character varying(150) NOT NULL,
    phone_number character varying(50) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: tenant_saved_trends; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_saved_trends (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    trend_id uuid NOT NULL,
    saved_by uuid,
    status character varying(50) DEFAULT 'SAVED'::character varying NOT NULL,
    notes text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: tenant_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_subscriptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    plan_code character varying(100) NOT NULL,
    status character varying(50) DEFAULT 'TRIAL'::character varying NOT NULL,
    started_at timestamp without time zone DEFAULT now() NOT NULL,
    expires_at timestamp without time zone,
    trial_ends_at timestamp without time zone,
    cancelled_at timestamp without time zone,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: tenant_usage_daily; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_usage_daily (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    usage_date date NOT NULL,
    storage_bytes bigint DEFAULT 0 NOT NULL,
    bandwidth_bytes bigint DEFAULT 0 NOT NULL,
    ai_tokens bigint DEFAULT 0 NOT NULL,
    render_seconds bigint DEFAULT 0 NOT NULL,
    generated_assets_count integer DEFAULT 0 NOT NULL,
    scheduled_posts_count integer DEFAULT 0 NOT NULL,
    published_posts_count integer DEFAULT 0 NOT NULL,
    document_count integer DEFAULT 0 NOT NULL
);


--
-- Name: tenant_users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    email character varying(320) NOT NULL,
    password_hash character varying(255) NOT NULL,
    full_name character varying(200),
    role character varying(50) DEFAULT 'ADMIN'::character varying NOT NULL,
    active boolean DEFAULT true NOT NULL,
    last_login_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    organization_id uuid,
    notification_phone character varying(50),
    email_notifications_enabled boolean DEFAULT true NOT NULL,
    whatsapp_notifications_enabled boolean DEFAULT false NOT NULL
);


--
-- Name: tenants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_code character varying(100) NOT NULL,
    business_name character varying(200) NOT NULL,
    business_type character varying(50) NOT NULL,
    phone_number_id character varying(100) NOT NULL,
    waba_id character varying(100),
    access_token_encrypted text,
    system_prompt text NOT NULL,
    default_language character varying(20) DEFAULT 'en'::character varying NOT NULL,
    timezone character varying(100) DEFAULT 'Asia/Dubai'::character varying NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    business_hours character varying(200) DEFAULT 'Sat-Thu 9am-9pm'::character varying NOT NULL,
    crm_business_type character varying(50) DEFAULT 'other'::character varying NOT NULL,
    whatsapp_number character varying(100),
    faq_json jsonb DEFAULT '[]'::jsonb NOT NULL
);


--
-- Name: timesheets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.timesheets (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    work_date date NOT NULL,
    hours numeric(8,2) NOT NULL,
    status character varying(30) DEFAULT 'SUBMITTED'::character varying NOT NULL,
    description character varying(500),
    approved_by uuid,
    approved_at timestamp without time zone,
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_timesheet_hours CHECK (((hours >= (0)::numeric) AND (hours <= (24)::numeric)))
);


--
-- Name: trend_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trend_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    source_platform character varying(100) NOT NULL,
    niche character varying(200),
    country character varying(100),
    language character varying(50),
    title character varying(500),
    trend_url text,
    audio_ref text,
    hashtags text[] DEFAULT '{}'::text[] NOT NULL,
    score numeric(10,2) DEFAULT 0 NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    discovered_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: trend_signals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trend_signals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid,
    keyword character varying(200),
    hashtag character varying(200),
    topic text,
    country character varying(10),
    industry character varying(100),
    platform_code character varying(50),
    raw_score double precision DEFAULT 0.0 NOT NULL,
    final_score double precision DEFAULT 0.0 NOT NULL,
    freshness_score double precision DEFAULT 0.0 NOT NULL,
    growth_score double precision DEFAULT 0.0 NOT NULL,
    relevance_score double precision DEFAULT 0.0 NOT NULL,
    engagement_score double precision DEFAULT 0.0 NOT NULL,
    brand_safety_score double precision DEFAULT 0.0 NOT NULL,
    captured_at timestamp without time zone DEFAULT now() NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: trend_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trend_sources (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    source_type character varying(50) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: vehicles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vehicles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    contact_id uuid NOT NULL,
    make character varying(100) NOT NULL,
    model character varying(100) NOT NULL,
    model_year integer,
    plate_number character varying(50) NOT NULL,
    vin character varying(100),
    color character varying(80),
    metadata jsonb,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: video_scripts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.video_scripts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    content_idea_id uuid,
    title character varying(300) NOT NULL,
    platform_code character varying(50) DEFAULT 'INSTAGRAM'::character varying NOT NULL,
    content_type character varying(50) DEFAULT 'REEL'::character varying NOT NULL,
    style character varying(100) DEFAULT 'ENGAGING'::character varying NOT NULL,
    duration_secs integer DEFAULT 30 NOT NULL,
    hook text,
    script_body text,
    shot_list jsonb DEFAULT '[]'::jsonb NOT NULL,
    hashtags jsonb DEFAULT '[]'::jsonb NOT NULL,
    caption text,
    music_suggestion character varying(200),
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    generated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: video_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.video_templates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    scope character varying(50) DEFAULT 'SYSTEM'::character varying NOT NULL,
    name character varying(300) NOT NULL,
    category character varying(200),
    format character varying(50) NOT NULL,
    template_asset_id uuid,
    preview_asset_id uuid,
    thumbnail_asset_id uuid,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: webhook_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.webhook_outbox (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    payload jsonb NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    error_message text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    processed_at timestamp without time zone
);


--
-- Name: whatsapp_button_replies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whatsapp_button_replies (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    button_id character varying(120) NOT NULL,
    button_title character varying(60) NOT NULL,
    reply_kind character varying(20) DEFAULT 'TEXT'::character varying NOT NULL,
    reply_text text,
    tool_name character varying(120),
    tool_arguments_json text,
    sort_order integer DEFAULT 0 NOT NULL,
    description text,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_whatsapp_button_replies_kind CHECK (((reply_kind)::text = ANY ((ARRAY['TEXT'::character varying, 'TOOL_CALL'::character varying])::text[]))),
    CONSTRAINT chk_whatsapp_button_replies_text_present CHECK ((((reply_kind)::text <> 'TEXT'::text) OR ((reply_text IS NOT NULL) AND (length(TRIM(BOTH FROM reply_text)) > 0))))
);


--
-- Name: whatsapp_flow_registry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whatsapp_flow_registry (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    flow_key character varying(120) NOT NULL,
    flow_id character varying(120) NOT NULL,
    flow_cta character varying(80) DEFAULT 'Open'::character varying NOT NULL,
    screen_id character varying(120),
    description text,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: whatsapp_flow_submissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whatsapp_flow_submissions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    conversation_id uuid,
    contact_id uuid,
    flow_id character varying(120),
    flow_token character varying(200),
    response_json jsonb NOT NULL,
    raw_payload jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: whatsapp_interactive_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whatsapp_interactive_messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    conversation_id uuid,
    contact_id uuid,
    direction character varying(20) DEFAULT 'OUTBOUND'::character varying NOT NULL,
    interactive_type character varying(80) NOT NULL,
    provider_message_id character varying(200),
    request_payload jsonb NOT NULL,
    response_payload jsonb,
    status character varying(40) DEFAULT 'SENT'::character varying NOT NULL,
    error_message text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: whatsapp_message_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whatsapp_message_templates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    template_code character varying(120) NOT NULL,
    meta_template_name character varying(250) NOT NULL,
    language_code character varying(20) DEFAULT 'en'::character varying NOT NULL,
    category character varying(50) NOT NULL,
    audience character varying(50) DEFAULT 'CUSTOMER'::character varying NOT NULL,
    description text,
    body_preview text,
    component_schema_json text,
    default_components_json text,
    enabled_for_ai boolean DEFAULT false NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: whatsapp_order_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whatsapp_order_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    whatsapp_order_id uuid NOT NULL,
    product_retailer_id character varying(200),
    product_id uuid,
    quantity integer NOT NULL,
    item_price numeric(12,2),
    currency character varying(10),
    raw_item jsonb,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: whatsapp_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whatsapp_orders (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    conversation_id uuid,
    contact_id uuid,
    wa_message_id character varying(200),
    catalog_id character varying(200),
    order_payload jsonb NOT NULL,
    status character varying(50) DEFAULT 'RECEIVED'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: whatsapp_template_send_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whatsapp_template_send_audit (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    conversation_id uuid,
    template_id uuid NOT NULL,
    recipient_type character varying(50) NOT NULL,
    recipient_phone_number character varying(50) NOT NULL,
    request_components_json text,
    graph_payload_json text,
    graph_status_code integer,
    graph_response_body text,
    sent_by character varying(50) DEFAULT 'AI_TOOL'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: workflow_in_app_notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workflow_in_app_notifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    outbox_id uuid NOT NULL,
    project_id uuid,
    document_id uuid,
    transmittal_id uuid,
    event_type character varying(60) NOT NULL,
    title character varying(240) NOT NULL,
    body text NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    read_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: workflow_notification_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workflow_notification_deliveries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    outbox_id uuid NOT NULL,
    user_id uuid NOT NULL,
    channel character varying(20) NOT NULL,
    destination character varying(320) NOT NULL,
    subject character varying(240),
    body text NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp without time zone DEFAULT now() NOT NULL,
    claimed_at timestamp without time zone,
    last_error text,
    sent_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_notification_delivery_channel CHECK (((channel)::text = ANY ((ARRAY['EMAIL'::character varying, 'WHATSAPP'::character varying])::text[]))),
    CONSTRAINT ck_notification_delivery_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'SENT'::character varying, 'FAILED'::character varying, 'DEAD'::character varying, 'SKIPPED'::character varying])::text[])))
);


--
-- Name: workflow_notification_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workflow_notification_outbox (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    project_id uuid,
    document_id uuid,
    approval_id uuid,
    approval_step_id uuid,
    event_type character varying(60) NOT NULL,
    target_user_id uuid,
    target_organization_id uuid,
    target_party_role character varying(40),
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    delivered_at timestamp without time zone,
    transmittal_id uuid,
    dispatched_at timestamp without time zone,
    claimed_at timestamp without time zone,
    CONSTRAINT ck_workflow_notification_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'DELIVERED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_workflow_notification_target CHECK (((target_user_id IS NOT NULL) OR (target_organization_id IS NOT NULL) OR (target_party_role IS NOT NULL)))
);


--
-- Name: actual_cost_entries actual_cost_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actual_cost_entries
    ADD CONSTRAINT actual_cost_entries_pkey PRIMARY KEY (id);


--
-- Name: analytics_snapshots analytics_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analytics_snapshots
    ADD CONSTRAINT analytics_snapshots_pkey PRIMARY KEY (id);


--
-- Name: approval_tasks approval_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_tasks
    ADD CONSTRAINT approval_tasks_pkey PRIMARY KEY (id);


--
-- Name: background_jobs background_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.background_jobs
    ADD CONSTRAINT background_jobs_pkey PRIMARY KEY (id);


--
-- Name: budget_lines budget_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_lines
    ADD CONSTRAINT budget_lines_pkey PRIMARY KEY (id);


--
-- Name: budget_versions budget_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_versions
    ADD CONSTRAINT budget_versions_pkey PRIMARY KEY (id);


--
-- Name: campaigns campaigns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaigns
    ADD CONSTRAINT campaigns_pkey PRIMARY KEY (id);


--
-- Name: canned_responses canned_responses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canned_responses
    ADD CONSTRAINT canned_responses_pkey PRIMARY KEY (id);


--
-- Name: document_access_grants ck_document_grant_permission; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.document_access_grants
    ADD CONSTRAINT ck_document_grant_permission CHECK (((permission_code)::text = ANY ((ARRAY['VIEW'::character varying, 'EDIT'::character varying, 'ISSUE'::character varying, 'MANAGE'::character varying])::text[]))) NOT VALID;


--
-- Name: consultant_kpi_snapshots consultant_kpi_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultant_kpi_snapshots
    ADD CONSTRAINT consultant_kpi_snapshots_pkey PRIMARY KEY (id);


--
-- Name: contacts contacts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_pkey PRIMARY KEY (id);


--
-- Name: content_ideas content_ideas_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_ideas
    ADD CONSTRAINT content_ideas_pkey PRIMARY KEY (id);


--
-- Name: content_items content_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_items
    ADD CONSTRAINT content_items_pkey PRIMARY KEY (id);


--
-- Name: content_variants content_variants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_variants
    ADD CONSTRAINT content_variants_pkey PRIMARY KEY (id);


--
-- Name: control_forecast_snapshots control_forecast_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_forecast_snapshots
    ADD CONSTRAINT control_forecast_snapshots_pkey PRIMARY KEY (id);


--
-- Name: conversation_events conversation_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_events
    ADD CONSTRAINT conversation_events_pkey PRIMARY KEY (id);


--
-- Name: conversations conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_pkey PRIMARY KEY (id);


--
-- Name: document_access_grants document_access_grants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_access_grants
    ADD CONSTRAINT document_access_grants_pkey PRIMARY KEY (id);


--
-- Name: document_approval_steps document_approval_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approval_steps
    ADD CONSTRAINT document_approval_steps_pkey PRIMARY KEY (id);


--
-- Name: document_approvals document_approvals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_pkey PRIMARY KEY (id);


--
-- Name: document_audit_events document_audit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_audit_events
    ADD CONSTRAINT document_audit_events_pkey PRIMARY KEY (id);


--
-- Name: document_comments document_comments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_comments
    ADD CONSTRAINT document_comments_pkey PRIMARY KEY (id);


--
-- Name: document_control_workflows document_control_workflows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_control_workflows
    ADD CONSTRAINT document_control_workflows_pkey PRIMARY KEY (id);


--
-- Name: document_encryption_metadata document_encryption_metadata_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_encryption_metadata
    ADD CONSTRAINT document_encryption_metadata_pkey PRIMARY KEY (id);


--
-- Name: document_number_series document_number_series_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_number_series
    ADD CONSTRAINT document_number_series_pkey PRIMARY KEY (id);


--
-- Name: document_transmittal_items document_transmittal_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_items
    ADD CONSTRAINT document_transmittal_items_pkey PRIMARY KEY (id);


--
-- Name: document_transmittal_items document_transmittal_items_transmittal_id_document_version__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_items
    ADD CONSTRAINT document_transmittal_items_transmittal_id_document_version__key UNIQUE (transmittal_id, document_version_id);


--
-- Name: document_transmittal_recipients document_transmittal_recipien_transmittal_id_recipient_orga_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_recipients
    ADD CONSTRAINT document_transmittal_recipien_transmittal_id_recipient_orga_key UNIQUE (transmittal_id, recipient_organization_id);


--
-- Name: document_transmittal_recipients document_transmittal_recipients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_recipients
    ADD CONSTRAINT document_transmittal_recipients_pkey PRIMARY KEY (id);


--
-- Name: document_transmittals document_transmittals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittals
    ADD CONSTRAINT document_transmittals_pkey PRIMARY KEY (id);


--
-- Name: document_upload_link_events document_upload_link_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_link_events
    ADD CONSTRAINT document_upload_link_events_pkey PRIMARY KEY (id);


--
-- Name: document_upload_link_sessions document_upload_link_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_link_sessions
    ADD CONSTRAINT document_upload_link_sessions_pkey PRIMARY KEY (id);


--
-- Name: document_upload_link_sessions document_upload_link_sessions_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_link_sessions
    ADD CONSTRAINT document_upload_link_sessions_token_key UNIQUE (token);


--
-- Name: document_upload_links document_upload_links_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_links
    ADD CONSTRAINT document_upload_links_pkey PRIMARY KEY (id);


--
-- Name: document_upload_links document_upload_links_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_links
    ADD CONSTRAINT document_upload_links_token_key UNIQUE (token);


--
-- Name: document_versions document_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT document_versions_pkey PRIMARY KEY (id);


--
-- Name: documents documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (id);


--
-- Name: early_warning_signals early_warning_signals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.early_warning_signals
    ADD CONSTRAINT early_warning_signals_pkey PRIMARY KEY (id);


--
-- Name: equipment_usage equipment_usage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment_usage
    ADD CONSTRAINT equipment_usage_pkey PRIMARY KEY (id);


--
-- Name: feature_api_path feature_api_path_path_pattern_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_api_path
    ADD CONSTRAINT feature_api_path_path_pattern_key UNIQUE (path_pattern);


--
-- Name: feature_api_path feature_api_path_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_api_path
    ADD CONSTRAINT feature_api_path_pkey PRIMARY KEY (id);


--
-- Name: feature_catalog feature_catalog_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_catalog
    ADD CONSTRAINT feature_catalog_pkey PRIMARY KEY (feature_code);


--
-- Name: forecast_snapshots forecast_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.forecast_snapshots
    ADD CONSTRAINT forecast_snapshots_pkey PRIMARY KEY (id);


--
-- Name: knowledge_documents knowledge_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_documents
    ADD CONSTRAINT knowledge_documents_pkey PRIMARY KEY (id);


--
-- Name: knowledge_embeddings knowledge_embeddings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_embeddings
    ADD CONSTRAINT knowledge_embeddings_pkey PRIMARY KEY (id);


--
-- Name: lead_signals lead_signals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lead_signals
    ADD CONSTRAINT lead_signals_pkey PRIMARY KEY (id);


--
-- Name: learning_insights learning_insights_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.learning_insights
    ADD CONSTRAINT learning_insights_pkey PRIMARY KEY (id);


--
-- Name: material_receipts material_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT material_receipts_pkey PRIMARY KEY (id);


--
-- Name: media_assets media_assets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_assets
    ADD CONSTRAINT media_assets_pkey PRIMARY KEY (id);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (id);


--
-- Name: organizations organizations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);


--
-- Name: payment_application_items payment_application_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_application_items
    ADD CONSTRAINT payment_application_items_pkey PRIMARY KEY (id);


--
-- Name: payment_applications payment_applications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_applications
    ADD CONSTRAINT payment_applications_pkey PRIMARY KEY (id);


--
-- Name: payment_audit_events payment_audit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_audit_events
    ADD CONSTRAINT payment_audit_events_pkey PRIMARY KEY (id);


--
-- Name: permission_audit_events permission_audit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permission_audit_events
    ADD CONSTRAINT permission_audit_events_pkey PRIMARY KEY (id);


--
-- Name: plan_features plan_features_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_features
    ADD CONSTRAINT plan_features_pkey PRIMARY KEY (id);


--
-- Name: plan_features plan_features_plan_code_feature_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_features
    ADD CONSTRAINT plan_features_plan_code_feature_code_key UNIQUE (plan_code, feature_code);


--
-- Name: platform_accounts platform_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_accounts
    ADD CONSTRAINT platform_accounts_pkey PRIMARY KEY (id);


--
-- Name: platform_accounts platform_accounts_tenant_id_platform_code_external_account__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_accounts
    ADD CONSTRAINT platform_accounts_tenant_id_platform_code_external_account__key UNIQUE (tenant_id, platform_code, external_account_id);


--
-- Name: platform_admins platform_admins_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_admins
    ADD CONSTRAINT platform_admins_email_key UNIQUE (email);


--
-- Name: platform_admins platform_admins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_admins
    ADD CONSTRAINT platform_admins_pkey PRIMARY KEY (id);


--
-- Name: platforms platforms_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platforms
    ADD CONSTRAINT platforms_pkey PRIMARY KEY (code);


--
-- Name: product_categories product_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_categories
    ADD CONSTRAINT product_categories_pkey PRIMARY KEY (id);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: project_capability_overrides project_capability_overrides_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_capability_overrides
    ADD CONSTRAINT project_capability_overrides_pkey PRIMARY KEY (id);


--
-- Name: project_commitments project_commitments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_commitments
    ADD CONSTRAINT project_commitments_pkey PRIMARY KEY (id);


--
-- Name: project_contracts project_contracts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_contracts
    ADD CONSTRAINT project_contracts_pkey PRIMARY KEY (id);


--
-- Name: project_participants project_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_participants
    ADD CONSTRAINT project_participants_pkey PRIMARY KEY (id);


--
-- Name: project_resources project_resources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_resources
    ADD CONSTRAINT project_resources_pkey PRIMARY KEY (id);


--
-- Name: project_variations project_variations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT project_variations_pkey PRIMARY KEY (id);


--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);


--
-- Name: publish_jobs publish_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publish_jobs
    ADD CONSTRAINT publish_jobs_pkey PRIMARY KEY (id);


--
-- Name: publish_results publish_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publish_results
    ADD CONSTRAINT publish_results_pkey PRIMARY KEY (id);


--
-- Name: publishing_jobs publishing_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publishing_jobs
    ADD CONSTRAINT publishing_jobs_pkey PRIMARY KEY (id);


--
-- Name: render_jobs render_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.render_jobs
    ADD CONSTRAINT render_jobs_pkey PRIMARY KEY (id);


--
-- Name: resource_rates resource_rates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_rates
    ADD CONSTRAINT resource_rates_pkey PRIMARY KEY (id);


--
-- Name: role_permissions role_permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role, feature_code, action);


--
-- Name: service_appointments service_appointments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_appointments
    ADD CONSTRAINT service_appointments_pkey PRIMARY KEY (id);


--
-- Name: service_records service_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_records
    ADD CONSTRAINT service_records_pkey PRIMARY KEY (id);


--
-- Name: social_accounts social_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.social_accounts
    ADD CONSTRAINT social_accounts_pkey PRIMARY KEY (id);


--
-- Name: storage_upload_tokens storage_upload_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_upload_tokens
    ADD CONSTRAINT storage_upload_tokens_pkey PRIMARY KEY (id);


--
-- Name: storage_upload_tokens storage_upload_tokens_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_upload_tokens
    ADD CONSTRAINT storage_upload_tokens_token_key UNIQUE (token);


--
-- Name: subscription_plans subscription_plans_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_plans
    ADD CONSTRAINT subscription_plans_code_key UNIQUE (code);


--
-- Name: subscription_plans subscription_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_plans
    ADD CONSTRAINT subscription_plans_pkey PRIMARY KEY (id);


--
-- Name: tenant_agents tenant_agents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_agents
    ADD CONSTRAINT tenant_agents_pkey PRIMARY KEY (id);


--
-- Name: tenant_features tenant_features_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_features
    ADD CONSTRAINT tenant_features_pkey PRIMARY KEY (id);


--
-- Name: tenant_features tenant_features_tenant_id_feature_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_features
    ADD CONSTRAINT tenant_features_tenant_id_feature_code_key UNIQUE (tenant_id, feature_code);


--
-- Name: tenant_notification_contacts tenant_notification_contacts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_notification_contacts
    ADD CONSTRAINT tenant_notification_contacts_pkey PRIMARY KEY (id);


--
-- Name: tenant_saved_trends tenant_saved_trends_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_saved_trends
    ADD CONSTRAINT tenant_saved_trends_pkey PRIMARY KEY (id);


--
-- Name: tenant_saved_trends tenant_saved_trends_tenant_id_trend_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_saved_trends
    ADD CONSTRAINT tenant_saved_trends_tenant_id_trend_id_key UNIQUE (tenant_id, trend_id);


--
-- Name: tenant_subscriptions tenant_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_subscriptions
    ADD CONSTRAINT tenant_subscriptions_pkey PRIMARY KEY (id);


--
-- Name: tenant_usage_daily tenant_usage_daily_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_usage_daily
    ADD CONSTRAINT tenant_usage_daily_pkey PRIMARY KEY (id);


--
-- Name: tenant_usage_daily tenant_usage_daily_tenant_id_usage_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_usage_daily
    ADD CONSTRAINT tenant_usage_daily_tenant_id_usage_date_key UNIQUE (tenant_id, usage_date);


--
-- Name: tenant_users tenant_users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_users
    ADD CONSTRAINT tenant_users_pkey PRIMARY KEY (id);


--
-- Name: tenants tenants_phone_number_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_phone_number_id_key UNIQUE (phone_number_id);


--
-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);


--
-- Name: tenants tenants_tenant_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_tenant_code_key UNIQUE (tenant_code);


--
-- Name: timesheets timesheets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timesheets
    ADD CONSTRAINT timesheets_pkey PRIMARY KEY (id);


--
-- Name: trend_items trend_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trend_items
    ADD CONSTRAINT trend_items_pkey PRIMARY KEY (id);


--
-- Name: trend_signals trend_signals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trend_signals
    ADD CONSTRAINT trend_signals_pkey PRIMARY KEY (id);


--
-- Name: trend_sources trend_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trend_sources
    ADD CONSTRAINT trend_sources_pkey PRIMARY KEY (id);


--
-- Name: actual_cost_entries uk_actual_cost_source; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actual_cost_entries
    ADD CONSTRAINT uk_actual_cost_source UNIQUE (source_type, source_id);


--
-- Name: budget_lines uk_budget_line_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_lines
    ADD CONSTRAINT uk_budget_line_code UNIQUE (budget_version_id, cost_code);


--
-- Name: canned_responses uk_canned_responses_shortcut; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canned_responses
    ADD CONSTRAINT uk_canned_responses_shortcut UNIQUE (tenant_id, shortcut);


--
-- Name: consultant_kpi_snapshots uk_consultant_kpi_project_org_day; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultant_kpi_snapshots
    ADD CONSTRAINT uk_consultant_kpi_project_org_day UNIQUE (project_id, organization_id, snapshot_date);


--
-- Name: contacts uk_contacts_tenant_wa_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT uk_contacts_tenant_wa_id UNIQUE (tenant_id, wa_id);


--
-- Name: control_forecast_snapshots uk_control_forecast_project_day; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_forecast_snapshots
    ADD CONSTRAINT uk_control_forecast_project_day UNIQUE (project_id, snapshot_date);


--
-- Name: conversations uk_conversations_tenant_contact; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT uk_conversations_tenant_contact UNIQUE (tenant_id, contact_id);


--
-- Name: document_versions uk_doc_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT uk_doc_version UNIQUE (document_id, version_num);


--
-- Name: equipment_usage uk_equipment_usage_resource_day; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment_usage
    ADD CONSTRAINT uk_equipment_usage_resource_day UNIQUE (project_id, resource_id, usage_date);


--
-- Name: workflow_in_app_notifications uk_in_app_notification_user_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_in_app_notifications
    ADD CONSTRAINT uk_in_app_notification_user_event UNIQUE (outbox_id, user_id);


--
-- Name: knowledge_embeddings uk_knowledge_embeddings_document_chunk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_embeddings
    ADD CONSTRAINT uk_knowledge_embeddings_document_chunk UNIQUE (document_id, chunk_index);


--
-- Name: material_receipts uk_material_receipt_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT uk_material_receipt_ref UNIQUE (project_id, receipt_ref);


--
-- Name: workflow_notification_deliveries uk_notification_delivery_user_channel; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_deliveries
    ADD CONSTRAINT uk_notification_delivery_user_channel UNIQUE (outbox_id, user_id, channel);


--
-- Name: document_number_series uk_number_series_project_type; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_number_series
    ADD CONSTRAINT uk_number_series_project_type UNIQUE (project_id, doc_type);


--
-- Name: organizations uk_organizations_tenant_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT uk_organizations_tenant_code UNIQUE (tenant_id, org_code);


--
-- Name: project_participants uk_participant_project_org_role; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_participants
    ADD CONSTRAINT uk_participant_project_org_role UNIQUE (project_id, organization_id, party_role);


--
-- Name: payment_applications uk_payment_app_project_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_applications
    ADD CONSTRAINT uk_payment_app_project_ref UNIQUE (project_id, application_ref);


--
-- Name: payment_application_items uk_payment_item_app_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_application_items
    ADD CONSTRAINT uk_payment_item_app_document UNIQUE (payment_application_id, document_id);


--
-- Name: product_categories uk_product_categories_tenant_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_categories
    ADD CONSTRAINT uk_product_categories_tenant_code UNIQUE (tenant_id, code);


--
-- Name: products uk_products_tenant_retailer_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT uk_products_tenant_retailer_id UNIQUE (tenant_id, retailer_id);


--
-- Name: project_capability_overrides uk_project_capability_override; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_capability_overrides
    ADD CONSTRAINT uk_project_capability_override UNIQUE (project_id, party_role, user_role, permission_code);


--
-- Name: project_commitments uk_project_commitment_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_commitments
    ADD CONSTRAINT uk_project_commitment_ref UNIQUE (project_id, reference_no);


--
-- Name: project_contracts uk_project_contract_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_contracts
    ADD CONSTRAINT uk_project_contract_ref UNIQUE (project_id, contract_ref);


--
-- Name: project_resources uk_project_resource_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_resources
    ADD CONSTRAINT uk_project_resource_code UNIQUE (project_id, organization_id, resource_code);


--
-- Name: project_variations uk_project_variation_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT uk_project_variation_ref UNIQUE (project_id, variation_ref);


--
-- Name: projects uk_projects_tenant_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT uk_projects_tenant_code UNIQUE (tenant_id, project_code);


--
-- Name: service_appointments uk_service_appointments_slot; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_appointments
    ADD CONSTRAINT uk_service_appointments_slot UNIQUE (tenant_id, appointment_date, time_slot);


--
-- Name: tenant_agents uk_tenant_agents_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_agents
    ADD CONSTRAINT uk_tenant_agents_email UNIQUE (tenant_id, email);


--
-- Name: tenant_notification_contacts uk_tenant_notification_contacts; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_notification_contacts
    ADD CONSTRAINT uk_tenant_notification_contacts UNIQUE (tenant_id, purpose, phone_number);


--
-- Name: tenant_users uk_tenant_users_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_users
    ADD CONSTRAINT uk_tenant_users_email UNIQUE (email);


--
-- Name: timesheets uk_timesheet_resource_day; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timesheets
    ADD CONSTRAINT uk_timesheet_resource_day UNIQUE (project_id, resource_id, work_date);


--
-- Name: document_transmittals uk_transmittal_project_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittals
    ADD CONSTRAINT uk_transmittal_project_no UNIQUE (project_id, transmittal_no);


--
-- Name: vehicles uk_vehicles_tenant_plate; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vehicles
    ADD CONSTRAINT uk_vehicles_tenant_plate UNIQUE (tenant_id, plate_number);


--
-- Name: early_warning_signals uk_warning_snapshot_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.early_warning_signals
    ADD CONSTRAINT uk_warning_snapshot_code UNIQUE (forecast_snapshot_id, signal_code);


--
-- Name: whatsapp_button_replies uk_whatsapp_button_replies; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_button_replies
    ADD CONSTRAINT uk_whatsapp_button_replies UNIQUE (tenant_id, button_id);


--
-- Name: whatsapp_flow_registry uk_whatsapp_flow_registry; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_flow_registry
    ADD CONSTRAINT uk_whatsapp_flow_registry UNIQUE (tenant_id, flow_key);


--
-- Name: whatsapp_message_templates uk_whatsapp_message_templates; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_message_templates
    ADD CONSTRAINT uk_whatsapp_message_templates UNIQUE (tenant_id, template_code, language_code);


--
-- Name: document_control_workflows uk_workflow_tenant_type; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_control_workflows
    ADD CONSTRAINT uk_workflow_tenant_type UNIQUE (tenant_id, doc_type);


--
-- Name: vehicles vehicles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vehicles
    ADD CONSTRAINT vehicles_pkey PRIMARY KEY (id);


--
-- Name: video_scripts video_scripts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_scripts
    ADD CONSTRAINT video_scripts_pkey PRIMARY KEY (id);


--
-- Name: video_templates video_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_templates
    ADD CONSTRAINT video_templates_pkey PRIMARY KEY (id);


--
-- Name: webhook_outbox webhook_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhook_outbox
    ADD CONSTRAINT webhook_outbox_pkey PRIMARY KEY (id);


--
-- Name: whatsapp_button_replies whatsapp_button_replies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_button_replies
    ADD CONSTRAINT whatsapp_button_replies_pkey PRIMARY KEY (id);


--
-- Name: whatsapp_flow_registry whatsapp_flow_registry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_flow_registry
    ADD CONSTRAINT whatsapp_flow_registry_pkey PRIMARY KEY (id);


--
-- Name: whatsapp_flow_submissions whatsapp_flow_submissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_flow_submissions
    ADD CONSTRAINT whatsapp_flow_submissions_pkey PRIMARY KEY (id);


--
-- Name: whatsapp_interactive_messages whatsapp_interactive_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_interactive_messages
    ADD CONSTRAINT whatsapp_interactive_messages_pkey PRIMARY KEY (id);


--
-- Name: whatsapp_message_templates whatsapp_message_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_message_templates
    ADD CONSTRAINT whatsapp_message_templates_pkey PRIMARY KEY (id);


--
-- Name: whatsapp_order_items whatsapp_order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_order_items
    ADD CONSTRAINT whatsapp_order_items_pkey PRIMARY KEY (id);


--
-- Name: whatsapp_orders whatsapp_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_orders
    ADD CONSTRAINT whatsapp_orders_pkey PRIMARY KEY (id);


--
-- Name: whatsapp_template_send_audit whatsapp_template_send_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_template_send_audit
    ADD CONSTRAINT whatsapp_template_send_audit_pkey PRIMARY KEY (id);


--
-- Name: workflow_in_app_notifications workflow_in_app_notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_in_app_notifications
    ADD CONSTRAINT workflow_in_app_notifications_pkey PRIMARY KEY (id);


--
-- Name: workflow_notification_deliveries workflow_notification_deliveries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_deliveries
    ADD CONSTRAINT workflow_notification_deliveries_pkey PRIMARY KEY (id);


--
-- Name: workflow_notification_outbox workflow_notification_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_pkey PRIMARY KEY (id);


--
-- Name: idx_actual_cost_budget_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_actual_cost_budget_line ON public.actual_cost_entries USING btree (budget_line_id);


--
-- Name: idx_actual_cost_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_actual_cost_org ON public.actual_cost_entries USING btree (organization_id);


--
-- Name: idx_actual_cost_project_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_actual_cost_project_date ON public.actual_cost_entries USING btree (project_id, cost_date);


--
-- Name: idx_analytics_snapshots_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analytics_snapshots_job ON public.analytics_snapshots USING btree (publish_job_id);


--
-- Name: idx_analytics_snapshots_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_analytics_snapshots_tenant ON public.analytics_snapshots USING btree (tenant_id, captured_at DESC);


--
-- Name: idx_approval_parallel_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_parallel_group ON public.document_approval_steps USING btree (approval_id, parallel_group, step_index) WHERE (decision IS NULL);


--
-- Name: idx_approval_steps_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_steps_due ON public.document_approval_steps USING btree (due_at) WHERE (decision IS NULL);


--
-- Name: idx_approval_steps_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_steps_org ON public.document_approval_steps USING btree (assignment_organization_id) WHERE (decision IS NULL);


--
-- Name: idx_approval_steps_party; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_steps_party ON public.document_approval_steps USING btree (assignment_party_role) WHERE (decision IS NULL);


--
-- Name: idx_approval_tasks_idea; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_tasks_idea ON public.approval_tasks USING btree (content_idea_id);


--
-- Name: idx_approval_tasks_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_tasks_tenant_status ON public.approval_tasks USING btree (tenant_id, status);


--
-- Name: idx_bg_jobs_locked; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bg_jobs_locked ON public.background_jobs USING btree (locked_by) WHERE (locked_by IS NOT NULL);


--
-- Name: idx_bg_jobs_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bg_jobs_pending ON public.background_jobs USING btree (status, priority DESC, run_after) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RETRYING'::character varying])::text[]));


--
-- Name: idx_bg_jobs_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bg_jobs_tenant ON public.background_jobs USING btree (tenant_id, job_type);


--
-- Name: idx_budget_lines_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_budget_lines_parent ON public.budget_lines USING btree (parent_line_id);


--
-- Name: idx_budget_lines_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_budget_lines_version ON public.budget_lines USING btree (budget_version_id, sort_order);


--
-- Name: idx_budget_versions_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_budget_versions_org ON public.budget_versions USING btree (project_id, organization_id, status);


--
-- Name: idx_budget_versions_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_budget_versions_project ON public.budget_versions USING btree (project_id, status);


--
-- Name: idx_campaigns_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_campaigns_tenant_status ON public.campaigns USING btree (tenant_id, status);


--
-- Name: idx_canned_responses_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_canned_responses_tenant_active ON public.canned_responses USING btree (tenant_id, active);


--
-- Name: idx_commitments_budget_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commitments_budget_line ON public.project_commitments USING btree (budget_line_id);


--
-- Name: idx_commitments_project_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commitments_project_org ON public.project_commitments USING btree (project_id, organization_id, status);


--
-- Name: idx_consultant_kpi_project_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consultant_kpi_project_date ON public.consultant_kpi_snapshots USING btree (project_id, snapshot_date DESC);


--
-- Name: idx_contacts_tenant_wa_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contacts_tenant_wa_id ON public.contacts USING btree (tenant_id, wa_id);


--
-- Name: idx_content_ideas_campaign; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_content_ideas_campaign ON public.content_ideas USING btree (campaign_id);


--
-- Name: idx_content_ideas_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_content_ideas_tenant_status ON public.content_ideas USING btree (tenant_id, status);


--
-- Name: idx_content_items_tenant_sched; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_content_items_tenant_sched ON public.content_items USING btree (tenant_id, scheduled_at) WHERE (scheduled_at IS NOT NULL);


--
-- Name: idx_content_items_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_content_items_tenant_status ON public.content_items USING btree (tenant_id, status);


--
-- Name: idx_content_variants_idea; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_content_variants_idea ON public.content_variants USING btree (content_idea_id);


--
-- Name: idx_control_forecast_project_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_control_forecast_project_date ON public.control_forecast_snapshots USING btree (project_id, snapshot_date DESC);


--
-- Name: idx_conversation_events_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversation_events_conversation ON public.conversation_events USING btree (conversation_id, created_at DESC);


--
-- Name: idx_conversation_events_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversation_events_tenant ON public.conversation_events USING btree (tenant_id, created_at DESC);


--
-- Name: idx_conversations_last_message_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversations_last_message_at ON public.conversations USING btree (last_message_at DESC);


--
-- Name: idx_conversations_tenant_last_message_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversations_tenant_last_message_at ON public.conversations USING btree (tenant_id, last_message_at DESC);


--
-- Name: idx_conversations_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversations_tenant_status ON public.conversations USING btree (tenant_id, status);


--
-- Name: idx_dcw_tenant_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcw_tenant_type ON public.document_control_workflows USING btree (tenant_id, doc_type);


--
-- Name: idx_doc_access_grants_doc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_access_grants_doc ON public.document_access_grants USING btree (document_id, permission_code);


--
-- Name: idx_doc_access_grants_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_access_grants_tenant ON public.document_access_grants USING btree (tenant_id);


--
-- Name: idx_doc_access_grants_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_access_grants_user ON public.document_access_grants USING btree (user_id, permission_code);


--
-- Name: idx_doc_approvals_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_approvals_document ON public.document_approvals USING btree (document_id);


--
-- Name: idx_doc_audit_events_doc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_audit_events_doc ON public.document_audit_events USING btree (document_id, created_at DESC);


--
-- Name: idx_doc_audit_events_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_audit_events_tenant ON public.document_audit_events USING btree (tenant_id, created_at DESC);


--
-- Name: idx_doc_comments_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_comments_document ON public.document_comments USING btree (document_id);


--
-- Name: idx_doc_enc_meta_asset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_enc_meta_asset ON public.document_encryption_metadata USING btree (asset_id);


--
-- Name: idx_doc_enc_meta_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_enc_meta_tenant ON public.document_encryption_metadata USING btree (tenant_id);


--
-- Name: idx_doc_versions_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_versions_document ON public.document_versions USING btree (document_id);


--
-- Name: idx_document_grants_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_grants_lookup ON public.document_access_grants USING btree (document_id, permission_code, user_id, organization_id, role_code);


--
-- Name: idx_document_grants_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_grants_org ON public.document_access_grants USING btree (document_id, organization_id, permission_code);


--
-- Name: idx_documents_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_due ON public.documents USING btree (due_at) WHERE (due_at IS NOT NULL);


--
-- Name: idx_documents_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_project ON public.documents USING btree (project_id, status);


--
-- Name: idx_documents_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_status ON public.documents USING btree (status);


--
-- Name: idx_documents_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_tenant ON public.documents USING btree (tenant_id);


--
-- Name: idx_documents_tenant_type_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_tenant_type_updated ON public.documents USING btree (tenant_id, doc_type, updated_at DESC);


--
-- Name: idx_documents_tenant_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_tenant_updated ON public.documents USING btree (tenant_id, updated_at DESC);


--
-- Name: idx_equipment_usage_project_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_equipment_usage_project_date ON public.equipment_usage USING btree (project_id, usage_date, status);


--
-- Name: idx_feature_api_path_feature; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feature_api_path_feature ON public.feature_api_path USING btree (feature_code);


--
-- Name: idx_feature_catalog_module; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feature_catalog_module ON public.feature_catalog USING btree (module, sort_order);


--
-- Name: idx_forecast_snapshots_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_forecast_snapshots_project ON public.forecast_snapshots USING btree (project_id, snapshot_date DESC);


--
-- Name: idx_forecast_snapshots_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_forecast_snapshots_source ON public.forecast_snapshots USING btree (source_organization_id, snapshot_date DESC);


--
-- Name: idx_in_app_notification_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_in_app_notification_user ON public.workflow_in_app_notifications USING btree (tenant_id, user_id, read_at, created_at DESC);


--
-- Name: idx_knowledge_documents_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_documents_tenant_active ON public.knowledge_documents USING btree (tenant_id, active);


--
-- Name: idx_knowledge_embeddings_embedding_hnsw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_embeddings_embedding_hnsw ON public.knowledge_embeddings USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: idx_knowledge_embeddings_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_embeddings_tenant ON public.knowledge_embeddings USING btree (tenant_id);


--
-- Name: idx_lead_signals_contact; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lead_signals_contact ON public.lead_signals USING btree (contact_id);


--
-- Name: idx_lead_signals_tenant_captured; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lead_signals_tenant_captured ON public.lead_signals USING btree (tenant_id, captured_at DESC);


--
-- Name: idx_lead_signals_tenant_score; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lead_signals_tenant_score ON public.lead_signals USING btree (tenant_id, score DESC);


--
-- Name: idx_learning_insights_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_learning_insights_tenant ON public.learning_insights USING btree (tenant_id, generated_at DESC);


--
-- Name: idx_material_receipts_budget_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_receipts_budget_line ON public.material_receipts USING btree (budget_line_id);


--
-- Name: idx_material_receipts_project_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_receipts_project_date ON public.material_receipts USING btree (project_id, receipt_date, status);


--
-- Name: idx_media_assets_object_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_media_assets_object_key ON public.media_assets USING btree (storage_provider, object_key);


--
-- Name: idx_media_assets_ref_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_media_assets_ref_id ON public.media_assets USING btree (ref_id);


--
-- Name: idx_media_assets_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_media_assets_tenant_id ON public.media_assets USING btree (tenant_id);


--
-- Name: idx_media_assets_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_media_assets_tenant_status ON public.media_assets USING btree (tenant_id, status);


--
-- Name: idx_media_assets_tenant_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_media_assets_tenant_type ON public.media_assets USING btree (tenant_id, asset_type);


--
-- Name: idx_messages_conversation_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_conversation_created_at ON public.messages USING btree (conversation_id, created_at DESC);


--
-- Name: idx_messages_tenant_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_tenant_created_at ON public.messages USING btree (tenant_id, created_at DESC);


--
-- Name: idx_notification_delivery_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_delivery_pending ON public.workflow_notification_deliveries USING btree (status, next_attempt_at, created_at);


--
-- Name: idx_notification_delivery_retry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_delivery_retry ON public.workflow_notification_deliveries USING btree (status, next_attempt_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying])::text[]));


--
-- Name: idx_notification_delivery_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_delivery_tenant ON public.workflow_notification_deliveries USING btree (tenant_id, created_at DESC);


--
-- Name: idx_organizations_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_organizations_tenant ON public.organizations USING btree (tenant_id, active);


--
-- Name: idx_participants_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_participants_org ON public.project_participants USING btree (organization_id);


--
-- Name: idx_participants_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_participants_parent ON public.project_participants USING btree (parent_participant_id);


--
-- Name: idx_participants_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_participants_project ON public.project_participants USING btree (project_id, active);


--
-- Name: idx_payment_apps_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_apps_org ON public.payment_applications USING btree (claimed_by_org_id);


--
-- Name: idx_payment_apps_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_apps_project ON public.payment_applications USING btree (project_id, status);


--
-- Name: idx_payment_audit_events_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_audit_events_app ON public.payment_audit_events USING btree (payment_application_id, created_at DESC);


--
-- Name: idx_payment_items_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_items_app ON public.payment_application_items USING btree (payment_application_id);


--
-- Name: idx_payment_items_doc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_items_doc ON public.payment_application_items USING btree (document_id);


--
-- Name: idx_payment_items_document_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_items_document_lookup ON public.payment_application_items USING btree (document_id) WHERE (document_id IS NOT NULL);


--
-- Name: idx_permission_audit_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_permission_audit_document ON public.permission_audit_events USING btree (tenant_id, document_id, created_at DESC);


--
-- Name: idx_permission_audit_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_permission_audit_project ON public.permission_audit_events USING btree (tenant_id, project_id, created_at DESC);


--
-- Name: idx_plan_features_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_features_plan ON public.plan_features USING btree (plan_code, enabled);


--
-- Name: idx_platform_accounts_tenant_platform; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_platform_accounts_tenant_platform ON public.platform_accounts USING btree (tenant_id, platform_code, active);


--
-- Name: idx_product_categories_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_categories_tenant_active ON public.product_categories USING btree (tenant_id, active);


--
-- Name: idx_products_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_products_tenant_active ON public.products USING btree (tenant_id, active);


--
-- Name: idx_products_tenant_category_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_products_tenant_category_active ON public.products USING btree (tenant_id, category_id, active);


--
-- Name: idx_project_capability_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_capability_project ON public.project_capability_overrides USING btree (tenant_id, project_id);


--
-- Name: idx_project_contracts_participant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_contracts_participant ON public.project_contracts USING btree (participant_id);


--
-- Name: idx_project_contracts_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_contracts_project ON public.project_contracts USING btree (project_id, status);


--
-- Name: idx_project_resources_project_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_resources_project_org ON public.project_resources USING btree (project_id, organization_id, active);


--
-- Name: idx_projects_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_projects_tenant ON public.projects USING btree (tenant_id, status);


--
-- Name: idx_publish_jobs_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_publish_jobs_due ON public.publish_jobs USING btree (status, scheduled_at) WHERE ((status)::text = 'SCHEDULED'::text);


--
-- Name: idx_publish_jobs_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_publish_jobs_tenant_status ON public.publish_jobs USING btree (tenant_id, status);


--
-- Name: idx_publish_results_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_publish_results_job ON public.publish_results USING btree (job_id);


--
-- Name: idx_publishing_jobs_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_publishing_jobs_due ON public.publishing_jobs USING btree (status, scheduled_at) WHERE ((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'RETRYING'::character varying])::text[]));


--
-- Name: idx_publishing_jobs_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_publishing_jobs_tenant_status ON public.publishing_jobs USING btree (tenant_id, status);


--
-- Name: idx_render_jobs_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_render_jobs_pending ON public.render_jobs USING btree (status, created_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying])::text[]));


--
-- Name: idx_render_jobs_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_render_jobs_tenant_status ON public.render_jobs USING btree (tenant_id, status);


--
-- Name: idx_resource_rates_effective; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resource_rates_effective ON public.resource_rates USING btree (resource_id, effective_from, effective_to);


--
-- Name: idx_saved_trends_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saved_trends_tenant ON public.tenant_saved_trends USING btree (tenant_id, status);


--
-- Name: idx_service_appointments_contact; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_appointments_contact ON public.service_appointments USING btree (contact_id);


--
-- Name: idx_service_appointments_tenant_date_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_appointments_tenant_date_status ON public.service_appointments USING btree (tenant_id, appointment_date, status);


--
-- Name: idx_service_appointments_vehicle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_appointments_vehicle ON public.service_appointments USING btree (vehicle_id);


--
-- Name: idx_service_records_contact_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_records_contact_date ON public.service_records USING btree (contact_id, service_date DESC);


--
-- Name: idx_service_records_tenant_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_records_tenant_type ON public.service_records USING btree (tenant_id, service_type);


--
-- Name: idx_service_records_vehicle_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_records_vehicle_date ON public.service_records USING btree (vehicle_id, service_date DESC);


--
-- Name: idx_social_accounts_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_social_accounts_tenant ON public.social_accounts USING btree (tenant_id, platform, status);


--
-- Name: idx_tenant_agents_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_agents_tenant_active ON public.tenant_agents USING btree (tenant_id, active);


--
-- Name: idx_tenant_features_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_features_tenant_code ON public.tenant_features USING btree (tenant_id, feature_code, enabled);


--
-- Name: idx_tenant_notification_contacts_tenant_purpose; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_notification_contacts_tenant_purpose ON public.tenant_notification_contacts USING btree (tenant_id, purpose, active);


--
-- Name: idx_tenant_subscriptions_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_subscriptions_status ON public.tenant_subscriptions USING btree (status, expires_at);


--
-- Name: idx_tenant_subscriptions_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_subscriptions_tenant ON public.tenant_subscriptions USING btree (tenant_id, status);


--
-- Name: idx_tenant_usage_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_usage_tenant_date ON public.tenant_usage_daily USING btree (tenant_id, usage_date DESC);


--
-- Name: idx_tenant_users_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_users_email ON public.tenant_users USING btree (email);


--
-- Name: idx_tenant_users_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_users_org ON public.tenant_users USING btree (organization_id);


--
-- Name: idx_tenant_users_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_users_tenant_id ON public.tenant_users USING btree (tenant_id);


--
-- Name: idx_tenants_phone_number_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenants_phone_number_id ON public.tenants USING btree (phone_number_id);


--
-- Name: idx_timesheets_project_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_timesheets_project_date ON public.timesheets USING btree (project_id, work_date, status);


--
-- Name: idx_transmittal_items_document; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transmittal_items_document ON public.document_transmittal_items USING btree (document_id, document_version_id);


--
-- Name: idx_transmittal_recipients_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transmittal_recipients_org ON public.document_transmittal_recipients USING btree (recipient_organization_id, acknowledged_at);


--
-- Name: idx_transmittals_project_sender; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_transmittals_project_sender ON public.document_transmittals USING btree (project_id, sender_organization_id, created_at DESC);


--
-- Name: idx_trend_items_niche; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trend_items_niche ON public.trend_items USING btree (niche, score DESC);


--
-- Name: idx_trend_items_platform_score; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trend_items_platform_score ON public.trend_items USING btree (source_platform, score DESC);


--
-- Name: idx_trend_signals_tenant_captured; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trend_signals_tenant_captured ON public.trend_signals USING btree (tenant_id, captured_at DESC);


--
-- Name: idx_trend_signals_tenant_score; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trend_signals_tenant_score ON public.trend_signals USING btree (tenant_id, final_score DESC);


--
-- Name: idx_trend_sources_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trend_sources_tenant ON public.trend_sources USING btree (tenant_id, active);


--
-- Name: idx_upload_link_events_link; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_link_events_link ON public.document_upload_link_events USING btree (link_id, created_at DESC);


--
-- Name: idx_upload_link_sessions_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_link_sessions_token ON public.document_upload_link_sessions USING btree (token);


--
-- Name: idx_upload_links_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_links_tenant ON public.document_upload_links USING btree (tenant_id);


--
-- Name: idx_upload_links_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_links_token ON public.document_upload_links USING btree (token);


--
-- Name: idx_upload_tokens_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_tokens_tenant ON public.storage_upload_tokens USING btree (tenant_id);


--
-- Name: idx_upload_tokens_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_tokens_token ON public.storage_upload_tokens USING btree (token, used, expires_at);


--
-- Name: idx_variations_budget_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_variations_budget_line ON public.project_variations USING btree (budget_line_id);


--
-- Name: idx_variations_project_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_variations_project_status ON public.project_variations USING btree (project_id, status);


--
-- Name: idx_vehicles_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vehicles_tenant_active ON public.vehicles USING btree (tenant_id, active);


--
-- Name: idx_vehicles_tenant_contact; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vehicles_tenant_contact ON public.vehicles USING btree (tenant_id, contact_id);


--
-- Name: idx_video_scripts_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_video_scripts_status ON public.video_scripts USING btree (tenant_id, status);


--
-- Name: idx_video_scripts_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_video_scripts_tenant ON public.video_scripts USING btree (tenant_id);


--
-- Name: idx_video_templates_format; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_video_templates_format ON public.video_templates USING btree (format, scope, active);


--
-- Name: idx_video_templates_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_video_templates_scope ON public.video_templates USING btree (scope, active);


--
-- Name: idx_video_templates_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_video_templates_tenant ON public.video_templates USING btree (tenant_id, active);


--
-- Name: idx_warning_project_severity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_warning_project_severity ON public.early_warning_signals USING btree (project_id, severity, created_at DESC);


--
-- Name: idx_webhook_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_webhook_outbox_pending ON public.webhook_outbox USING btree (created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_webhook_outbox_processing; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_webhook_outbox_processing ON public.webhook_outbox USING btree (created_at) WHERE ((status)::text = 'PROCESSING'::text);


--
-- Name: idx_whatsapp_button_replies_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_button_replies_tenant_active ON public.whatsapp_button_replies USING btree (tenant_id, active);


--
-- Name: idx_whatsapp_flow_registry_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_flow_registry_tenant_active ON public.whatsapp_flow_registry USING btree (tenant_id, active);


--
-- Name: idx_whatsapp_flow_submissions_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_flow_submissions_conversation ON public.whatsapp_flow_submissions USING btree (conversation_id, created_at DESC);


--
-- Name: idx_whatsapp_flow_submissions_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_flow_submissions_tenant ON public.whatsapp_flow_submissions USING btree (tenant_id, created_at DESC);


--
-- Name: idx_whatsapp_interactive_contact; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_interactive_contact ON public.whatsapp_interactive_messages USING btree (contact_id, created_at DESC);


--
-- Name: idx_whatsapp_interactive_tenant_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_interactive_tenant_conversation ON public.whatsapp_interactive_messages USING btree (tenant_id, conversation_id, created_at DESC);


--
-- Name: idx_whatsapp_message_templates_ai; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_message_templates_ai ON public.whatsapp_message_templates USING btree (tenant_id, enabled_for_ai, active);


--
-- Name: idx_whatsapp_order_items_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_order_items_order ON public.whatsapp_order_items USING btree (whatsapp_order_id);


--
-- Name: idx_whatsapp_orders_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_orders_conversation ON public.whatsapp_orders USING btree (conversation_id, created_at DESC);


--
-- Name: idx_whatsapp_orders_tenant_contact; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_orders_tenant_contact ON public.whatsapp_orders USING btree (tenant_id, contact_id, created_at DESC);


--
-- Name: idx_whatsapp_template_send_audit_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_template_send_audit_conversation ON public.whatsapp_template_send_audit USING btree (conversation_id, created_at DESC);


--
-- Name: idx_whatsapp_template_send_audit_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_whatsapp_template_send_audit_tenant ON public.whatsapp_template_send_audit USING btree (tenant_id, created_at DESC);


--
-- Name: idx_workflow_notification_claimed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_workflow_notification_claimed ON public.workflow_notification_outbox USING btree (status, claimed_at);


--
-- Name: idx_workflow_notification_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_workflow_notification_pending ON public.workflow_notification_outbox USING btree (tenant_id, status, created_at);


--
-- Name: uk_budget_project_scope_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_budget_project_scope_version ON public.budget_versions USING btree (project_id, COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid), version_no);


--
-- Name: uk_document_revision_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_document_revision_code ON public.document_versions USING btree (document_id, revision_code);


--
-- Name: uk_documents_project_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_documents_project_code ON public.documents USING btree (project_id, document_code) WHERE ((project_id IS NOT NULL) AND (document_code IS NOT NULL));


--
-- Name: uk_notification_approval_result; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_notification_approval_result ON public.workflow_notification_outbox USING btree (approval_id, event_type, target_user_id) WHERE ((approval_id IS NOT NULL) AND (approval_step_id IS NULL) AND (target_user_id IS NOT NULL));


--
-- Name: uk_notification_transmittal_issued; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_notification_transmittal_issued ON public.workflow_notification_outbox USING btree (transmittal_id, event_type, target_organization_id) WHERE ((transmittal_id IS NOT NULL) AND ((event_type)::text = 'TRANSMITTAL_ISSUED'::text) AND (target_organization_id IS NOT NULL));


--
-- Name: uk_workflow_notification_step_event; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_workflow_notification_step_event ON public.workflow_notification_outbox USING btree (approval_step_id, event_type, COALESCE(target_user_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(target_organization_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(target_party_role, ''::character varying));


--
-- Name: ux_knowledge_documents_seed_title; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_knowledge_documents_seed_title ON public.knowledge_documents USING btree (tenant_id, title, source_type) WHERE ((metadata ->> 'seed'::text) = 'true'::text);


--
-- Name: ux_messages_wa_message_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_messages_wa_message_id ON public.messages USING btree (wa_message_id) WHERE (wa_message_id IS NOT NULL);


--
-- Name: material_receipts material_receipts_actual_refresh; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER material_receipts_actual_refresh AFTER INSERT OR DELETE OR UPDATE ON public.material_receipts FOR EACH ROW EXECUTE FUNCTION public.trg_refresh_material_actual();


--
-- Name: project_commitments project_commitments_budget_refresh; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER project_commitments_budget_refresh AFTER INSERT OR DELETE OR UPDATE ON public.project_commitments FOR EACH ROW EXECUTE FUNCTION public.trg_refresh_commitment_budget();


--
-- Name: project_variations project_variations_budget_refresh; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER project_variations_budget_refresh AFTER INSERT OR DELETE OR UPDATE ON public.project_variations FOR EACH ROW EXECUTE FUNCTION public.trg_refresh_variation_budget();


--
-- Name: actual_cost_entries trg_actual_cost_budget_rollup; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_actual_cost_budget_rollup AFTER INSERT ON public.actual_cost_entries FOR EACH ROW EXECUTE FUNCTION public.apply_actual_cost_to_budget_line();


--
-- Name: document_approvals trg_notify_approval_transition; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_notify_approval_transition AFTER UPDATE OF current_step, status ON public.document_approvals FOR EACH ROW EXECUTE FUNCTION public.notify_approval_transition();


--
-- Name: document_approval_steps trg_notify_new_approval_step; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_notify_new_approval_step AFTER INSERT ON public.document_approval_steps FOR EACH ROW EXECUTE FUNCTION public.notify_new_approval_step();


--
-- Name: document_transmittal_recipients trg_notify_transmittal_acknowledgement; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_notify_transmittal_acknowledgement AFTER UPDATE OF acknowledged_at ON public.document_transmittal_recipients FOR EACH ROW EXECUTE FUNCTION public.notify_transmittal_acknowledgement();


--
-- Name: document_transmittals trg_notify_transmittal_transition; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_notify_transmittal_transition AFTER UPDATE OF status ON public.document_transmittals FOR EACH ROW EXECUTE FUNCTION public.notify_transmittal_transition();


--
-- Name: document_approval_steps trg_start_initial_parallel_step_sla; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_start_initial_parallel_step_sla BEFORE INSERT ON public.document_approval_steps FOR EACH ROW EXECUTE FUNCTION public.start_initial_parallel_step_sla();


--
-- Name: actual_cost_entries actual_cost_entries_budget_line_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actual_cost_entries
    ADD CONSTRAINT actual_cost_entries_budget_line_id_fkey FOREIGN KEY (budget_line_id) REFERENCES public.budget_lines(id) ON DELETE SET NULL;


--
-- Name: actual_cost_entries actual_cost_entries_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actual_cost_entries
    ADD CONSTRAINT actual_cost_entries_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: actual_cost_entries actual_cost_entries_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actual_cost_entries
    ADD CONSTRAINT actual_cost_entries_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: actual_cost_entries actual_cost_entries_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actual_cost_entries
    ADD CONSTRAINT actual_cost_entries_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.project_resources(id) ON DELETE SET NULL;


--
-- Name: actual_cost_entries actual_cost_entries_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actual_cost_entries
    ADD CONSTRAINT actual_cost_entries_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: analytics_snapshots analytics_snapshots_publish_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analytics_snapshots
    ADD CONSTRAINT analytics_snapshots_publish_job_id_fkey FOREIGN KEY (publish_job_id) REFERENCES public.publish_jobs(id);


--
-- Name: analytics_snapshots analytics_snapshots_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analytics_snapshots
    ADD CONSTRAINT analytics_snapshots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: approval_tasks approval_tasks_content_idea_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_tasks
    ADD CONSTRAINT approval_tasks_content_idea_id_fkey FOREIGN KEY (content_idea_id) REFERENCES public.content_ideas(id);


--
-- Name: approval_tasks approval_tasks_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_tasks
    ADD CONSTRAINT approval_tasks_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: background_jobs background_jobs_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.background_jobs
    ADD CONSTRAINT background_jobs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: budget_lines budget_lines_budget_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_lines
    ADD CONSTRAINT budget_lines_budget_version_id_fkey FOREIGN KEY (budget_version_id) REFERENCES public.budget_versions(id) ON DELETE CASCADE;


--
-- Name: budget_lines budget_lines_parent_line_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_lines
    ADD CONSTRAINT budget_lines_parent_line_id_fkey FOREIGN KEY (parent_line_id) REFERENCES public.budget_lines(id) ON DELETE CASCADE;


--
-- Name: budget_lines budget_lines_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_lines
    ADD CONSTRAINT budget_lines_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: budget_lines budget_lines_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_lines
    ADD CONSTRAINT budget_lines_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: budget_versions budget_versions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_versions
    ADD CONSTRAINT budget_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: budget_versions budget_versions_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_versions
    ADD CONSTRAINT budget_versions_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: budget_versions budget_versions_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_versions
    ADD CONSTRAINT budget_versions_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: budget_versions budget_versions_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_versions
    ADD CONSTRAINT budget_versions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: campaigns campaigns_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaigns
    ADD CONSTRAINT campaigns_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: canned_responses canned_responses_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.canned_responses
    ADD CONSTRAINT canned_responses_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: consultant_kpi_snapshots consultant_kpi_snapshots_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultant_kpi_snapshots
    ADD CONSTRAINT consultant_kpi_snapshots_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: consultant_kpi_snapshots consultant_kpi_snapshots_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultant_kpi_snapshots
    ADD CONSTRAINT consultant_kpi_snapshots_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: consultant_kpi_snapshots consultant_kpi_snapshots_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultant_kpi_snapshots
    ADD CONSTRAINT consultant_kpi_snapshots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: contacts contacts_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: content_ideas content_ideas_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_ideas
    ADD CONSTRAINT content_ideas_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.campaigns(id);


--
-- Name: content_ideas content_ideas_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_ideas
    ADD CONSTRAINT content_ideas_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: content_items content_items_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_items
    ADD CONSTRAINT content_items_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: content_items content_items_final_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_items
    ADD CONSTRAINT content_items_final_asset_id_fkey FOREIGN KEY (final_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;


--
-- Name: content_items content_items_source_trend_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_items
    ADD CONSTRAINT content_items_source_trend_id_fkey FOREIGN KEY (source_trend_id) REFERENCES public.trend_items(id) ON DELETE SET NULL;


--
-- Name: content_items content_items_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_items
    ADD CONSTRAINT content_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: content_variants content_variants_content_idea_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_variants
    ADD CONSTRAINT content_variants_content_idea_id_fkey FOREIGN KEY (content_idea_id) REFERENCES public.content_ideas(id);


--
-- Name: content_variants content_variants_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.content_variants
    ADD CONSTRAINT content_variants_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: control_forecast_snapshots control_forecast_snapshots_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_forecast_snapshots
    ADD CONSTRAINT control_forecast_snapshots_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: control_forecast_snapshots control_forecast_snapshots_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_forecast_snapshots
    ADD CONSTRAINT control_forecast_snapshots_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: control_forecast_snapshots control_forecast_snapshots_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_forecast_snapshots
    ADD CONSTRAINT control_forecast_snapshots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: conversation_events conversation_events_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_events
    ADD CONSTRAINT conversation_events_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: conversation_events conversation_events_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_events
    ADD CONSTRAINT conversation_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: conversations conversations_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id);


--
-- Name: conversations conversations_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: document_access_grants document_access_grants_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_access_grants
    ADD CONSTRAINT document_access_grants_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: document_access_grants document_access_grants_granted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_access_grants
    ADD CONSTRAINT document_access_grants_granted_by_fkey FOREIGN KEY (granted_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_access_grants document_access_grants_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_access_grants
    ADD CONSTRAINT document_access_grants_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: document_access_grants document_access_grants_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_access_grants
    ADD CONSTRAINT document_access_grants_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_access_grants document_access_grants_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_access_grants
    ADD CONSTRAINT document_access_grants_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.tenant_users(id) ON DELETE CASCADE;


--
-- Name: document_approval_steps document_approval_steps_approval_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approval_steps
    ADD CONSTRAINT document_approval_steps_approval_id_fkey FOREIGN KEY (approval_id) REFERENCES public.document_approvals(id) ON DELETE CASCADE;


--
-- Name: document_approval_steps document_approval_steps_assignment_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approval_steps
    ADD CONSTRAINT document_approval_steps_assignment_organization_id_fkey FOREIGN KEY (assignment_organization_id) REFERENCES public.organizations(id) ON DELETE SET NULL;


--
-- Name: document_approval_steps document_approval_steps_reviewer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approval_steps
    ADD CONSTRAINT document_approval_steps_reviewer_id_fkey FOREIGN KEY (reviewer_id) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_approvals document_approvals_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: document_approvals document_approvals_initiated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_initiated_by_fkey FOREIGN KEY (initiated_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_approvals document_approvals_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_approvals document_approvals_workflow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES public.document_control_workflows(id) ON DELETE SET NULL;


--
-- Name: document_audit_events document_audit_events_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_audit_events
    ADD CONSTRAINT document_audit_events_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_audit_events document_audit_events_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_audit_events
    ADD CONSTRAINT document_audit_events_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: document_audit_events document_audit_events_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_audit_events
    ADD CONSTRAINT document_audit_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_comments document_comments_author_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_comments
    ADD CONSTRAINT document_comments_author_id_fkey FOREIGN KEY (author_id) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_comments document_comments_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_comments
    ADD CONSTRAINT document_comments_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: document_comments document_comments_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_comments
    ADD CONSTRAINT document_comments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_control_workflows document_control_workflows_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_control_workflows
    ADD CONSTRAINT document_control_workflows_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_encryption_metadata document_encryption_metadata_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_encryption_metadata
    ADD CONSTRAINT document_encryption_metadata_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.media_assets(id) ON DELETE CASCADE;


--
-- Name: document_encryption_metadata document_encryption_metadata_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_encryption_metadata
    ADD CONSTRAINT document_encryption_metadata_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_number_series document_number_series_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_number_series
    ADD CONSTRAINT document_number_series_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: document_number_series document_number_series_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_number_series
    ADD CONSTRAINT document_number_series_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_transmittal_items document_transmittal_items_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_items
    ADD CONSTRAINT document_transmittal_items_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id);


--
-- Name: document_transmittal_items document_transmittal_items_document_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_items
    ADD CONSTRAINT document_transmittal_items_document_version_id_fkey FOREIGN KEY (document_version_id) REFERENCES public.document_versions(id);


--
-- Name: document_transmittal_items document_transmittal_items_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_items
    ADD CONSTRAINT document_transmittal_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_transmittal_items document_transmittal_items_transmittal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_items
    ADD CONSTRAINT document_transmittal_items_transmittal_id_fkey FOREIGN KEY (transmittal_id) REFERENCES public.document_transmittals(id) ON DELETE CASCADE;


--
-- Name: document_transmittal_recipients document_transmittal_recipients_acknowledged_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_recipients
    ADD CONSTRAINT document_transmittal_recipients_acknowledged_by_fkey FOREIGN KEY (acknowledged_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_transmittal_recipients document_transmittal_recipients_recipient_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_recipients
    ADD CONSTRAINT document_transmittal_recipients_recipient_organization_id_fkey FOREIGN KEY (recipient_organization_id) REFERENCES public.organizations(id);


--
-- Name: document_transmittal_recipients document_transmittal_recipients_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_recipients
    ADD CONSTRAINT document_transmittal_recipients_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_transmittal_recipients document_transmittal_recipients_transmittal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittal_recipients
    ADD CONSTRAINT document_transmittal_recipients_transmittal_id_fkey FOREIGN KEY (transmittal_id) REFERENCES public.document_transmittals(id) ON DELETE CASCADE;


--
-- Name: document_transmittals document_transmittals_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittals
    ADD CONSTRAINT document_transmittals_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_transmittals document_transmittals_issued_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittals
    ADD CONSTRAINT document_transmittals_issued_by_fkey FOREIGN KEY (issued_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_transmittals document_transmittals_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittals
    ADD CONSTRAINT document_transmittals_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: document_transmittals document_transmittals_sender_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittals
    ADD CONSTRAINT document_transmittals_sender_organization_id_fkey FOREIGN KEY (sender_organization_id) REFERENCES public.organizations(id);


--
-- Name: document_transmittals document_transmittals_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_transmittals
    ADD CONSTRAINT document_transmittals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_upload_link_events document_upload_link_events_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_link_events
    ADD CONSTRAINT document_upload_link_events_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE SET NULL;


--
-- Name: document_upload_link_events document_upload_link_events_link_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_link_events
    ADD CONSTRAINT document_upload_link_events_link_id_fkey FOREIGN KEY (link_id) REFERENCES public.document_upload_links(id) ON DELETE CASCADE;


--
-- Name: document_upload_link_events document_upload_link_events_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_link_events
    ADD CONSTRAINT document_upload_link_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_upload_link_sessions document_upload_link_sessions_link_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_link_sessions
    ADD CONSTRAINT document_upload_link_sessions_link_id_fkey FOREIGN KEY (link_id) REFERENCES public.document_upload_links(id) ON DELETE CASCADE;


--
-- Name: document_upload_links document_upload_links_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_links
    ADD CONSTRAINT document_upload_links_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_upload_links document_upload_links_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_links
    ADD CONSTRAINT document_upload_links_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: document_upload_links document_upload_links_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_upload_links
    ADD CONSTRAINT document_upload_links_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: document_versions document_versions_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT document_versions_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;


--
-- Name: document_versions document_versions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT document_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_versions document_versions_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT document_versions_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: document_versions document_versions_issued_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT document_versions_issued_by_fkey FOREIGN KEY (issued_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: document_versions document_versions_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT document_versions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: documents documents_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: documents documents_issued_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_issued_by_fkey FOREIGN KEY (issued_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: documents documents_originator_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_originator_org_id_fkey FOREIGN KEY (originator_org_id) REFERENCES public.organizations(id) ON DELETE SET NULL;


--
-- Name: documents documents_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE SET NULL;


--
-- Name: documents documents_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: documents documents_upload_link_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_upload_link_id_fkey FOREIGN KEY (upload_link_id) REFERENCES public.document_upload_links(id) ON DELETE SET NULL;


--
-- Name: early_warning_signals early_warning_signals_forecast_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.early_warning_signals
    ADD CONSTRAINT early_warning_signals_forecast_snapshot_id_fkey FOREIGN KEY (forecast_snapshot_id) REFERENCES public.control_forecast_snapshots(id) ON DELETE CASCADE;


--
-- Name: early_warning_signals early_warning_signals_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.early_warning_signals
    ADD CONSTRAINT early_warning_signals_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: early_warning_signals early_warning_signals_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.early_warning_signals
    ADD CONSTRAINT early_warning_signals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: equipment_usage equipment_usage_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment_usage
    ADD CONSTRAINT equipment_usage_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: equipment_usage equipment_usage_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment_usage
    ADD CONSTRAINT equipment_usage_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: equipment_usage equipment_usage_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment_usage
    ADD CONSTRAINT equipment_usage_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: equipment_usage equipment_usage_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment_usage
    ADD CONSTRAINT equipment_usage_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.project_resources(id) ON DELETE CASCADE;


--
-- Name: equipment_usage equipment_usage_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment_usage
    ADD CONSTRAINT equipment_usage_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: feature_api_path feature_api_path_feature_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_api_path
    ADD CONSTRAINT feature_api_path_feature_code_fkey FOREIGN KEY (feature_code) REFERENCES public.feature_catalog(feature_code) ON DELETE CASCADE;


--
-- Name: documents fk_doc_workflow; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT fk_doc_workflow FOREIGN KEY (workflow_id) REFERENCES public.document_control_workflows(id) ON DELETE SET NULL;


--
-- Name: forecast_snapshots forecast_snapshots_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.forecast_snapshots
    ADD CONSTRAINT forecast_snapshots_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: forecast_snapshots forecast_snapshots_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.forecast_snapshots
    ADD CONSTRAINT forecast_snapshots_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: forecast_snapshots forecast_snapshots_source_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.forecast_snapshots
    ADD CONSTRAINT forecast_snapshots_source_organization_id_fkey FOREIGN KEY (source_organization_id) REFERENCES public.organizations(id) ON DELETE SET NULL;


--
-- Name: forecast_snapshots forecast_snapshots_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.forecast_snapshots
    ADD CONSTRAINT forecast_snapshots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: knowledge_documents knowledge_documents_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_documents
    ADD CONSTRAINT knowledge_documents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: knowledge_embeddings knowledge_embeddings_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_embeddings
    ADD CONSTRAINT knowledge_embeddings_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.knowledge_documents(id) ON DELETE CASCADE;


--
-- Name: knowledge_embeddings knowledge_embeddings_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_embeddings
    ADD CONSTRAINT knowledge_embeddings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: lead_signals lead_signals_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lead_signals
    ADD CONSTRAINT lead_signals_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id);


--
-- Name: lead_signals lead_signals_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lead_signals
    ADD CONSTRAINT lead_signals_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: lead_signals lead_signals_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lead_signals
    ADD CONSTRAINT lead_signals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: learning_insights learning_insights_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.learning_insights
    ADD CONSTRAINT learning_insights_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: material_receipts material_receipts_budget_line_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT material_receipts_budget_line_id_fkey FOREIGN KEY (budget_line_id) REFERENCES public.budget_lines(id) ON DELETE SET NULL;


--
-- Name: material_receipts material_receipts_commitment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT material_receipts_commitment_id_fkey FOREIGN KEY (commitment_id) REFERENCES public.project_commitments(id) ON DELETE SET NULL;


--
-- Name: material_receipts material_receipts_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT material_receipts_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: material_receipts material_receipts_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT material_receipts_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE SET NULL;


--
-- Name: material_receipts material_receipts_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT material_receipts_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: material_receipts material_receipts_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT material_receipts_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: material_receipts material_receipts_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_receipts
    ADD CONSTRAINT material_receipts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: media_assets media_assets_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_assets
    ADD CONSTRAINT media_assets_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: media_assets media_assets_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_assets
    ADD CONSTRAINT media_assets_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: media_assets media_assets_uploaded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_assets
    ADD CONSTRAINT media_assets_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: messages messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: messages messages_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: organizations organizations_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: payment_application_items payment_application_items_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_application_items
    ADD CONSTRAINT payment_application_items_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE SET NULL;


--
-- Name: payment_application_items payment_application_items_payment_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_application_items
    ADD CONSTRAINT payment_application_items_payment_application_id_fkey FOREIGN KEY (payment_application_id) REFERENCES public.payment_applications(id) ON DELETE CASCADE;


--
-- Name: payment_application_items payment_application_items_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_application_items
    ADD CONSTRAINT payment_application_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: payment_applications payment_applications_certified_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_applications
    ADD CONSTRAINT payment_applications_certified_by_fkey FOREIGN KEY (certified_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: payment_applications payment_applications_claimed_by_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_applications
    ADD CONSTRAINT payment_applications_claimed_by_org_id_fkey FOREIGN KEY (claimed_by_org_id) REFERENCES public.organizations(id);


--
-- Name: payment_applications payment_applications_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_applications
    ADD CONSTRAINT payment_applications_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: payment_applications payment_applications_paid_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_applications
    ADD CONSTRAINT payment_applications_paid_by_fkey FOREIGN KEY (paid_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: payment_applications payment_applications_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_applications
    ADD CONSTRAINT payment_applications_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: payment_applications payment_applications_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_applications
    ADD CONSTRAINT payment_applications_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: payment_audit_events payment_audit_events_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_audit_events
    ADD CONSTRAINT payment_audit_events_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: payment_audit_events payment_audit_events_payment_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_audit_events
    ADD CONSTRAINT payment_audit_events_payment_application_id_fkey FOREIGN KEY (payment_application_id) REFERENCES public.payment_applications(id) ON DELETE CASCADE;


--
-- Name: payment_audit_events payment_audit_events_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_audit_events
    ADD CONSTRAINT payment_audit_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: permission_audit_events permission_audit_events_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permission_audit_events
    ADD CONSTRAINT permission_audit_events_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: permission_audit_events permission_audit_events_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permission_audit_events
    ADD CONSTRAINT permission_audit_events_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: permission_audit_events permission_audit_events_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permission_audit_events
    ADD CONSTRAINT permission_audit_events_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: permission_audit_events permission_audit_events_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permission_audit_events
    ADD CONSTRAINT permission_audit_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: platform_accounts platform_accounts_platform_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_accounts
    ADD CONSTRAINT platform_accounts_platform_code_fkey FOREIGN KEY (platform_code) REFERENCES public.platforms(code);


--
-- Name: platform_accounts platform_accounts_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_accounts
    ADD CONSTRAINT platform_accounts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: product_categories product_categories_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_categories
    ADD CONSTRAINT product_categories_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.product_categories(id);


--
-- Name: product_categories product_categories_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_categories
    ADD CONSTRAINT product_categories_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: products products_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.product_categories(id);


--
-- Name: products products_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: project_capability_overrides project_capability_overrides_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_capability_overrides
    ADD CONSTRAINT project_capability_overrides_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: project_capability_overrides project_capability_overrides_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_capability_overrides
    ADD CONSTRAINT project_capability_overrides_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: project_capability_overrides project_capability_overrides_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_capability_overrides
    ADD CONSTRAINT project_capability_overrides_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: project_capability_overrides project_capability_overrides_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_capability_overrides
    ADD CONSTRAINT project_capability_overrides_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: project_commitments project_commitments_budget_line_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_commitments
    ADD CONSTRAINT project_commitments_budget_line_id_fkey FOREIGN KEY (budget_line_id) REFERENCES public.budget_lines(id) ON DELETE SET NULL;


--
-- Name: project_commitments project_commitments_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_commitments
    ADD CONSTRAINT project_commitments_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: project_commitments project_commitments_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_commitments
    ADD CONSTRAINT project_commitments_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: project_commitments project_commitments_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_commitments
    ADD CONSTRAINT project_commitments_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: project_commitments project_commitments_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_commitments
    ADD CONSTRAINT project_commitments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: project_contracts project_contracts_participant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_contracts
    ADD CONSTRAINT project_contracts_participant_id_fkey FOREIGN KEY (participant_id) REFERENCES public.project_participants(id) ON DELETE CASCADE;


--
-- Name: project_contracts project_contracts_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_contracts
    ADD CONSTRAINT project_contracts_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: project_contracts project_contracts_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_contracts
    ADD CONSTRAINT project_contracts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: project_participants project_participants_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_participants
    ADD CONSTRAINT project_participants_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: project_participants project_participants_parent_participant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_participants
    ADD CONSTRAINT project_participants_parent_participant_id_fkey FOREIGN KEY (parent_participant_id) REFERENCES public.project_participants(id) ON DELETE SET NULL;


--
-- Name: project_participants project_participants_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_participants
    ADD CONSTRAINT project_participants_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: project_participants project_participants_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_participants
    ADD CONSTRAINT project_participants_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: project_resources project_resources_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_resources
    ADD CONSTRAINT project_resources_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: project_resources project_resources_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_resources
    ADD CONSTRAINT project_resources_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: project_resources project_resources_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_resources
    ADD CONSTRAINT project_resources_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: project_resources project_resources_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_resources
    ADD CONSTRAINT project_resources_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: project_variations project_variations_approved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT project_variations_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: project_variations project_variations_budget_line_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT project_variations_budget_line_id_fkey FOREIGN KEY (budget_line_id) REFERENCES public.budget_lines(id) ON DELETE SET NULL;


--
-- Name: project_variations project_variations_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT project_variations_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: project_variations project_variations_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT project_variations_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE SET NULL;


--
-- Name: project_variations project_variations_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT project_variations_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: project_variations project_variations_source_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT project_variations_source_document_id_fkey FOREIGN KEY (source_document_id) REFERENCES public.documents(id) ON DELETE SET NULL;


--
-- Name: project_variations project_variations_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_variations
    ADD CONSTRAINT project_variations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: projects projects_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: publish_jobs publish_jobs_content_idea_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publish_jobs
    ADD CONSTRAINT publish_jobs_content_idea_id_fkey FOREIGN KEY (content_idea_id) REFERENCES public.content_ideas(id);


--
-- Name: publish_jobs publish_jobs_platform_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publish_jobs
    ADD CONSTRAINT publish_jobs_platform_account_id_fkey FOREIGN KEY (platform_account_id) REFERENCES public.platform_accounts(id);


--
-- Name: publish_jobs publish_jobs_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publish_jobs
    ADD CONSTRAINT publish_jobs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: publish_results publish_results_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publish_results
    ADD CONSTRAINT publish_results_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.publish_jobs(id);


--
-- Name: publish_results publish_results_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publish_results
    ADD CONSTRAINT publish_results_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: publishing_jobs publishing_jobs_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publishing_jobs
    ADD CONSTRAINT publishing_jobs_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;


--
-- Name: publishing_jobs publishing_jobs_content_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publishing_jobs
    ADD CONSTRAINT publishing_jobs_content_item_id_fkey FOREIGN KEY (content_item_id) REFERENCES public.content_items(id) ON DELETE CASCADE;


--
-- Name: publishing_jobs publishing_jobs_social_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publishing_jobs
    ADD CONSTRAINT publishing_jobs_social_account_id_fkey FOREIGN KEY (social_account_id) REFERENCES public.social_accounts(id) ON DELETE CASCADE;


--
-- Name: publishing_jobs publishing_jobs_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.publishing_jobs
    ADD CONSTRAINT publishing_jobs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: render_jobs render_jobs_content_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.render_jobs
    ADD CONSTRAINT render_jobs_content_item_id_fkey FOREIGN KEY (content_item_id) REFERENCES public.content_items(id) ON DELETE CASCADE;


--
-- Name: render_jobs render_jobs_output_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.render_jobs
    ADD CONSTRAINT render_jobs_output_asset_id_fkey FOREIGN KEY (output_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;


--
-- Name: render_jobs render_jobs_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.render_jobs
    ADD CONSTRAINT render_jobs_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.video_templates(id) ON DELETE SET NULL;


--
-- Name: render_jobs render_jobs_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.render_jobs
    ADD CONSTRAINT render_jobs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: resource_rates resource_rates_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_rates
    ADD CONSTRAINT resource_rates_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: resource_rates resource_rates_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_rates
    ADD CONSTRAINT resource_rates_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.project_resources(id) ON DELETE CASCADE;


--
-- Name: resource_rates resource_rates_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_rates
    ADD CONSTRAINT resource_rates_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: role_permissions role_permissions_feature_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_feature_code_fkey FOREIGN KEY (feature_code) REFERENCES public.feature_catalog(feature_code) ON DELETE CASCADE;


--
-- Name: service_appointments service_appointments_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_appointments
    ADD CONSTRAINT service_appointments_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id);


--
-- Name: service_appointments service_appointments_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_appointments
    ADD CONSTRAINT service_appointments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: service_appointments service_appointments_vehicle_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_appointments
    ADD CONSTRAINT service_appointments_vehicle_id_fkey FOREIGN KEY (vehicle_id) REFERENCES public.vehicles(id);


--
-- Name: service_records service_records_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_records
    ADD CONSTRAINT service_records_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id);


--
-- Name: service_records service_records_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_records
    ADD CONSTRAINT service_records_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: service_records service_records_vehicle_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_records
    ADD CONSTRAINT service_records_vehicle_id_fkey FOREIGN KEY (vehicle_id) REFERENCES public.vehicles(id);


--
-- Name: social_accounts social_accounts_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.social_accounts
    ADD CONSTRAINT social_accounts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: storage_upload_tokens storage_upload_tokens_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.storage_upload_tokens
    ADD CONSTRAINT storage_upload_tokens_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: tenant_agents tenant_agents_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_agents
    ADD CONSTRAINT tenant_agents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: tenant_features tenant_features_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_features
    ADD CONSTRAINT tenant_features_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: tenant_notification_contacts tenant_notification_contacts_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_notification_contacts
    ADD CONSTRAINT tenant_notification_contacts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: tenant_saved_trends tenant_saved_trends_saved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_saved_trends
    ADD CONSTRAINT tenant_saved_trends_saved_by_fkey FOREIGN KEY (saved_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: tenant_saved_trends tenant_saved_trends_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_saved_trends
    ADD CONSTRAINT tenant_saved_trends_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: tenant_saved_trends tenant_saved_trends_trend_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_saved_trends
    ADD CONSTRAINT tenant_saved_trends_trend_id_fkey FOREIGN KEY (trend_id) REFERENCES public.trend_items(id) ON DELETE CASCADE;


--
-- Name: tenant_subscriptions tenant_subscriptions_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_subscriptions
    ADD CONSTRAINT tenant_subscriptions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: tenant_usage_daily tenant_usage_daily_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_usage_daily
    ADD CONSTRAINT tenant_usage_daily_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: tenant_users tenant_users_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_users
    ADD CONSTRAINT tenant_users_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE SET NULL;


--
-- Name: tenant_users tenant_users_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_users
    ADD CONSTRAINT tenant_users_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: timesheets timesheets_approved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timesheets
    ADD CONSTRAINT timesheets_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: timesheets timesheets_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timesheets
    ADD CONSTRAINT timesheets_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.tenant_users(id) ON DELETE SET NULL;


--
-- Name: timesheets timesheets_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timesheets
    ADD CONSTRAINT timesheets_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: timesheets timesheets_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timesheets
    ADD CONSTRAINT timesheets_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: timesheets timesheets_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timesheets
    ADD CONSTRAINT timesheets_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.project_resources(id) ON DELETE CASCADE;


--
-- Name: timesheets timesheets_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timesheets
    ADD CONSTRAINT timesheets_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: trend_signals trend_signals_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trend_signals
    ADD CONSTRAINT trend_signals_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.trend_sources(id);


--
-- Name: trend_signals trend_signals_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trend_signals
    ADD CONSTRAINT trend_signals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: trend_sources trend_sources_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trend_sources
    ADD CONSTRAINT trend_sources_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: vehicles vehicles_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vehicles
    ADD CONSTRAINT vehicles_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id);


--
-- Name: vehicles vehicles_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vehicles
    ADD CONSTRAINT vehicles_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: video_scripts video_scripts_content_idea_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_scripts
    ADD CONSTRAINT video_scripts_content_idea_id_fkey FOREIGN KEY (content_idea_id) REFERENCES public.content_ideas(id);


--
-- Name: video_scripts video_scripts_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_scripts
    ADD CONSTRAINT video_scripts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: video_templates video_templates_preview_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_templates
    ADD CONSTRAINT video_templates_preview_asset_id_fkey FOREIGN KEY (preview_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;


--
-- Name: video_templates video_templates_template_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_templates
    ADD CONSTRAINT video_templates_template_asset_id_fkey FOREIGN KEY (template_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;


--
-- Name: video_templates video_templates_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_templates
    ADD CONSTRAINT video_templates_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: video_templates video_templates_thumbnail_asset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_templates
    ADD CONSTRAINT video_templates_thumbnail_asset_id_fkey FOREIGN KEY (thumbnail_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;


--
-- Name: whatsapp_button_replies whatsapp_button_replies_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_button_replies
    ADD CONSTRAINT whatsapp_button_replies_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: whatsapp_flow_registry whatsapp_flow_registry_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_flow_registry
    ADD CONSTRAINT whatsapp_flow_registry_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: whatsapp_flow_submissions whatsapp_flow_submissions_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_flow_submissions
    ADD CONSTRAINT whatsapp_flow_submissions_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id);


--
-- Name: whatsapp_flow_submissions whatsapp_flow_submissions_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_flow_submissions
    ADD CONSTRAINT whatsapp_flow_submissions_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: whatsapp_flow_submissions whatsapp_flow_submissions_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_flow_submissions
    ADD CONSTRAINT whatsapp_flow_submissions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: whatsapp_interactive_messages whatsapp_interactive_messages_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_interactive_messages
    ADD CONSTRAINT whatsapp_interactive_messages_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id);


--
-- Name: whatsapp_interactive_messages whatsapp_interactive_messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_interactive_messages
    ADD CONSTRAINT whatsapp_interactive_messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: whatsapp_interactive_messages whatsapp_interactive_messages_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_interactive_messages
    ADD CONSTRAINT whatsapp_interactive_messages_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: whatsapp_message_templates whatsapp_message_templates_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_message_templates
    ADD CONSTRAINT whatsapp_message_templates_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: whatsapp_order_items whatsapp_order_items_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_order_items
    ADD CONSTRAINT whatsapp_order_items_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id);


--
-- Name: whatsapp_order_items whatsapp_order_items_whatsapp_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_order_items
    ADD CONSTRAINT whatsapp_order_items_whatsapp_order_id_fkey FOREIGN KEY (whatsapp_order_id) REFERENCES public.whatsapp_orders(id) ON DELETE CASCADE;


--
-- Name: whatsapp_orders whatsapp_orders_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_orders
    ADD CONSTRAINT whatsapp_orders_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id);


--
-- Name: whatsapp_orders whatsapp_orders_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_orders
    ADD CONSTRAINT whatsapp_orders_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: whatsapp_orders whatsapp_orders_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_orders
    ADD CONSTRAINT whatsapp_orders_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: whatsapp_template_send_audit whatsapp_template_send_audit_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_template_send_audit
    ADD CONSTRAINT whatsapp_template_send_audit_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: whatsapp_template_send_audit whatsapp_template_send_audit_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_template_send_audit
    ADD CONSTRAINT whatsapp_template_send_audit_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.whatsapp_message_templates(id);


--
-- Name: whatsapp_template_send_audit whatsapp_template_send_audit_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whatsapp_template_send_audit
    ADD CONSTRAINT whatsapp_template_send_audit_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: workflow_in_app_notifications workflow_in_app_notifications_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_in_app_notifications
    ADD CONSTRAINT workflow_in_app_notifications_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: workflow_in_app_notifications workflow_in_app_notifications_outbox_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_in_app_notifications
    ADD CONSTRAINT workflow_in_app_notifications_outbox_id_fkey FOREIGN KEY (outbox_id) REFERENCES public.workflow_notification_outbox(id) ON DELETE CASCADE;


--
-- Name: workflow_in_app_notifications workflow_in_app_notifications_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_in_app_notifications
    ADD CONSTRAINT workflow_in_app_notifications_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: workflow_in_app_notifications workflow_in_app_notifications_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_in_app_notifications
    ADD CONSTRAINT workflow_in_app_notifications_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: workflow_in_app_notifications workflow_in_app_notifications_transmittal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_in_app_notifications
    ADD CONSTRAINT workflow_in_app_notifications_transmittal_id_fkey FOREIGN KEY (transmittal_id) REFERENCES public.document_transmittals(id) ON DELETE CASCADE;


--
-- Name: workflow_in_app_notifications workflow_in_app_notifications_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_in_app_notifications
    ADD CONSTRAINT workflow_in_app_notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.tenant_users(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_deliveries workflow_notification_deliveries_outbox_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_deliveries
    ADD CONSTRAINT workflow_notification_deliveries_outbox_id_fkey FOREIGN KEY (outbox_id) REFERENCES public.workflow_notification_outbox(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_deliveries workflow_notification_deliveries_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_deliveries
    ADD CONSTRAINT workflow_notification_deliveries_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_deliveries workflow_notification_deliveries_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_deliveries
    ADD CONSTRAINT workflow_notification_deliveries_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.tenant_users(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_outbox workflow_notification_outbox_approval_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_approval_id_fkey FOREIGN KEY (approval_id) REFERENCES public.document_approvals(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_outbox workflow_notification_outbox_approval_step_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_approval_step_id_fkey FOREIGN KEY (approval_step_id) REFERENCES public.document_approval_steps(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_outbox workflow_notification_outbox_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_outbox workflow_notification_outbox_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_outbox workflow_notification_outbox_target_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_target_organization_id_fkey FOREIGN KEY (target_organization_id) REFERENCES public.organizations(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_outbox workflow_notification_outbox_target_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_target_user_id_fkey FOREIGN KEY (target_user_id) REFERENCES public.tenant_users(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_outbox workflow_notification_outbox_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: workflow_notification_outbox workflow_notification_outbox_transmittal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_notification_outbox
    ADD CONSTRAINT workflow_notification_outbox_transmittal_id_fkey FOREIGN KEY (transmittal_id) REFERENCES public.document_transmittals(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--



-- =============================================================
-- Seed data
-- Constraint checks deferred during bulk load: plain pg_dump
-- --data-only INSERT ordering is not guaranteed FK-safe, and this
-- schema has legitimate circular FKs (e.g. product_categories,
-- project_participants, budget_lines).
-- =============================================================
SET session_replication_role = replica;
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.13 (Debian 16.13-1.pgdg12+1)
-- Dumped by pg_dump version 16.13 (Debian 16.13-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: tenants; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.tenants (id, tenant_code, business_name, business_type, phone_number_id, waba_id, access_token_encrypted, system_prompt, default_language, timezone, active, created_at, updated_at, business_hours, crm_business_type, whatsapp_number, faq_json) VALUES ('25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'tastybites', 'Tasty Bites Restaurant', 'RESTAURANT', 'INACTIVE_TASTYBITES', 'REPLACE_WITH_WABA_ID', NULL, 'You are a helpful WhatsApp customer service assistant for LocalBites Restaurant. Keep replies short, polite, and suitable for WhatsApp. Do not invent prices, menu items, offers, or policies. If the customer asks for a human agent, reply exactly HUMAN_HANDOFF_REQUIRED.', 'en', 'Asia/Dubai', false, '2026-07-08 21:13:34.694095', '2026-07-08 21:13:35.053779', 'Sat-Thu 9am-9pm', 'other', NULL, '[]');
INSERT INTO public.tenants (id, tenant_code, business_name, business_type, phone_number_id, waba_id, access_token_encrypted, system_prompt, default_language, timezone, active, created_at, updated_at, business_hours, crm_business_type, whatsapp_number, faq_json) VALUES ('c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'speedwheels', 'SpeedWheels Auto Service Center', 'AUTOMOBILE', '104824432320753', '109188368543368', NULL, 'You are the WhatsApp assistant for SpeedWheels Auto Service Center in Dubai. Be proactive and conversational. First, identify returning customers using their phone number and mention their registered vehicles when available. Use automobile tools for customer lookup, vehicle history, service recommendations, appointment slots, booking, and cancellation. Ask clear follow-up questions about symptoms, recent services, mileage, vehicle plate number, preferred date, and preferred time. Do not invent prices, service history, warranty terms, or appointment availability. For booking, confirm service type, vehicle, date, and exact slot. If the customer asks for a human agent, reply exactly HUMAN_HANDOFF_REQUIRED.', 'en', 'Asia/Dubai', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:36:32.665244', 'Sat-Thu 9am-9pm', 'other', NULL, '[]');


--
-- Data for Name: organizations; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.organizations (id, tenant_id, name, org_code, trade_license, contact_email, contact_phone, active, created_at, updated_at) VALUES ('96715f67-4a16-4712-812d-7de80cffd160', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SpeedWheels Holdings', 'CLIENT01', 'TL-CLIENT-0001', 'client.pm@speedwheels-demo.local', '+971-50-100-0001', true, '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575');
INSERT INTO public.organizations (id, tenant_id, name, org_code, trade_license, contact_email, contact_phone, active, created_at, updated_at) VALUES ('a1c0f2c9-808c-47ab-869e-377098812226', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'Apex Consulting Engineers', 'CONS01', 'TL-CONS-0001', 'lead.engineer@apexconsult-demo.local', '+971-50-100-0002', true, '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575');
INSERT INTO public.organizations (id, tenant_id, name, org_code, trade_license, contact_email, contact_phone, active, created_at, updated_at) VALUES ('540e31f4-71d5-4be3-950f-1ef8572de985', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'BuildRight Contractors LLC', 'CONT01', 'TL-CONT-0001', 'contracts@buildright-demo.local', '+971-50-100-0003', true, '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575');
INSERT INTO public.organizations (id, tenant_id, name, org_code, trade_license, contact_email, contact_phone, active, created_at, updated_at) VALUES ('7b86e0f7-56d4-478d-8ec7-83822d0cd8a2', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'Delta MEP Services', 'SUB01', 'TL-SUB-0001', 'site@deltamep-demo.local', '+971-50-100-0004', true, '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575');


--
-- Data for Name: projects; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.projects (id, tenant_id, name, project_code, description, contract_value, currency, retention_percent, status, start_date, end_date, created_at, updated_at) VALUES ('2751aeaf-9062-4073-98ab-c164a67d963f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'Workshop Expansion - Phase 1', 'WSX-P1', 'Demo multi-party project seeded for local testing of project control / access management.', 2500000.00, 'AED', 10.00, 'ACTIVE', '2026-08-08', '2027-02-04', '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575');


--
-- Data for Name: tenant_users; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('cb536ef9-8df6-4f0e-aee2-915056010ab9', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'admin@tastybites.com', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'Tasty Bites Restaurant Admin', 'ADMIN', true, NULL, '2026-07-08 21:13:36.524148', '2026-07-08 21:13:36.524148', NULL, NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('1f560515-25a5-452a-aba8-fadda1dd432a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'client.admin@speedwheels-demo.local', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'Client Project Director', 'ADMIN', true, NULL, '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575', '96715f67-4a16-4712-812d-7de80cffd160', NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('06ba8c89-1f5b-4d61-8100-bfd148cecb0d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'consultant.reviewer@speedwheels-demo.local', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'Consulting Engineer', 'REVIEWER', true, NULL, '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575', 'a1c0f2c9-808c-47ab-869e-377098812226', NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('5241dff3-ae9a-498b-a225-a4c2138db458', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'contractor.manager@speedwheels-demo.local', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'Contracts Manager', 'MANAGER', true, NULL, '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575', '540e31f4-71d5-4be3-950f-1ef8572de985', NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('9742a756-b6c1-4c21-913b-5fa2191e1db0', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'subcontractor.viewer@speedwheels-demo.local', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'MEP Site Engineer', 'VIEWER', true, NULL, '2026-08-08 21:33:10.893575', '2026-08-08 21:33:10.893575', '7b86e0f7-56d4-478d-8ec7-83822d0cd8a2', NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('799e97ed-9e97-47f8-a44b-3de3b1fa214a', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'manager@tastybites.com', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'Tasty Bites Restaurant Manager', 'MANAGER', true, NULL, '2026-08-08 22:21:00.239326', '2026-08-08 22:21:00.239326', NULL, NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('b3d63eb1-29aa-40bf-bd8b-2d31e86effea', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'reviewer@tastybites.com', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'Tasty Bites Restaurant Reviewer', 'REVIEWER', true, NULL, '2026-08-08 22:21:00.239326', '2026-08-08 22:21:00.239326', NULL, NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('e317681c-3c93-421a-ae72-3fbcaf5f1659', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'viewer@tastybites.com', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'Tasty Bites Restaurant Viewer', 'VIEWER', true, NULL, '2026-08-08 22:21:00.239326', '2026-08-08 22:21:00.239326', NULL, NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('5372529c-34ed-4d77-aecb-c0761e4b70ca', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'reviewer@speedwheels.com', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'SpeedWheels Auto Service Center Reviewer', 'REVIEWER', true, '2026-08-08 22:22:12.499476', '2026-08-08 22:21:00.239326', '2026-08-08 22:22:12.500822', NULL, NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('c8cc6a1c-6e8a-4e11-bf42-c89d08f0e54e', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'admin@speedwheels.com', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'SpeedWheels Auto Service Center Admin', 'ADMIN', true, '2026-08-08 22:33:21.06686', '2026-07-08 21:13:36.524148', '2026-08-08 22:33:21.069125', '96715f67-4a16-4712-812d-7de80cffd160', NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('a1213e16-8abf-4951-8ccf-04362e17e411', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'manager@speedwheels.com', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'SpeedWheels Auto Service Center Manager', 'MANAGER', true, '2026-08-08 22:33:21.402479', '2026-08-08 22:21:00.239326', '2026-08-08 22:33:21.404271', NULL, NULL, true, false);
INSERT INTO public.tenant_users (id, tenant_id, email, password_hash, full_name, role, active, last_login_at, created_at, updated_at, organization_id, notification_phone, email_notifications_enabled, whatsapp_notifications_enabled) VALUES ('ba2577cc-b7bc-41f5-9112-c8d3e094091c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'viewer@speedwheels.com', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'SpeedWheels Auto Service Center Viewer', 'VIEWER', true, '2026-08-08 22:34:01.201246', '2026-08-08 22:21:00.239326', '2026-08-08 22:34:01.204115', NULL, NULL, true, false);


--
-- Data for Name: budget_versions; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: budget_lines; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: project_resources; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: actual_cost_entries; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: campaigns; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: content_ideas; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: platforms; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('WHATSAPP', 'WhatsApp', true, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('INSTAGRAM', 'Instagram', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('FACEBOOK', 'Facebook', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('TIKTOK', 'TikTok', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('YOUTUBE', 'YouTube', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('LINKEDIN', 'LinkedIn', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('PINTEREST', 'Pinterest', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('GOOGLE_BUSINESS', 'Google Business Profile', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('X_TWITTER', 'X / Twitter', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('REDDIT', 'Reddit', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('WEBSITE', 'Website', false, '{}', '2026-07-08 21:13:35.371121');
INSERT INTO public.platforms (code, display_name, enabled, capability_json, created_at) VALUES ('MANUAL_IMPORT', 'Manual Import', true, '{}', '2026-07-08 21:13:35.371121');


--
-- Data for Name: platform_accounts; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: publish_jobs; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: analytics_snapshots; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: approval_tasks; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: background_jobs; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: canned_responses; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: consultant_kpi_snapshots; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: contacts; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.contacts (id, tenant_id, wa_id, phone_number, display_name, last_seen_at, created_at, updated_at, language) VALUES ('02d58de4-5ec4-4b56-a0fa-674008875c5d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '971501234567', '971501234567', 'Ahmed Al Rashid', '2026-07-05 21:13:35.053779', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779', 'en');
INSERT INTO public.contacts (id, tenant_id, wa_id, phone_number, display_name, last_seen_at, created_at, updated_at, language) VALUES ('b343f043-c579-4f86-a82e-f045f997fbc8', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '971502345678', '971502345678', 'Fatima Hassan', '2026-06-30 21:13:35.053779', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779', 'en');
INSERT INTO public.contacts (id, tenant_id, wa_id, phone_number, display_name, last_seen_at, created_at, updated_at, language) VALUES ('3e31cf6e-1578-4971-b735-14d99091bd3b', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '971503456789', '971503456789', 'John Smith', '2026-06-23 21:13:35.053779', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779', 'en');
INSERT INTO public.contacts (id, tenant_id, wa_id, phone_number, display_name, last_seen_at, created_at, updated_at, language) VALUES ('8ac74ee2-e406-4eb6-bc41-2130e17a896d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '971504567890', '971504567890', 'Priya Sharma', '2026-07-07 21:13:35.053779', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779', 'en');
INSERT INTO public.contacts (id, tenant_id, wa_id, phone_number, display_name, last_seen_at, created_at, updated_at, language) VALUES ('9167ceed-588e-4f9e-871b-0b906352fa51', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '971505678901', '971505678901', 'Mohammed Ali', '2026-06-08 21:13:35.053779', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779', 'en');
INSERT INTO public.contacts (id, tenant_id, wa_id, phone_number, display_name, last_seen_at, created_at, updated_at, language) VALUES ('5427a285-ab6b-4b80-9a40-2ff32a7bb2f3', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '971506789012', '971506789012', 'Sarah Johnson', '2026-07-03 21:13:35.053779', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779', 'en');
INSERT INTO public.contacts (id, tenant_id, wa_id, phone_number, display_name, last_seen_at, created_at, updated_at, language) VALUES ('4bdfe995-05dc-4d17-9075-e99af6e5efef', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '971521022707', '971521022707', 'Yaswanth', '2026-07-08 21:13:35.184364', '2026-07-08 21:13:35.184364', '2026-07-08 21:13:35.184364', 'en');
INSERT INTO public.contacts (id, tenant_id, wa_id, phone_number, display_name, last_seen_at, created_at, updated_at, language) VALUES ('51c3272b-382e-4fde-9592-eeb588a48673', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '971529999001', '971529999001', 'E2E Test Customer', '2026-07-08 21:53:28.014578', '2026-07-08 21:52:15.652503', '2026-07-08 21:53:28.016466', 'en');


--
-- Data for Name: media_assets; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: trend_items; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: content_items; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: content_variants; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: control_forecast_snapshots; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: conversations; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.conversations (id, tenant_id, contact_id, status, assigned_agent_id, bot_enabled, priority, last_message_at, created_at, updated_at, unread_count, last_message_preview) VALUES ('3c96866a-54bd-4603-9cb5-b27648afa647', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '51c3272b-382e-4fde-9592-eeb588a48673', 'REQUESTING', 'ed15f5ff-186a-4ef4-8629-82a3292ec8c6', false, 'NORMAL', '2026-07-08 22:04:31.359283', '2026-07-08 21:52:15.657567', '2026-07-08 22:04:31.360078', 0, 'hi');


--
-- Data for Name: conversation_events; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.conversation_events (id, tenant_id, conversation_id, event_type, from_agent_id, to_agent_id, notes, created_at) VALUES ('a19d0acb-4449-404f-aa88-c4f5356d10e2', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', 'AGENT_ASSIGNED', NULL, '951602ec-81eb-4858-8234-f5a4424ff57a', NULL, '2026-07-08 21:55:28.995942');
INSERT INTO public.conversation_events (id, tenant_id, conversation_id, event_type, from_agent_id, to_agent_id, notes, created_at) VALUES ('485d89e1-4d0c-4cf3-8ce5-f1ae4e05b732', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', 'AGENT_ASSIGNED', NULL, 'ed15f5ff-186a-4ef4-8629-82a3292ec8c6', NULL, '2026-07-08 21:55:41.056834');
INSERT INTO public.conversation_events (id, tenant_id, conversation_id, event_type, from_agent_id, to_agent_id, notes, created_at) VALUES ('41c11519-a8b5-43ef-8224-d70aac263932', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', 'AGENT_ASSIGNED', NULL, '951602ec-81eb-4858-8234-f5a4424ff57a', NULL, '2026-07-08 22:04:16.346138');
INSERT INTO public.conversation_events (id, tenant_id, conversation_id, event_type, from_agent_id, to_agent_id, notes, created_at) VALUES ('0f17814a-608d-4197-b299-3fdfc59b0891', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', 'AGENT_ASSIGNED', NULL, 'ed15f5ff-186a-4ef4-8629-82a3292ec8c6', NULL, '2026-07-08 22:04:26.719874');


--
-- Data for Name: document_control_workflows; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_upload_links; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: documents; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_access_grants; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_approvals; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_approval_steps; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_audit_events; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_comments; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_encryption_metadata; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_number_series; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_transmittals; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_versions; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_transmittal_items; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_transmittal_recipients; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_upload_link_events; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: document_upload_link_sessions; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: early_warning_signals; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: equipment_usage; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: feature_catalog; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('DASHBOARD', 'CRM', 'Main', 'Dashboard', 'LayoutDashboard', '/dashboard', 'VIEWER', true, 10, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('INBOX', 'CRM', 'Main', 'Inbox', 'MessageSquare', '/inbox', 'VIEWER', true, 20, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('CONTACTS', 'CRM', 'Main', 'Contacts', 'Users', '/contacts', 'VIEWER', true, 30, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('BOOKINGS', 'CRM', 'Main', 'Bookings', 'Calendar', '/bookings', 'VIEWER', true, 40, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PRODUCT_CATALOG', 'CRM', 'Main', 'Products', 'Package', '/products', 'VIEWER', true, 50, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('ORDER_MANAGEMENT', 'CRM', 'Main', 'Orders', 'ShoppingCart', '/orders', 'VIEWER', true, 60, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('CAMPAIGNS', 'MARKETING', 'Marketing', 'Campaigns', 'Megaphone', '/campaigns', 'MANAGER', false, 10, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('ANALYTICS', 'MARKETING', 'Marketing', 'Analytics', 'BarChart3', '/analytics', 'VIEWER', true, 20, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('LEAD_INTELLIGENCE', 'MARKETING', 'Marketing', 'Lead Intelligence', 'Radio', '/leads', 'MANAGER', false, 30, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PLATFORM_INTEGRATIONS', 'MARKETING', 'Marketing', 'Platforms', 'Share2', '/platforms', 'ADMIN', false, 40, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('LEARNING_INSIGHTS', 'MARKETING', 'Marketing', 'Learning Insights', 'GraduationCap', '/learning', 'MANAGER', false, 50, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('DOCUMENT_CONTROL', 'CONTENT', 'Content', 'Documents', 'FileText', '/documents', 'VIEWER', false, 10, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('MEDIA_LIBRARY', 'CONTENT', 'Content', 'Media Library', 'Image', '/media-library', 'VIEWER', false, 20, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('AI_TREND_PICKER', 'CONTENT', 'Content', 'AI Trends', 'TrendingUp', '/trends', 'MANAGER', false, 30, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('AI_CONTENT_GENERATOR', 'CONTENT', 'Content', 'Content Studio', 'Zap', '/content-studio', 'MANAGER', false, 40, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('VIDEO_TEMPLATE_ENGINE', 'CONTENT', 'Content', 'Video Generator', 'Video', '/video-generator', 'MANAGER', false, 50, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('SCHEDULED_PUBLISHING', 'CONTENT', 'Content', 'Content Calendar', 'Clock', '/calendar', 'MANAGER', false, 60, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('CONTENT_APPROVALS', 'CONTENT', 'Content', 'Approval Queue', 'CheckSquare', '/approvals', 'REVIEWER', false, 70, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('SETTINGS_WEBHOOK', 'SETTINGS', 'Settings', 'Webhook Setup', 'Globe', '/settings/webhook', 'ADMIN', true, 10, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('SETTINGS_BOT', 'SETTINGS', 'Settings', 'AI Bot Config', 'Bot', '/settings/bot', 'ADMIN', true, 20, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('SETTINGS_TEAM', 'SETTINGS', 'Settings', 'Team', 'UserPlus', '/settings/team', 'ADMIN', true, 30, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('SETTINGS_SOCIAL', 'SETTINGS', 'Settings', 'Social Accounts', 'Share2', '/settings/social', 'ADMIN', false, 40, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('SETTINGS_STORAGE', 'SETTINGS', 'Settings', 'Storage', 'HardDrive', '/settings/storage', 'ADMIN', false, 50, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('SETTINGS_BILLING', 'SETTINGS', 'Settings', 'Billing', 'CreditCard', '/settings/billing', 'ADMIN', true, 60, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROFILE', 'SETTINGS', NULL, 'Profile', 'User', '/profile', 'VIEWER', true, 70, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_CONTROL_SUITE', 'PROJECT_CONTROL', 'Enterprise', 'Project Control', 'Building2', '/control', 'MANAGER', false, 0, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_OVERVIEW', 'PROJECT_CONTROL', NULL, 'Overview', 'LayoutDashboard', '/control', 'VIEWER', false, 10, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_DOCUMENTS', 'PROJECT_CONTROL', NULL, 'Documents', 'Files', '/control/documents', 'VIEWER', false, 20, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_UPLOAD_LINKS', 'PROJECT_CONTROL', NULL, 'Upload Links', 'Link2', '/control/upload-links', 'MANAGER', false, 30, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_CONTROLS_CORE', 'PROJECT_CONTROL', NULL, 'Project Controls', 'TrendingUp', '/control/project-controls', 'MANAGER', false, 40, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_RESOURCE_COST', 'PROJECT_CONTROL', NULL, 'Resources & Cost', 'Gauge', '/control/resource-costs', 'MANAGER', false, 50, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_COMMITMENTS', 'PROJECT_CONTROL', NULL, 'Commitments & Changes', 'PackageCheck', '/control/commercial-facts', 'MANAGER', false, 60, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_FORECASTING', 'PROJECT_CONTROL', NULL, 'Forecast & Early Warning', 'Activity', '/control/forecast-intelligence', 'MANAGER', false, 70, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_WORKFLOWS', 'PROJECT_CONTROL', NULL, 'Workflows', 'GitBranch', '/control/workflows', 'MANAGER', false, 80, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_TRANSMITTALS', 'PROJECT_CONTROL', NULL, 'Transmittals', 'Send', '/control/transmittals', 'REVIEWER', false, 90, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_APPROVALS', 'PROJECT_CONTROL', NULL, 'Approvals', 'CheckSquare', '/control/approvals', 'REVIEWER', false, 100, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_SECURITY', 'PROJECT_CONTROL', NULL, 'Security & Access', 'KeyRound', '/control/security', 'ADMIN', false, 110, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_BUDGET_IPC', 'PROJECT_CONTROL', NULL, 'Budget & IPC', 'WalletCards', '/control/commercial', 'MANAGER', false, 120, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_AI_INSIGHTS', 'PROJECT_CONTROL', NULL, 'AI Insights', 'Sparkles', '/control/insights', 'VIEWER', false, 130, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_AUDIT', 'PROJECT_CONTROL', NULL, 'Audit & Compliance', 'ShieldCheck', '/control/audit', 'ADMIN', false, 140, '2026-08-08 22:17:04.246961');
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at) VALUES ('PROJECT_NOTIFICATIONS', 'PROJECT_CONTROL', NULL, 'Notifications', 'Bell', '/control/communications', 'VIEWER', false, 150, '2026-08-08 22:17:04.246961');


--
-- Data for Name: feature_api_path; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('20317db6-530b-4059-a4a2-825d4539f14f', 'DASHBOARD', '^/api/v1/crm/stats');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('937c2050-236c-4106-a8ee-c79677a810cf', 'INBOX', '/conversations');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('dfdd80e3-3f2e-4e2b-9419-7a504f05b935', 'CONTACTS', '^/api/v1/crm/contacts');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('b4f6fc59-3eb1-4fc9-9c1c-3c9e78a0ea17', 'BOOKINGS', '^/api/v1/crm/bookings');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('c38a5d15-31bc-43db-8d8a-c3b31f89f1a0', 'SETTINGS_TEAM', '^/api/v1/crm/agents');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('e25f2aaf-a078-480b-b69f-37e6d461b1ba', 'PRODUCT_CATALOG', '^/api/v1/products');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('d185bf47-c939-4b27-b3c6-ccaca7324b5b', 'ORDER_MANAGEMENT', '^/api/v1/orders');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('714d0f45-4077-4231-91f9-12a82c64013c', 'ANALYTICS', '^/api/v1/analytics');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('e8bbcb82-41e4-438f-ba7b-de90fdf921dd', 'CAMPAIGNS', '^/api/v1/campaigns');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('7618e792-eff2-46e6-b3af-f1e66134f21c', 'AI_CONTENT_GENERATOR', '^/api/v1/content-ideas');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('666a39cf-3049-4b12-a9a1-454d263b9d29', 'AI_TREND_PICKER', '^/api/v1/trends');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('d39fc3bf-ceec-46cd-a851-13cf489bef44', 'LEAD_INTELLIGENCE', '^/api/v1/leads');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('25dbbcde-74ed-4604-8eca-67b32f00cd6a', 'LEARNING_INSIGHTS', '^/api/v1/learning');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('11170f78-f642-4744-aab5-5590dbc523a0', 'CONTENT_APPROVALS', '^/api/v1/approvals');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('da07de50-75eb-44cf-952f-64fbc0ef48d1', 'SCHEDULED_PUBLISHING', '^/api/v1/publish-jobs');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('5bd87630-cc2b-477a-82f7-faca1a4339c7', 'SCHEDULED_PUBLISHING', '^/api/v1/publishing-jobs');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('60b1031b-2036-4d58-b020-dc6137ce7317', 'SETTINGS_SOCIAL', '^/api/v1/social-accounts');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('ce9cb7d7-7c46-44a4-8d77-42fc1500b95d', 'PLATFORM_INTEGRATIONS', '^/api/v1/platform-accounts');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('c09061df-a9be-4799-bf3f-bb358b39ed81', 'VIDEO_TEMPLATE_ENGINE', '^/api/v1/video-scripts');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('9882e7d4-731a-4fed-a1cf-df38238a02ac', 'VIDEO_TEMPLATE_ENGINE', '^/api/v1/render-jobs');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('5859a5b6-3e60-4911-b69a-b76be31fe8b8', 'VIDEO_TEMPLATE_ENGINE', '^/api/v1/templates(/|$)');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('8cedaafa-cfb0-4d5e-a668-2509bf8adc65', 'MEDIA_LIBRARY', '^/api/v1/media(/|$)');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('ef48747b-5eb0-4358-82cb-b441752c1d9e', 'DOCUMENT_CONTROL', '^/api/v1/documents');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('db17d29b-4918-41a4-9c93-3f1c898052d7', 'SETTINGS_WEBHOOK', '^/api/v1/webhook');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('f6684ab4-b739-4271-a947-62859c74edb6', 'SETTINGS_BOT', '^/api/tenants/[^/]+/templates');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('8d8a4141-2a27-43b2-8895-82079bf062a6', 'SETTINGS_BOT', '^/api/tenants/[^/]+/whatsapp/interactive');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('a578f52a-703b-498e-88d0-38ea5ac3c763', 'SETTINGS_BOT', '^/api/tenants/[^/]+/knowledge');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('ef694a88-9997-4cbc-a2b9-be82953def09', 'PROJECT_OVERVIEW', '^/api/v1/projects$');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('97d09075-029e-4b7f-9e84-465f5515f888', 'PROJECT_CONTROLS_CORE', '^/api/v1/projects/[^/]+/controls');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('aae625df-e5c2-434b-a7e4-d2d93718a03f', 'PROJECT_COMMITMENTS', '^/api/v1/projects/[^/]+/commercial-facts');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('777921b7-b6e0-4d9c-8eb7-efddb98d0473', 'PROJECT_FORECASTING', '^/api/v1/projects/[^/]+/forecast-intelligence');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('ff74c2e7-10a9-414f-bb31-1dea8c019277', 'PROJECT_BUDGET_IPC', '^/api/v1/projects/[^/]+/commercial$');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('b0fb6265-12c3-4aca-aeca-b7d5d456e6bc', 'PROJECT_BUDGET_IPC', '^/api/v1/payment-applications');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('735a42f9-527b-484c-b460-a0144be0c763', 'PROJECT_RESOURCE_COST', '^/api/v1/projects/[^/]+/resource-costs');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('4b468de6-ff5d-4f9e-9d16-76a5f83ad0f4', 'PROJECT_SECURITY', '^/api/v1/projects/[^/]+/capabilities');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('db1515d6-ba45-4e8b-a198-8adad21d5da7', 'PROJECT_SECURITY', '^/api/v1/organizations');
INSERT INTO public.feature_api_path (id, feature_code, path_pattern) VALUES ('aea42892-9bd5-4356-9836-af762f97530d', 'PROJECT_APPROVALS', '^/api/v1/approval-worklist');


--
-- Data for Name: forecast_snapshots; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: knowledge_documents; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.knowledge_documents (id, tenant_id, title, document_type, source_type, content, metadata, active, created_at, updated_at) VALUES ('eac0a02a-f06e-4565-90eb-249cc4f7f11b', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'LocalBites starter menu and FAQ', 'MENU', 'SEED', 'LocalBites serves burgers, pizza, pasta, salads, desserts, and soft drinks. Opening hours are 10 AM to 11 PM daily. Delivery is available within 8 km. Average delivery time is 30 to 45 minutes. If the customer asks for unavailable items or exact stock, ask the team to confirm.', '{"seed": true}', true, '2026-07-08 21:13:34.694095', '2026-07-08 21:13:34.694095');
INSERT INTO public.knowledge_documents (id, tenant_id, title, document_type, source_type, content, metadata, active, created_at, updated_at) VALUES ('26192cad-fcd7-4234-b4d6-56020b928867', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SpeedWheels Service Catalog', 'POLICY', 'SEED', 'SpeedWheels Auto Service Center - Service Catalog:
1. Oil Change & Filter (OIL_CHANGE) - Semi-synthetic or full synthetic oil, filter replacement, fluid top-up, basic inspection. Duration: 30-45 minutes.
2. Brake Service (BRAKE_SERVICE) - Brake pad inspection/replacement, rotor inspection/resurfacing, brake fluid flush. Duration: 1-3 hours.
3. Tire Rotation & Balancing (TIRE_ROTATION_BALANCING) - Rotate tires, balance wheels, inspect tire wear and pressure. Duration: 45-60 minutes.
4. AC Service & Recharge (AC_SERVICE_RECHARGE) - AC performance check, gas recharge, cabin filter replacement if needed. Duration: 1-2 hours.
5. Battery Check & Replacement (BATTERY_REPLACEMENT) - Battery health test, alternator charging check, replacement if needed. Duration: 30-45 minutes.
6. Engine Diagnostics (ENGINE_DIAGNOSTICS) - Computer fault scan, warning light diagnosis, report with recommended repairs. Duration: 45-90 minutes.
7. Transmission Service (TRANSMISSION_SERVICE) - Transmission fluid and filter service depending on vehicle type. Duration: 2-3 hours.
8. Wheel Alignment (WHEEL_ALIGNMENT) - 4-wheel computerized alignment. Duration: 45-60 minutes.
9. Full Vehicle Inspection (FULL_VEHICLE_INSPECTION) - 60-point inspection for safety, leaks, brakes, tires, battery, fluids, AC, and diagnostics. Duration: 1-2 hours.
10. Car Wash & Detailing (CAR_WASH) - Exterior hand wash, interior vacuum and wipe, full detail packages available. Duration: 1-3 hours.', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.knowledge_documents (id, tenant_id, title, document_type, source_type, content, metadata, active, created_at, updated_at) VALUES ('b3e16773-97c0-49a2-8c45-9d97453d0820', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SpeedWheels Pricing Guide', 'POLICY', 'SEED', 'SpeedWheels Auto Service Center - Pricing Guide (AED):
Oil Change & Filter: Semi-synthetic 149 AED (4-cylinder), 179 AED (6-cylinder). Full synthetic 189 AED (4-cylinder), 249 AED (6-cylinder), 350 AED (European luxury).
Brake Service: Brake pad replacement front or rear 450-650 AED depending on vehicle. Brake pad + rotor resurfacing 650-950 AED. Full brake overhaul all 4 wheels 1200-1800 AED. Brake fluid flush 150 AED.
Tire Rotation & Balancing: 120-150 AED.
AC Service & Recharge: 300-450 AED including cabin filter if needed.
Battery Check: Free with any service. Battery Replacement: 280-500 AED depending on battery size and brand.
Engine Diagnostics: 150 AED, waived if service is performed same day.
Transmission Service: 450-700 AED depending on vehicle type.
Wheel Alignment: 200 AED for 4-wheel computerized alignment.
Full Vehicle Inspection: 200-250 AED.
Car Wash: 50 AED basic, 150 AED premium, 350 AED full detail.
Prices are base prices and may vary depending on make, model, and specific requirements. European luxury vehicles such as BMW, Mercedes, and Audi may have higher prices due to specialized parts and fluids. All prices include labor.', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.knowledge_documents (id, tenant_id, title, document_type, source_type, content, metadata, active, created_at, updated_at) VALUES ('369a1eae-7814-4b57-b48b-d1e055392a55', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SpeedWheels Business Hours and Policies', 'BOOKING_RULES', 'SEED', 'SpeedWheels Auto Service Center - Hours & Policies:
Business Hours: Saturday to Thursday 8:00 AM to 6:00 PM. Friday closed. Public holidays closed and announced in advance.
Appointment Booking: Appointments can be booked up to 2 weeks in advance. Same-day appointments are subject to availability. Service slots are 1 hour each from 9:00 AM to 5:00 PM. Lunch break is 1:00 PM to 2:00 PM with no appointments.
Cancellation Policy: Free cancellation up to 4 hours before appointment. Late cancellations may affect future priority booking. If a customer misses 3 appointments, future bookings require confirmation call.
Drop-off Service: Customers can drop off vehicles outside business hours using the key drop box. A service advisor will contact the customer when the vehicle is assessed.
Pickup & Delivery: Available within 15 km radius for services over 500 AED. Pickup/delivery fee is 50 AED, waived for services over 1000 AED.
Warranty: 6-month warranty on labor. Parts warranty varies by manufacturer, typically 12-24 months. Warranty void if vehicle is serviced by unauthorized third party for the same component.
Payment Methods: Cash, Visa, Mastercard, Apple Pay, Samsung Pay. Corporate accounts are available for fleet customers.', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.knowledge_documents (id, tenant_id, title, document_type, source_type, content, metadata, active, created_at, updated_at) VALUES ('710e2d84-4cf2-46ef-a3bf-9036a9b703ef', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SpeedWheels FAQ', 'FAQ', 'SEED', 'SpeedWheels Auto Service Center - FAQ:
Q: How often should I change my oil? A: Every 5,000-10,000 km or every 6 months, whichever comes first. European cars may have longer intervals of 10,000-15,000 km with full synthetic oil.
Q: How long does an oil change take? A: 30-45 minutes for most vehicles.
Q: Do you use genuine parts? A: We use OEM-equivalent or genuine parts depending on customer preference. Genuine parts are available at a premium.
Q: Can I wait while my car is serviced? A: Yes, we have a waiting area with WiFi, coffee, and TV. For longer services, we can arrange a courtesy vehicle or taxi.
Q: Do you service all car brands? A: Yes, we service Toyota, Honda, Nissan, BMW, Mercedes-Benz, Audi, Ford, Hyundai, Kia, and more. We have specialized tools for European vehicles.
Q: What if additional repairs are needed? A: We always call before performing additional work. No surprises on the bill.
Q: Do you provide a courtesy car? A: Courtesy vehicles are available for services expected to take more than 4 hours, subject to availability and refundable deposit.
Q: How do I know when my next service is due? A: We track service history and can send WhatsApp reminders when the next service is approaching.
Q: Is there a loyalty program? A: Every 5th oil change is 50% off. Fleet customers get special corporate rates.
Q: Can I see my service history? A: Yes, customers can ask on WhatsApp and we can pull complete service history for all registered vehicles.', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');


--
-- Data for Name: knowledge_embeddings; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: lead_signals; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.lead_signals (id, tenant_id, contact_id, conversation_id, signal_type, intent_category, message_text, score, platform_code, metadata, captured_at, created_at) VALUES ('9249add6-daee-4dd6-aa72-a6d6391dc128', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '51c3272b-382e-4fde-9592-eeb588a48673', '3c96866a-54bd-4603-9cb5-b27648afa647', 'INBOUND_MESSAGE', 'BOOKING_REQUEST', 'Hi, I would like to book an oil change for my car', 0.7, 'WHATSAPP', '{}', '2026-07-08 21:52:15.675292', '2026-07-08 21:52:15.675292');


--
-- Data for Name: learning_insights; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: project_commitments; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: material_receipts; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: messages; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.messages (id, tenant_id, conversation_id, wa_message_id, direction, message_type, text_body, raw_payload, ai_generated, sent_by_agent_id, created_at, intent, confidence_score, action_type, buttons_json) VALUES ('9c3e0557-2759-4722-9df0-7f09df5315ed', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', 'wamid.E2E-TEXT-0001', 'INBOUND', 'TEXT', 'Hi, I would like to book an oil change for my car', '{"entry":[{"id":"MOCK_WABA_ID","changes":[{"field":"messages","value":{"contacts":[{"wa_id":"971529999001","profile":{"name":"E2E Test Customer"}}],"messages":[{"id":"wamid.E2E-TEXT-0001","from":"971529999001","text":{"body":"Hi, I would like to book an oil change for my car"},"type":"text","timestamp":"1751900000"}],"metadata":{"phone_number_id":"104824432320753","display_phone_number":"971500000000"},"messaging_product":"whatsapp"}}]}],"object":"whatsapp_business_account"}', false, NULL, '2026-07-08 21:52:15.658688', NULL, NULL, NULL, NULL);
INSERT INTO public.messages (id, tenant_id, conversation_id, wa_message_id, direction, message_type, text_body, raw_payload, ai_generated, sent_by_agent_id, created_at, intent, confidence_score, action_type, buttons_json) VALUES ('9b546432-f188-4ed3-84f3-fff43bedee75', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', NULL, 'OUTBOUND', 'TEXT', 'Welcome back, E2E Test Customer! I can certainly help you book an Oil Change. 

To get started, could you please provide:
1. Your **vehicle''s plate number**?
2. Your **preferred date and time slot**?

We have the following slots available today (**Thursday, July 9, 2026**):
* 09:00 - 10:00
* 11:00 - 12:00
* 12:00 - 13:00
* 14:00 - 15:00
* 15:00 - 16:00
* 16:00 - 17:00

If you prefer another date, just let me know!', NULL, true, NULL, '2026-07-08 21:52:24.909041', NULL, NULL, NULL, NULL);
INSERT INTO public.messages (id, tenant_id, conversation_id, wa_message_id, direction, message_type, text_body, raw_payload, ai_generated, sent_by_agent_id, created_at, intent, confidence_score, action_type, buttons_json) VALUES ('c46b0cbe-872e-44ab-9b03-31f759d7261f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', 'wamid.E2E-IMAGE-0002', 'INBOUND', 'IMAGE', '', '{"entry":[{"id":"MOCK_WABA_ID","changes":[{"field":"messages","value":{"contacts":[{"wa_id":"971529999001","profile":{"name":"E2E Test Customer"}}],"messages":[{"id":"wamid.E2E-IMAGE-0002","from":"971529999001","type":"image","image":{"id":"MOCK_MEDIA_ID","mime_type":"image/jpeg"},"timestamp":"1751900060"}],"metadata":{"phone_number_id":"104824432320753","display_phone_number":"971500000000"},"messaging_product":"whatsapp"}}]}],"object":"whatsapp_business_account"}', false, NULL, '2026-07-08 21:53:28.016331', NULL, NULL, NULL, NULL);
INSERT INTO public.messages (id, tenant_id, conversation_id, wa_message_id, direction, message_type, text_body, raw_payload, ai_generated, sent_by_agent_id, created_at, intent, confidence_score, action_type, buttons_json) VALUES ('e229e1c0-7b25-4a25-8fd1-16a5c7838e27', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', NULL, 'OUTBOUND', 'TEXT', 'Thanks. I received your message. Please send your request as text, or our team will assist you shortly.', NULL, true, NULL, '2026-07-08 21:53:28.024274', NULL, NULL, NULL, NULL);
INSERT INTO public.messages (id, tenant_id, conversation_id, wa_message_id, direction, message_type, text_body, raw_payload, ai_generated, sent_by_agent_id, created_at, intent, confidence_score, action_type, buttons_json) VALUES ('f3af99c7-9d43-45ac-adaa-525074c58256', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', NULL, 'OUTBOUND', 'TEXT', 'hi', NULL, false, '951602ec-81eb-4858-8234-f5a4424ff57a', '2026-07-08 22:04:22.05671', NULL, NULL, NULL, NULL);
INSERT INTO public.messages (id, tenant_id, conversation_id, wa_message_id, direction, message_type, text_body, raw_payload, ai_generated, sent_by_agent_id, created_at, intent, confidence_score, action_type, buttons_json) VALUES ('5c8af97c-05ed-4cfe-8311-e6f2203e8b8a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3c96866a-54bd-4603-9cb5-b27648afa647', NULL, 'OUTBOUND', 'TEXT', 'hi', NULL, false, 'ed15f5ff-186a-4ef4-8629-82a3292ec8c6', '2026-07-08 22:04:31.359895', NULL, NULL, NULL, NULL);


--
-- Data for Name: payment_applications; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: payment_application_items; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: payment_audit_events; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: permission_audit_events; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: plan_features; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('a1aa51aa-3733-410c-9b8c-ff7b277719fb', 'STARTER', 'WHATSAPP_BOT', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('b4100cec-3127-45aa-a601-1f9084a5d73a', 'STARTER', 'CRM_DASHBOARD', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('8204379e-140b-4961-bad2-bd89e2f7aa25', 'STARTER', 'CAMPAIGNS', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('9a7efaf0-7ed0-412a-bfc0-b0314d567309', 'STARTER', 'DOCUMENT_CONTROL', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('b096ead3-2382-471d-ab81-6301e820b645', 'STARTER', 'ZERO_KNOWLEDGE_STORAGE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('55b417c1-234e-402b-bd45-01370a60dedc', 'STARTER', 'DOCUMENT_AI_ANALYZER', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('d21846bf-621b-4531-ac15-e4c29b344263', 'STARTER', 'AI_TREND_PICKER', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('8ab6d019-5518-4666-922d-4554371a0488', 'STARTER', 'AI_CONTENT_GENERATOR', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('f3d8325b-f205-414d-a572-6c700ed9ab96', 'STARTER', 'MEDIA_LIBRARY', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('73b9a865-75c4-4a16-a581-bd9c0ecdddad', 'STARTER', 'VIDEO_TEMPLATE_ENGINE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('2a2a2c34-fc80-4291-b9e4-8382c6e2d82c', 'STARTER', 'SCHEDULED_PUBLISHING', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('dc8c0e24-3489-412b-9a31-f1b4e35092ae', 'STARTER', 'INSTAGRAM_PUBLISHING', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('2da25045-4cf9-4c6a-956a-014d66394ae0', 'STARTER', 'YOUTUBE_PUBLISHING', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('8d4576f0-a5e7-483a-900e-819636cc68db', 'STARTER', 'BYO_MEDIA_STORAGE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('8c1e3ec7-4164-422d-b25d-68f4b30b9d36', 'STARTER', 'BYO_DOCUMENT_STORAGE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('67deb0cf-cb46-4bc4-9b15-7bbc464e1a77', 'STARTER', 'CUSTOMER_KMS', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('b4b63508-8662-4001-82ed-d046c6056e3b', 'GROWTH', 'WHATSAPP_BOT', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('f279ecb2-a50e-4949-89a4-97688f93b3a5', 'GROWTH', 'CRM_DASHBOARD', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('50b6554e-c558-4236-8774-4e8227fd7f71', 'GROWTH', 'CAMPAIGNS', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('f00af0a6-73a7-4755-9774-c0510f630056', 'GROWTH', 'DOCUMENT_CONTROL', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('ac3e16ab-88e7-4af7-9ad7-a2b249f82a2b', 'GROWTH', 'ZERO_KNOWLEDGE_STORAGE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('6047835d-b3cc-40d5-aaf3-a9d9b4deeb86', 'GROWTH', 'DOCUMENT_AI_ANALYZER', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('86cac5b1-a794-47d3-af09-28e22a6fcd74', 'GROWTH', 'AI_TREND_PICKER', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('cd201cee-823c-4740-999b-7023ae254b7d', 'GROWTH', 'AI_CONTENT_GENERATOR', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('9222e8ef-69ef-452a-983b-1a94c5494c73', 'GROWTH', 'MEDIA_LIBRARY', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('132d0bda-931b-4a0b-bde5-b3adeb8ea37b', 'GROWTH', 'VIDEO_TEMPLATE_ENGINE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('c2228cc4-ee5a-413a-90f6-ba8a367e70c4', 'GROWTH', 'SCHEDULED_PUBLISHING', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('1b67e089-84e0-4379-8c10-d73079f03ed0', 'GROWTH', 'INSTAGRAM_PUBLISHING', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('c30080ba-96f8-4ce0-87c2-f04f3b5845ff', 'GROWTH', 'YOUTUBE_PUBLISHING', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('631aacb8-67bf-4e44-8cb3-139e8415e6f6', 'GROWTH', 'BYO_MEDIA_STORAGE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('54f95f3c-0d0f-4724-9833-c7e0105eed66', 'GROWTH', 'BYO_DOCUMENT_STORAGE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('12d43aff-f9e3-486f-93ab-81b171b283a3', 'GROWTH', 'CUSTOMER_KMS', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('dd331030-b6c2-4554-8784-e235a5032bd2', 'BUSINESS', 'WHATSAPP_BOT', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('4cd87aba-01cd-4228-8612-25f2322272ee', 'BUSINESS', 'CRM_DASHBOARD', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('52e64382-a5c7-4711-a1f4-a1b2a31cd78c', 'BUSINESS', 'CAMPAIGNS', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('3163485a-d012-4ecf-812c-da07721ae67b', 'BUSINESS', 'DOCUMENT_CONTROL', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('aa300b7f-1323-49be-809b-4c98aa0906dc', 'BUSINESS', 'ZERO_KNOWLEDGE_STORAGE', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('9ede4860-6db9-49d8-aa82-f3e9608e5a5b', 'BUSINESS', 'DOCUMENT_AI_ANALYZER', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('7da0e4f1-9f63-417d-8d2e-3e555c1409db', 'BUSINESS', 'AI_TREND_PICKER', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('a07fdf6b-6af9-40ac-a00e-1445c1108a21', 'BUSINESS', 'AI_CONTENT_GENERATOR', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('56227874-0ce4-4edf-be52-7a19570dd896', 'BUSINESS', 'MEDIA_LIBRARY', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('ba1b231b-fe59-4b09-84b1-1ea5eeba93cc', 'BUSINESS', 'VIDEO_TEMPLATE_ENGINE', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('abcc13e6-835c-4439-99d3-fa7bee45f8f0', 'BUSINESS', 'SCHEDULED_PUBLISHING', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('4f2caddb-e140-462d-8c11-984f5826bf66', 'BUSINESS', 'INSTAGRAM_PUBLISHING', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('ec7b61c3-ffe8-4be8-9dd1-c6586bd1f906', 'BUSINESS', 'YOUTUBE_PUBLISHING', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('fc92c28b-b930-4739-b4f3-9fcc2b299035', 'BUSINESS', 'BYO_MEDIA_STORAGE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('a042e9b0-8599-4867-97a8-f700f427d55d', 'BUSINESS', 'BYO_DOCUMENT_STORAGE', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('f7f105c9-8f2b-4009-ab14-93fb347bd87a', 'BUSINESS', 'CUSTOMER_KMS', false, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('ff2656f7-ee4e-4c62-b79a-f26aa060528f', 'ENTERPRISE', 'WHATSAPP_BOT', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('6d179829-ed34-4ac6-a537-9454e731c3b5', 'ENTERPRISE', 'CRM_DASHBOARD', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('3117bb01-1854-45fb-b5c1-8d6841d77f15', 'ENTERPRISE', 'CAMPAIGNS', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('b37d0087-05ef-491c-9cdf-97495834ba40', 'ENTERPRISE', 'DOCUMENT_CONTROL', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('d5f184f1-f49b-41eb-baa9-d58c50919d04', 'ENTERPRISE', 'ZERO_KNOWLEDGE_STORAGE', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('a6a809af-069b-41af-974a-c76dfe59dce7', 'ENTERPRISE', 'DOCUMENT_AI_ANALYZER', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('91f02fcb-1bbc-4562-9c4b-1e3b7da82618', 'ENTERPRISE', 'AI_TREND_PICKER', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('3f8bf6d2-920f-4cfe-91cb-050298ae4a2f', 'ENTERPRISE', 'AI_CONTENT_GENERATOR', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('336d37ba-dd47-4923-abb8-201282ccf4aa', 'ENTERPRISE', 'MEDIA_LIBRARY', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('3ed1e6e8-8aa8-4426-9119-35754a7ec901', 'ENTERPRISE', 'VIDEO_TEMPLATE_ENGINE', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('1c2bf3f4-6456-425c-8a0a-947ab255af90', 'ENTERPRISE', 'SCHEDULED_PUBLISHING', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('a0fc095e-d5bb-4453-ab01-7bdaa5b7d9dc', 'ENTERPRISE', 'INSTAGRAM_PUBLISHING', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('f985a52f-f2e8-4bb6-b44f-495e1784b6b3', 'ENTERPRISE', 'YOUTUBE_PUBLISHING', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('a207aaa0-8ff8-4f22-a1f5-3271e415e4d6', 'ENTERPRISE', 'BYO_MEDIA_STORAGE', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('d98fd1c7-b44a-4ecd-aa6f-97e2929c64a0', 'ENTERPRISE', 'BYO_DOCUMENT_STORAGE', true, NULL);
INSERT INTO public.plan_features (id, plan_code, feature_code, enabled, limits) VALUES ('ecd58174-53d5-48cd-a831-809bcd09c331', 'ENTERPRISE', 'CUSTOMER_KMS', true, NULL);


--
-- Data for Name: platform_admins; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.platform_admins (id, email, password_hash, full_name, active, last_login_at, created_at, updated_at) VALUES ('b0763152-72d6-47be-a8d5-2a76c881219d', 'superadmin@jeeva.internal', '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2', 'Platform Super Admin', true, '2026-08-08 22:34:01.54339', '2026-08-08 22:21:00.239326', '2026-08-08 22:34:01.544995');


--
-- Data for Name: product_categories; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.product_categories (id, tenant_id, code, name, sort_order, category_id, retailer_id, whatsapp_catalog_id, inventory_count, metadata, active, created_at, updated_at) VALUES ('133109e1-8b47-400b-b705-738f01501c9a', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'main_menu', 'Main Menu', 1, NULL, NULL, NULL, NULL, NULL, true, '2026-07-08 21:13:34.694095', '2026-07-08 21:13:34.694095');


--
-- Data for Name: products; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.products (id, tenant_id, category_id, name, description, price, currency, retailer_id, whatsapp_catalog_id, image_url, inventory_count, metadata, active, created_at, updated_at) VALUES ('2bb765da-c1e1-457a-b68e-2e1f586f7a62', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', '133109e1-8b47-400b-b705-738f01501c9a', 'Classic Burger', 'Classic beef burger with fries.', 28.00, 'AED', 'localbites_burger_001', 'REPLACE_WITH_META_CATALOG_ID', NULL, 100, '{"seed": true}', true, '2026-07-08 21:13:34.694095', '2026-07-08 21:13:34.694095');
INSERT INTO public.products (id, tenant_id, category_id, name, description, price, currency, retailer_id, whatsapp_catalog_id, image_url, inventory_count, metadata, active, created_at, updated_at) VALUES ('bf4c820e-a5ec-4987-a82f-e8e5ca4ebadd', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', '133109e1-8b47-400b-b705-738f01501c9a', 'Margherita Pizza', 'Cheese and tomato pizza.', 32.00, 'AED', 'localbites_pizza_001', 'REPLACE_WITH_META_CATALOG_ID', NULL, 100, '{"seed": true}', true, '2026-07-08 21:13:34.694095', '2026-07-08 21:13:34.694095');


--
-- Data for Name: project_capability_overrides; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: project_participants; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.project_participants (id, tenant_id, project_id, organization_id, party_role, parent_participant_id, active, created_at) VALUES ('0298aee3-958b-4f63-89fc-dcfac75ee35c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '2751aeaf-9062-4073-98ab-c164a67d963f', '96715f67-4a16-4712-812d-7de80cffd160', 'CLIENT', NULL, true, '2026-08-08 21:33:10.893575');
INSERT INTO public.project_participants (id, tenant_id, project_id, organization_id, party_role, parent_participant_id, active, created_at) VALUES ('2539127e-3a7f-459e-bf35-76dcaf6ea5c2', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '2751aeaf-9062-4073-98ab-c164a67d963f', 'a1c0f2c9-808c-47ab-869e-377098812226', 'CONSULTANT', NULL, true, '2026-08-08 21:33:10.893575');
INSERT INTO public.project_participants (id, tenant_id, project_id, organization_id, party_role, parent_participant_id, active, created_at) VALUES ('d3e5da82-129a-4448-b4b8-ee1bd0d56d13', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '2751aeaf-9062-4073-98ab-c164a67d963f', '540e31f4-71d5-4be3-950f-1ef8572de985', 'CONTRACTOR', NULL, true, '2026-08-08 21:33:10.893575');
INSERT INTO public.project_participants (id, tenant_id, project_id, organization_id, party_role, parent_participant_id, active, created_at) VALUES ('f8d726e6-4e56-4e61-8b3a-21bd55bee90c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '2751aeaf-9062-4073-98ab-c164a67d963f', '7b86e0f7-56d4-478d-8ec7-83822d0cd8a2', 'SUBCONTRACTOR', 'd3e5da82-129a-4448-b4b8-ee1bd0d56d13', true, '2026-08-08 21:33:10.893575');


--
-- Data for Name: project_contracts; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: project_variations; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: publish_results; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: social_accounts; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: publishing_jobs; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: video_templates; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.video_templates (id, tenant_id, scope, name, category, format, template_asset_id, preview_asset_id, thumbnail_asset_id, config, active, created_at, updated_at) VALUES ('1ff6ce36-4fdf-4397-9ef3-4b632e78c683', NULL, 'SYSTEM', 'Clean Reel - Minimal', 'general', 'REEL_9_16', NULL, NULL, NULL, '{"pace": "medium", "style": "minimal", "colors": ["#ffffff", "#000000"]}', true, '2026-07-08 21:13:36.429571', '2026-07-08 21:13:36.429571');
INSERT INTO public.video_templates (id, tenant_id, scope, name, category, format, template_asset_id, preview_asset_id, thumbnail_asset_id, config, active, created_at, updated_at) VALUES ('55fa250a-2e66-4f83-822f-0f1068965049', NULL, 'SYSTEM', 'Product Showcase', 'product', 'REEL_9_16', NULL, NULL, NULL, '{"pace": "slow", "style": "corporate", "colors": ["#0055cc", "#ffffff"]}', true, '2026-07-08 21:13:36.429571', '2026-07-08 21:13:36.429571');
INSERT INTO public.video_templates (id, tenant_id, scope, name, category, format, template_asset_id, preview_asset_id, thumbnail_asset_id, config, active, created_at, updated_at) VALUES ('863cccbc-1147-482f-abd1-21780a671599', NULL, 'SYSTEM', 'YouTube Intro Standard', 'general', 'YOUTUBE_16_9', NULL, NULL, NULL, '{"pace": "fast", "style": "engaging", "colors": ["#ff0000", "#ffffff"]}', true, '2026-07-08 21:13:36.429571', '2026-07-08 21:13:36.429571');
INSERT INTO public.video_templates (id, tenant_id, scope, name, category, format, template_asset_id, preview_asset_id, thumbnail_asset_id, config, active, created_at, updated_at) VALUES ('a8d9bc64-a15f-4129-b25a-17d034268b34', NULL, 'SYSTEM', 'Story - Vibrant', 'lifestyle', 'STORY_9_16', NULL, NULL, NULL, '{"pace": "fast", "style": "vibrant", "colors": ["#ff6b6b", "#ffd93d"]}', true, '2026-07-08 21:13:36.429571', '2026-07-08 21:13:36.429571');
INSERT INTO public.video_templates (id, tenant_id, scope, name, category, format, template_asset_id, preview_asset_id, thumbnail_asset_id, config, active, created_at, updated_at) VALUES ('09afc2a9-6c83-4a5f-979e-b75d8acc1577', NULL, 'SYSTEM', 'Shorts - Trending', 'general', 'SHORTS_9_16', NULL, NULL, NULL, '{"pace": "fast", "style": "trendy", "colors": ["#6c5ce7", "#00cec9"]}', true, '2026-07-08 21:13:36.429571', '2026-07-08 21:13:36.429571');


--
-- Data for Name: render_jobs; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: resource_rates; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: role_permissions; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'DASHBOARD', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'INBOX', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'CONTACTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'BOOKINGS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PRODUCT_CATALOG', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'ORDER_MANAGEMENT', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'CAMPAIGNS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'ANALYTICS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'LEAD_INTELLIGENCE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PLATFORM_INTEGRATIONS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'LEARNING_INSIGHTS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'DOCUMENT_CONTROL', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'MEDIA_LIBRARY', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'AI_TREND_PICKER', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'AI_CONTENT_GENERATOR', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'VIDEO_TEMPLATE_ENGINE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SCHEDULED_PUBLISHING', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'CONTENT_APPROVALS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_WEBHOOK', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_BOT', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_TEAM', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_SOCIAL', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_STORAGE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_BILLING', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROFILE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_CONTROL_SUITE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_OVERVIEW', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_DOCUMENTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_UPLOAD_LINKS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_CONTROLS_CORE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_RESOURCE_COST', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_COMMITMENTS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_FORECASTING', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_WORKFLOWS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_TRANSMITTALS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_APPROVALS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_SECURITY', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_BUDGET_IPC', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_AI_INSIGHTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_AUDIT', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_NOTIFICATIONS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'DASHBOARD', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'INBOX', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'CONTACTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'BOOKINGS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PRODUCT_CATALOG', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'ORDER_MANAGEMENT', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'CAMPAIGNS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'ANALYTICS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'LEAD_INTELLIGENCE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PLATFORM_INTEGRATIONS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'LEARNING_INSIGHTS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'DOCUMENT_CONTROL', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'MEDIA_LIBRARY', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'AI_TREND_PICKER', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'AI_CONTENT_GENERATOR', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'VIDEO_TEMPLATE_ENGINE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SCHEDULED_PUBLISHING', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'CONTENT_APPROVALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_WEBHOOK', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_BOT', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_TEAM', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_SOCIAL', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_STORAGE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_BILLING', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROFILE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_CONTROL_SUITE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_OVERVIEW', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_DOCUMENTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_UPLOAD_LINKS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_CONTROLS_CORE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_RESOURCE_COST', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_COMMITMENTS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_FORECASTING', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_WORKFLOWS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_TRANSMITTALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_APPROVALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_SECURITY', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_BUDGET_IPC', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_AI_INSIGHTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_AUDIT', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_NOTIFICATIONS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'DASHBOARD', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'INBOX', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'CONTACTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'BOOKINGS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PRODUCT_CATALOG', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'ORDER_MANAGEMENT', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'CAMPAIGNS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'ANALYTICS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'LEAD_INTELLIGENCE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PLATFORM_INTEGRATIONS', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'LEARNING_INSIGHTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'DOCUMENT_CONTROL', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'MEDIA_LIBRARY', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'AI_TREND_PICKER', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'AI_CONTENT_GENERATOR', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'VIDEO_TEMPLATE_ENGINE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SCHEDULED_PUBLISHING', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'CONTENT_APPROVALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_WEBHOOK', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_BOT', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_TEAM', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_SOCIAL', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_STORAGE', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_BILLING', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROFILE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_CONTROL_SUITE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_OVERVIEW', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_DOCUMENTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_UPLOAD_LINKS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_CONTROLS_CORE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_RESOURCE_COST', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_COMMITMENTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_FORECASTING', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_WORKFLOWS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_TRANSMITTALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_APPROVALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_SECURITY', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_BUDGET_IPC', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_AI_INSIGHTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_AUDIT', 'VIEW', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_NOTIFICATIONS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'DASHBOARD', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'INBOX', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'CONTACTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'BOOKINGS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PRODUCT_CATALOG', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'ORDER_MANAGEMENT', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'CAMPAIGNS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'ANALYTICS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'LEAD_INTELLIGENCE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PLATFORM_INTEGRATIONS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'LEARNING_INSIGHTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'DOCUMENT_CONTROL', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'MEDIA_LIBRARY', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'AI_TREND_PICKER', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'AI_CONTENT_GENERATOR', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'VIDEO_TEMPLATE_ENGINE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SCHEDULED_PUBLISHING', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'CONTENT_APPROVALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_WEBHOOK', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_BOT', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_TEAM', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_SOCIAL', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_STORAGE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_BILLING', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROFILE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_CONTROL_SUITE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_OVERVIEW', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_DOCUMENTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_UPLOAD_LINKS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_CONTROLS_CORE', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_RESOURCE_COST', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_COMMITMENTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_FORECASTING', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_WORKFLOWS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_TRANSMITTALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_APPROVALS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_SECURITY', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_BUDGET_IPC', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_AI_INSIGHTS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_AUDIT', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_NOTIFICATIONS', 'VIEW', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'DASHBOARD', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'INBOX', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'CONTACTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'BOOKINGS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PRODUCT_CATALOG', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'ORDER_MANAGEMENT', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'CAMPAIGNS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'ANALYTICS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'LEAD_INTELLIGENCE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PLATFORM_INTEGRATIONS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'LEARNING_INSIGHTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'DOCUMENT_CONTROL', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'MEDIA_LIBRARY', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'AI_TREND_PICKER', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'AI_CONTENT_GENERATOR', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'VIDEO_TEMPLATE_ENGINE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SCHEDULED_PUBLISHING', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'CONTENT_APPROVALS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_WEBHOOK', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_BOT', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_TEAM', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_SOCIAL', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_STORAGE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'SETTINGS_BILLING', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROFILE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_CONTROL_SUITE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_OVERVIEW', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_DOCUMENTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_UPLOAD_LINKS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_CONTROLS_CORE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_RESOURCE_COST', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_COMMITMENTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_FORECASTING', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_WORKFLOWS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_TRANSMITTALS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_APPROVALS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_SECURITY', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_BUDGET_IPC', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_AI_INSIGHTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_AUDIT', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('VIEWER', 'PROJECT_NOTIFICATIONS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'DASHBOARD', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'INBOX', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'CONTACTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'BOOKINGS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PRODUCT_CATALOG', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'ORDER_MANAGEMENT', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'CAMPAIGNS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'ANALYTICS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'LEAD_INTELLIGENCE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PLATFORM_INTEGRATIONS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'LEARNING_INSIGHTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'DOCUMENT_CONTROL', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'MEDIA_LIBRARY', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'AI_TREND_PICKER', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'AI_CONTENT_GENERATOR', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'VIDEO_TEMPLATE_ENGINE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SCHEDULED_PUBLISHING', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'CONTENT_APPROVALS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_WEBHOOK', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_BOT', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_TEAM', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_SOCIAL', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_STORAGE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'SETTINGS_BILLING', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROFILE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_CONTROL_SUITE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_OVERVIEW', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_DOCUMENTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_UPLOAD_LINKS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_CONTROLS_CORE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_RESOURCE_COST', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_COMMITMENTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_FORECASTING', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_WORKFLOWS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_TRANSMITTALS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_APPROVALS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_SECURITY', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_BUDGET_IPC', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_AI_INSIGHTS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_AUDIT', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('REVIEWER', 'PROJECT_NOTIFICATIONS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'DASHBOARD', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'INBOX', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'CONTACTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'BOOKINGS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PRODUCT_CATALOG', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'ORDER_MANAGEMENT', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'CAMPAIGNS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'ANALYTICS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'LEAD_INTELLIGENCE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PLATFORM_INTEGRATIONS', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'LEARNING_INSIGHTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'DOCUMENT_CONTROL', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'MEDIA_LIBRARY', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'AI_TREND_PICKER', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'AI_CONTENT_GENERATOR', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'VIDEO_TEMPLATE_ENGINE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SCHEDULED_PUBLISHING', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'CONTENT_APPROVALS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_WEBHOOK', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_BOT', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_TEAM', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_SOCIAL', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_STORAGE', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'SETTINGS_BILLING', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROFILE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_CONTROL_SUITE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_OVERVIEW', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_DOCUMENTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_UPLOAD_LINKS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_CONTROLS_CORE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_RESOURCE_COST', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_COMMITMENTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_FORECASTING', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_WORKFLOWS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_TRANSMITTALS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_APPROVALS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_SECURITY', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_BUDGET_IPC', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_AI_INSIGHTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_AUDIT', 'MANAGE', false);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('MANAGER', 'PROJECT_NOTIFICATIONS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'DASHBOARD', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'INBOX', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'CONTACTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'BOOKINGS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PRODUCT_CATALOG', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'ORDER_MANAGEMENT', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'CAMPAIGNS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'ANALYTICS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'LEAD_INTELLIGENCE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PLATFORM_INTEGRATIONS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'LEARNING_INSIGHTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'DOCUMENT_CONTROL', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'MEDIA_LIBRARY', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'AI_TREND_PICKER', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'AI_CONTENT_GENERATOR', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'VIDEO_TEMPLATE_ENGINE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SCHEDULED_PUBLISHING', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'CONTENT_APPROVALS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_WEBHOOK', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_BOT', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_TEAM', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_SOCIAL', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_STORAGE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'SETTINGS_BILLING', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROFILE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_CONTROL_SUITE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_OVERVIEW', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_DOCUMENTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_UPLOAD_LINKS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_CONTROLS_CORE', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_RESOURCE_COST', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_COMMITMENTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_FORECASTING', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_WORKFLOWS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_TRANSMITTALS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_APPROVALS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_SECURITY', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_BUDGET_IPC', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_AI_INSIGHTS', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_AUDIT', 'MANAGE', true);
INSERT INTO public.role_permissions (role, feature_code, action, allowed) VALUES ('ADMIN', 'PROJECT_NOTIFICATIONS', 'MANAGE', true);


--
-- Data for Name: vehicles; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('62c91abf-b8d6-4515-8018-03b17da45b73', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '02d58de4-5ec4-4b56-a0fa-674008875c5d', 'Toyota', 'Camry', 2021, 'DXB A-12345', 'JTNB11HK1M3000001', 'White', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('416e0011-ea4b-4f13-ae0a-1fd34c2c3a59', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'b343f043-c579-4f86-a82e-f045f997fbc8', 'Honda', 'Civic', 2020, 'DXB B-23456', 'MRHFC1660LT000002', 'Silver', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('e19c1fe5-051b-4d85-8583-f48cb42eaadf', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3e31cf6e-1578-4971-b735-14d99091bd3b', 'BMW', 'X5', 2022, 'DXB C-34567', 'WBACR6109N9000003', 'Black', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('e35a3097-03cc-4115-b0f6-a34a98beeb4a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '8ac74ee2-e406-4eb6-bc41-2130e17a896d', 'Nissan', 'Patrol', 2019, 'DXB D-45678', 'JN1TANY62K0000004', 'Gold', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('5f92972c-75d1-4c8e-9667-087c6532cff9', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '8ac74ee2-e406-4eb6-bc41-2130e17a896d', 'Mercedes-Benz', 'C200', 2023, 'DXB E-56789', 'W1KAF4GB1PN000005', 'Blue', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('747b263a-eeb6-48c2-abf9-2e19a10fbb95', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '9167ceed-588e-4f9e-871b-0b906352fa51', 'Hyundai', 'Tucson', 2021, 'DXB F-67890', 'KM8J33A45MU000006', 'Grey', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('9100d11b-fdb4-43fb-8675-9bdb2143b44e', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '5427a285-ab6b-4b80-9a40-2ff32a7bb2f3', 'Ford', 'Mustang', 2022, 'DXB G-78901', '1FA6P8TH5N5000007', 'Red', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('7e4422e2-280a-4aee-ae55-0c461047057b', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '5427a285-ab6b-4b80-9a40-2ff32a7bb2f3', 'Kia', 'Sportage', 2020, 'DXB H-89012', 'KNDPMCAC5L7000008', 'White', '{"seed": true}', true, '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.vehicles (id, tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata, active, created_at, updated_at) VALUES ('9c051974-a969-407c-8c2c-abce4e2b6b74', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '4bdfe995-05dc-4d17-9075-e99af6e5efef', 'Toyota', 'Land Cruiser', 2022, 'DXB Y-90909', 'JTMHV05J504000009', 'White', '{"seed": true}', true, '2026-07-08 21:13:35.184364', '2026-07-08 21:13:35.184364');


--
-- Data for Name: service_appointments; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('2cebc78e-108e-4263-a156-9b1ce969b13a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-08', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('c68574fe-2aa6-41bd-aa4b-21f84ba43235', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-08', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('83fe5e18-f2a7-4a25-abc0-420f804d6e30', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-08', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('d8cd217a-8d04-48f9-a19f-824f06a06c11', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-08', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('2007485b-6f5b-4bc3-baa8-c17d2f08a61c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-08', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('acc746ba-809c-4642-abfa-334e3579e8db', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-09', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('011c32b5-6e2d-4dd1-882b-cd38b93cf764', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-09', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('944ef22b-1453-41e6-b1a2-71c986bf268c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-09', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('09b65e3d-7bc6-4f5e-8d82-c9f2af28a170', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-09', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('778b9150-8550-4e78-842e-3c127d97b991', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-09', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('e4009b8a-cbfe-4f04-a23f-1535afe98bb9', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-09', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('7d10a620-70f3-4a43-a207-e55a412a6e7b', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-11', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('faaf6423-165a-4e4d-909e-3327953f2119', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-11', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('fa7f8a9c-a1d6-4f25-b947-72f656f7eee2', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-11', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('983af051-dd73-456b-9d77-5a7509664f0b', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-11', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('d44304a1-ce5d-448d-beba-0ad6c680bf6e', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-11', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('d6c11c00-7bf4-428e-82bd-ab230dff96c3', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-11', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('86e63053-198f-4f4c-9dc3-5181b4096e8d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-11', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('a75fc55c-0839-47e2-8fe8-425c261e9893', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-12', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('6c83ca98-0494-4c51-b8f4-b895a9570bbd', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-12', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('b4923e54-4eb3-4bc0-ae59-66a63a673915', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-12', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('b64c76e2-9f86-4fc8-999b-4cc9c1e9a93d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-12', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('03d4139f-30f1-4ef5-b039-f68689aa3ef2', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-12', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('1e08e806-0afe-45ba-bfcd-8b55d5165e01', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-12', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('7d2e1a53-7cc0-4957-bb20-23699ebd511b', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-12', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('4f35ee57-6da0-4bda-aafa-9002bae80eea', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-13', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('d8e02e73-6be0-43e1-9492-943cfe87aa5a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-13', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('093135ef-a8ab-4d05-9b34-f4ff415bf0c7', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-13', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('54451c06-3399-4ab0-98d5-16b26d6d46ff', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-13', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('07a8769e-abf4-47d0-9c6a-23a659bbfe55', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-13', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('ffa5fb71-6cb9-4a2d-a67f-40d550dcbf8f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-13', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('77b2c5d1-6d2f-44b5-9589-a2d7281ca133', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-13', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('757b6bcd-af17-47a2-888a-6ce19fc281b8', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-14', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('7557f1db-565e-4201-a216-ef9b68be8f83', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-14', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('95636621-6062-4c12-8635-e7b8c764bfe3', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-14', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('bd153c97-8efd-434f-a021-4c19fff0cd0d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-14', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('10e1f92f-f54b-46bb-a119-cb63c9b23f07', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-14', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('10d2d6b1-8f74-4265-a2b3-d8187de088a7', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-14', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('f3450e6c-5e10-4f82-9428-253300f574b7', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-14', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('52b34fd7-49d0-44f6-aeca-d74bf4041661', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-15', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('4c66f3a8-9883-4c7b-bb66-5c65ae658ea7', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-15', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('f765dd89-dfe8-4a63-b753-c3ab01c621bc', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-15', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('995905b7-6ccf-4697-bec1-7f3855f14a07', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-15', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('5cb9f354-a79a-4dfa-bef2-a15c3062cc40', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-15', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('23670404-5d38-4a35-8689-1a9f468b19ff', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-15', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('a7dea655-577a-4efe-8a55-c48d2e022d04', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-15', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('63721eb3-a297-438d-bac7-9dbb3696e6a7', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-16', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('1bc29277-cd16-4675-af57-2f56c2f0e7b4', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-16', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('21ec00ae-b23e-4cf5-83ab-d76c373f5161', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-16', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('8bc3b569-c2ee-463e-8856-c9f34f1c6c8c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-16', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('83cab4cc-7dc6-4bfd-bd43-a67c4609a69c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-16', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('0ed65f1d-3b3f-402a-bb4d-0e70e0516ce1', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-16', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('92e2a38a-685e-4235-b0a8-e6d44260a093', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-16', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('6dc80703-21cc-48a2-93be-d8e93cb23575', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-18', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('48fb7a2a-69b2-4819-99a8-83399f257858', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-18', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('3d3f333f-396f-4dfa-8c35-a2db7ea75f93', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-18', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('0b6caae9-fca4-4d17-8369-ca4bd2cb19ee', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-18', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('3762531c-bfbc-49c9-827e-71cc91a80194', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-18', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('b3186e2c-d089-4207-9d7b-32026ac55ba4', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-18', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('812a6a75-56b7-48b5-8dd4-a85c64d43e9c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-18', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('ec7139a5-e9ac-4e4c-9959-54180ea24c9f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-19', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('04369f8d-6577-4996-b1a2-748cc0df58a5', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-19', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('6dfd4d0a-2fcb-471b-ba98-0bfa65513add', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-19', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('26e55c9b-2bed-411a-af74-30ae8f322f1f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-19', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('799d9059-67ce-440a-8a96-a8f4a1fe3e42', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-19', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('2d56bb24-1065-4c3e-a97c-7607cb34ca4a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-19', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('79719e37-1f45-4158-9893-58576d884e73', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-19', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('47dd24b6-3832-43e0-8004-9607cdd8c37f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-20', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('4c45a1dc-ce51-41f7-a27f-657cec3361ef', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-20', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('b9252949-e421-4036-be3d-0c8841b97595', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-20', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('1da86432-2018-4719-a6ab-8a54f839253f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-20', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('a321baa6-51a4-4abd-8f25-7ef5bb252022', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-20', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('a5e1787f-34b7-4bb9-a648-7b45c6a22191', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-20', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('f74c6a8c-e85f-4363-8bc6-83f2a3338a19', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-20', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('99ed93de-8963-4565-99b4-8d37900badc2', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-21', '09:00-10:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('d8981d3c-00c9-4959-8fe9-a72e634978f5', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-21', '10:00-11:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('5809c10a-df87-4c5b-902b-a4a7abad1dc1', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-21', '11:00-12:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('69aec9ef-d34a-4c49-855f-3ee3d10c3fa4', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-21', '12:00-13:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('cc11da61-0d5a-4fdc-b8e6-cf7eed1d0036', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-21', '14:00-15:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('d736da3b-ddaf-49dd-9194-fd331421281d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-21', '15:00-16:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('88796c2a-af3f-432e-b6d5-80266cd6e4c5', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', NULL, NULL, 'ANY', '2026-07-21', '16:00-17:00', 'AVAILABLE', NULL, NULL, NULL, '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('40c037dd-b470-4ea5-a61c-c97561aa8751', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '02d58de4-5ec4-4b56-a0fa-674008875c5d', '62c91abf-b8d6-4515-8018-03b17da45b73', 'OIL_CHANGE', '2026-07-08', '10:00-11:00', 'BOOKED', '971501234567', 'Ahmed Al Rashid', 'Seed booking', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('6b3c825e-3f40-4b57-bcc8-6958500a2fe1', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '3e31cf6e-1578-4971-b735-14d99091bd3b', 'e19c1fe5-051b-4d85-8583-f48cb42eaadf', 'ENGINE_DIAGNOSTICS', '2026-07-08', '14:00-15:00', 'BOOKED', '971503456789', 'John Smith', 'Seed booking', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_appointments (id, tenant_id, contact_id, vehicle_id, service_type, appointment_date, time_slot, status, customer_phone, customer_name, notes, metadata, created_at, updated_at) VALUES ('be7688f0-fff2-4db0-8fcd-2e2db4959161', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '8ac74ee2-e406-4eb6-bc41-2130e17a896d', '5f92972c-75d1-4c8e-9667-087c6532cff9', 'FULL_VEHICLE_INSPECTION', '2026-07-09', '10:00-11:00', 'BOOKED', '971504567890', 'Priya Sharma', 'Seed booking', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');


--
-- Data for Name: service_records; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('817ea1c1-163f-4423-a4ba-fb812d9ee1f0', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '62c91abf-b8d6-4515-8018-03b17da45b73', '02d58de4-5ec4-4b56-a0fa-674008875c5d', 'OIL_CHANGE', 'Full synthetic oil and filter replacement', 'Ravi Kumar', 42000, 189.00, 'AED', 'Cabin filter checked. Engine running smooth.', '2026-01-24 21:13:35.053779', '2026-07-23 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('9de6447c-3711-400e-9650-dd1b5418badd', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '62c91abf-b8d6-4515-8018-03b17da45b73', '02d58de4-5ec4-4b56-a0fa-674008875c5d', 'TIRE_ROTATION_BALANCING', 'Tire rotation and wheel balancing', 'Hassan Noor', 39000, 130.00, 'AED', 'Front tires moved to rear. No abnormal wear.', '2025-11-20 21:13:35.053779', NULL, 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('f8f69700-e7f9-44c1-9ea2-bd9e2801483f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '62c91abf-b8d6-4515-8018-03b17da45b73', '02d58de4-5ec4-4b56-a0fa-674008875c5d', 'BRAKE_SERVICE', 'Front brake pad replacement', 'Omar Khalid', 35000, 520.00, 'AED', 'Customer reported squeaking. Pads replaced and road tested.', '2025-08-22 21:13:35.053779', '2027-02-13 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('b2e2af36-9ac9-4ec0-a382-d75f378f0ddc', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '416e0011-ea4b-4f13-ae0a-1fd34c2c3a59', 'b343f043-c579-4f86-a82e-f045f997fbc8', 'AC_SERVICE_RECHARGE', 'AC gas recharge and cabin filter replacement', 'Anil Thomas', 61000, 380.00, 'AED', 'Cooling performance restored.', '2026-05-24 21:13:35.053779', '2027-05-24 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('ea699f69-53a5-47ce-818b-3d72b311e9d1', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '416e0011-ea4b-4f13-ae0a-1fd34c2c3a59', 'b343f043-c579-4f86-a82e-f045f997fbc8', 'OIL_CHANGE', 'Semi-synthetic oil and filter replacement', 'Ravi Kumar', 57000, 149.00, 'AED', 'Next oil change due soon.', '2026-01-14 21:13:35.053779', '2026-07-13 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('963f45b9-8f66-4b09-89c5-20b264734cff', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'e19c1fe5-051b-4d85-8583-f48cb42eaadf', '3e31cf6e-1578-4971-b735-14d99091bd3b', 'ENGINE_DIAGNOSTICS', 'Computer diagnostics and fault scan', 'Mark Wilson', 28000, 150.00, 'AED', 'Minor oxygen sensor warning cleared after inspection.', '2026-06-18 21:13:35.053779', NULL, 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('b1e1b63c-ffdf-4830-9d99-8fcee14318be', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'e19c1fe5-051b-4d85-8583-f48cb42eaadf', '3e31cf6e-1578-4971-b735-14d99091bd3b', 'TRANSMISSION_SERVICE', 'Transmission fluid service for BMW X5', 'Sameer Khan', 25000, 700.00, 'AED', 'Used European-spec fluid.', '2026-02-08 21:13:35.053779', '2027-02-08 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('6f5582bd-a834-4573-abf5-64aefadd4c5f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'e19c1fe5-051b-4d85-8583-f48cb42eaadf', '3e31cf6e-1578-4971-b735-14d99091bd3b', 'OIL_CHANGE', 'European full synthetic oil service', 'Ravi Kumar', 21500, 350.00, 'AED', 'BMW-approved oil grade used.', '2025-10-11 21:13:35.053779', '2026-10-11 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('2011c7ab-5589-404a-9cf4-274b96ab3160', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'e35a3097-03cc-4115-b0f6-a34a98beeb4a', '8ac74ee2-e406-4eb6-bc41-2130e17a896d', 'BATTERY_REPLACEMENT', 'Battery test and replacement', 'Hassan Noor', 88000, 420.00, 'AED', 'Old battery failed load test.', '2026-04-29 21:13:35.053779', NULL, 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('90f20e9c-26a3-47bf-85ec-8cf4d8390243', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'e35a3097-03cc-4115-b0f6-a34a98beeb4a', '8ac74ee2-e406-4eb6-bc41-2130e17a896d', 'WHEEL_ALIGNMENT', '4-wheel computerized alignment', 'Omar Khalid', 83500, 200.00, 'AED', 'Steering pull corrected.', '2025-12-30 21:13:35.053779', NULL, 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('fc71102f-6805-40bc-bf7b-8184c4798681', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'e35a3097-03cc-4115-b0f6-a34a98beeb4a', '8ac74ee2-e406-4eb6-bc41-2130e17a896d', 'OIL_CHANGE', 'Full synthetic oil and filter replacement', 'Ravi Kumar', 80000, 249.00, 'AED', 'Large engine oil capacity.', '2025-10-21 21:13:35.053779', '2026-10-16 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('6884be55-1c5a-4de2-be96-fd85452b7ee1', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '5f92972c-75d1-4c8e-9667-087c6532cff9', '8ac74ee2-e406-4eb6-bc41-2130e17a896d', 'FULL_VEHICLE_INSPECTION', 'Pre-warranty full vehicle inspection', 'Mark Wilson', 12000, 250.00, 'AED', 'No major defects. Monitor brake wear.', '2026-06-26 21:13:35.053779', NULL, 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('4e10c71b-4120-4fbd-8f81-ddf5431819b4', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '5f92972c-75d1-4c8e-9667-087c6532cff9', '8ac74ee2-e406-4eb6-bc41-2130e17a896d', 'CAR_WASH', 'Premium wash and interior vacuum', 'Imran Ali', 11800, 150.00, 'AED', 'Interior cleaned and dashboard dressed.', '2026-06-26 21:13:35.053779', NULL, 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('eb38adbb-f6b1-408a-b436-8f588030e783', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '747b263a-eeb6-48c2-abf9-2e19a10fbb95', '9167ceed-588e-4f9e-871b-0b906352fa51', 'BRAKE_SERVICE', 'Brake pad and rotor resurfacing', 'Omar Khalid', 51000, 850.00, 'AED', 'Brake vibration resolved.', '2026-04-14 21:13:35.053779', '2027-04-14 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('1ab78da0-69b9-48d9-8244-2a0f81fe4f82', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '747b263a-eeb6-48c2-abf9-2e19a10fbb95', '9167ceed-588e-4f9e-871b-0b906352fa51', 'OIL_CHANGE', 'Full synthetic oil and filter replacement', 'Anil Thomas', 47000, 189.00, 'AED', 'Recommended tire rotation next visit.', '2026-01-04 21:13:35.053779', '2026-08-02 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('3a7b629a-d3a8-47a6-ab5e-c9412bc27730', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '9100d11b-fdb4-43fb-8675-9bdb2143b44e', '5427a285-ab6b-4b80-9a40-2ff32a7bb2f3', 'WHEEL_ALIGNMENT', '4-wheel alignment after tire replacement', 'Hassan Noor', 18000, 200.00, 'AED', 'Alignment set to factory spec.', '2026-06-03 21:13:35.053779', NULL, 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('5b0382dd-ac3f-4816-b5f5-cf265e59404e', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '9100d11b-fdb4-43fb-8675-9bdb2143b44e', '5427a285-ab6b-4b80-9a40-2ff32a7bb2f3', 'OIL_CHANGE', 'Full synthetic oil service', 'Ravi Kumar', 15500, 249.00, 'AED', 'Performance engine oil grade used.', '2026-02-03 21:13:35.053779', '2026-08-02 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('7e8dacc4-2791-4d3f-a8cb-abc8496ac010', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '7e4422e2-280a-4aee-ae55-0c461047057b', '5427a285-ab6b-4b80-9a40-2ff32a7bb2f3', 'AC_SERVICE_RECHARGE', 'AC service and leak check', 'Anil Thomas', 64000, 320.00, 'AED', 'No leak found. Cooling improved.', '2026-04-04 21:13:35.053779', '2027-03-30 21:13:35.053779', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.053779', '2026-07-08 21:13:35.053779');
INSERT INTO public.service_records (id, tenant_id, vehicle_id, contact_id, service_type, description, technician_name, mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata, created_at, updated_at) VALUES ('d0afe597-487f-4d06-ba1f-c8948b5eff81', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', '9c051974-a969-407c-8c2c-abce4e2b6b74', '4bdfe995-05dc-4d17-9075-e99af6e5efef', 'OIL_CHANGE', 'Full synthetic oil and filter replacement', 'Ravi Kumar', 18000, 249.00, 'AED', 'No issues found. Cabin filter checked.', '2026-04-09 21:13:35.184364', '2026-10-06 21:13:35.184364', 'COMPLETED', '{"seed": true}', '2026-07-08 21:13:35.184364', '2026-07-08 21:13:35.184364');


--
-- Data for Name: storage_upload_tokens; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: subscription_plans; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.subscription_plans (id, code, name, monthly_price, active, limits, created_at) VALUES ('5d76de4a-1f34-49ed-8e50-ed0158e34ad8', 'STARTER', 'Starter', 29.00, true, '{"maxUsers": 2, "maxDocuments": 100, "maxMediaStorageGb": 5, "maxAiTokensPerMonth": 500000, "maxRenderMinutesPerMonth": 30, "maxScheduledPostsPerMonth": 50, "maxGeneratedVideosPerMonth": 10}', '2026-07-08 21:13:36.117388');
INSERT INTO public.subscription_plans (id, code, name, monthly_price, active, limits, created_at) VALUES ('8884dbe1-0959-4977-ac83-385211f4f099', 'GROWTH', 'Growth', 79.00, true, '{"maxUsers": 5, "maxDocuments": 500, "maxMediaStorageGb": 25, "maxAiTokensPerMonth": 2000000, "maxRenderMinutesPerMonth": 120, "maxScheduledPostsPerMonth": 200, "maxGeneratedVideosPerMonth": 50}', '2026-07-08 21:13:36.117388');
INSERT INTO public.subscription_plans (id, code, name, monthly_price, active, limits, created_at) VALUES ('6d15558d-f262-455a-84aa-53e73a21949c', 'BUSINESS', 'Business', 199.00, true, '{"maxUsers": 10, "maxDocuments": 1000, "maxMediaStorageGb": 100, "maxAiTokensPerMonth": 10000000, "maxRenderMinutesPerMonth": 600, "maxScheduledPostsPerMonth": 500, "maxGeneratedVideosPerMonth": 100}', '2026-07-08 21:13:36.117388');
INSERT INTO public.subscription_plans (id, code, name, monthly_price, active, limits, created_at) VALUES ('7bd3baed-9e92-407a-8dd8-73c46fc1b5fd', 'ENTERPRISE', 'Enterprise', NULL, true, '{"maxUsers": null, "maxDocuments": null, "maxMediaStorageGb": null, "maxAiTokensPerMonth": null, "maxRenderMinutesPerMonth": null, "maxScheduledPostsPerMonth": null, "maxGeneratedVideosPerMonth": null}', '2026-07-08 21:13:36.117388');


--
-- Data for Name: tenant_agents; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.tenant_agents (id, tenant_id, name, email, role, active, created_at, updated_at) VALUES ('ed15f5ff-186a-4ef4-8629-82a3292ec8c6', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SpeedWheels Auto Service Center Support Agent', 'agent@speedwheels.com', 'AGENT', true, '2026-07-08 21:13:36.605576', '2026-07-08 21:13:36.605576');
INSERT INTO public.tenant_agents (id, tenant_id, name, email, role, active, created_at, updated_at) VALUES ('951602ec-81eb-4858-8234-f5a4424ff57a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SpeedWheels Auto Service Center Manager', 'manager@speedwheels.com', 'MANAGER', true, '2026-07-08 21:13:36.605576', '2026-07-08 21:13:36.605576');


--
-- Data for Name: tenant_features; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('fbf97f5d-1707-45c7-ba37-6a0e477813df', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'BYO_MEDIA_STORAGE', false, NULL, NULL, NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('ea1c8ec6-3da9-4b31-a356-b0d5fd1bf660', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'BYO_MEDIA_STORAGE', false, NULL, NULL, NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('bb882a7c-64da-4e6e-b255-c82ad6a06884', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'BYO_DOCUMENT_STORAGE', false, NULL, NULL, NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('bc9df2e4-90e0-470a-8aba-c251e92bee1b', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'BYO_DOCUMENT_STORAGE', false, NULL, NULL, NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('ca42a26c-fef2-4d4f-82b4-86795a613930', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'CUSTOMER_KMS', false, NULL, NULL, NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('77ec38ac-f9a4-4ab8-97b0-2c5afb4cfeae', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'CUSTOMER_KMS', false, NULL, NULL, NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('00741e12-8430-4a49-a160-f5e15b4bffae', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'WHATSAPP_BOT', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('3d54eb58-a6fe-43e9-9b1e-6a69ca54332a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'WHATSAPP_BOT', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('6df77eb7-5aae-4c10-bbf5-f7d46daee3d6', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'CRM_DASHBOARD', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('258922e9-3494-4095-9de3-defa80d0b6e0', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'CRM_DASHBOARD', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('dfc40d22-ddea-4dd9-a4b7-f3102df39b41', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'CAMPAIGNS', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('475393a8-ab58-4a31-88f6-9a05ed1be04d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'CAMPAIGNS', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('36c179b3-f85e-4593-9d7c-f740a7a2b421', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'DOCUMENT_CONTROL', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('608a714e-94b0-4291-a89d-82ae6d8d33ac', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'DOCUMENT_CONTROL', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('2454ea07-e3b2-4cbf-bea3-d1a6bcfdc0ea', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'ZERO_KNOWLEDGE_STORAGE', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('dc9aa4c5-7c88-4031-8e5b-c4fe84b2de7b', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'ZERO_KNOWLEDGE_STORAGE', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('a685e3aa-2bab-40a9-8cee-4e591075f0c0', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'DOCUMENT_AI_ANALYZER', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('e74f29e2-70ed-41c6-8266-896c20b31097', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'DOCUMENT_AI_ANALYZER', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('a9df9571-1dcc-4c14-b035-6868495f9eb8', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'AI_TREND_PICKER', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('09c4eac7-e4df-496f-87a7-76b0141a718c', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'AI_TREND_PICKER', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('d918e075-87a4-44d7-922a-d7d477e32626', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'AI_CONTENT_GENERATOR', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('18f0ac5b-bb1d-427c-b3d1-b7725b6d8c67', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'AI_CONTENT_GENERATOR', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('9bfebed5-ecee-4487-ad8e-b25eb7bb9244', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'MEDIA_LIBRARY', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('b831caa6-e6d8-4111-b3c0-6b4c8b3dcf25', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'MEDIA_LIBRARY', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('b7681a01-712e-4561-b4ea-2b833bd0075c', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'VIDEO_TEMPLATE_ENGINE', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('d329fefb-4573-4419-85b4-eb05d3a9b2e8', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'VIDEO_TEMPLATE_ENGINE', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('d1d795c4-e1b8-47c7-af23-441820d64db4', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'SCHEDULED_PUBLISHING', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('21bc2550-ff0a-4ccd-a6e0-f7349d7e7676', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SCHEDULED_PUBLISHING', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('e4921434-5d30-45d2-95c5-f4bb24ae4136', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'INSTAGRAM_PUBLISHING', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('b6daeb28-37d8-4a9e-90d3-69a0defd4e9a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'INSTAGRAM_PUBLISHING', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('0d12ce60-c309-495a-a803-b557b10a839b', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'YOUTUBE_PUBLISHING', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('f989e92b-9991-4d1f-9ea5-815200d9791f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'YOUTUBE_PUBLISHING', true, NULL, '2026-07-08 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('8557b5ac-817b-496f-a0ea-5599146ca295', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'LEAD_INTELLIGENCE', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('808f4693-05b3-447a-b732-f2279cc1ec74', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PLATFORM_INTEGRATIONS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('bf9992b4-00d3-458c-b50f-dad90b887ce1', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'LEARNING_INSIGHTS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('11b8be96-f5a6-403a-b15c-53d904251a60', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'CONTENT_APPROVALS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('d2253f0b-d70a-49a8-bfa0-424058775172', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'SETTINGS_SOCIAL', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('17d7e82f-726f-4c83-89e1-f57ff151e5bf', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'SETTINGS_STORAGE', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('7c32d5bb-b6bc-41c5-aef9-225a842e5aed', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_CONTROL_SUITE', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('81b1c1e1-d525-41e3-95e6-1fbb6707b15f', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_OVERVIEW', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('91050782-3f3d-4ca7-9998-ec703fcf34d3', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_DOCUMENTS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('4a41dc8b-d1cf-4e8a-832e-3618099608b8', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_UPLOAD_LINKS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('129dbcea-bd91-4e1f-840b-d5cb187b97aa', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_CONTROLS_CORE', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('c4111228-cf93-4c3d-86c2-6f92d5ced714', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_RESOURCE_COST', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('4b3ed3c5-eace-4574-8385-7535929ee97e', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_COMMITMENTS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('7a37e02e-2465-41a7-bb05-4ac4b7a6c131', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_FORECASTING', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('ff49c24c-1b3b-40dc-9b6c-04e5c52cb331', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_WORKFLOWS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('e48d66b6-09e0-4ee0-9a15-71c2498cbf87', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_TRANSMITTALS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('f165c1f9-3f2b-469d-9481-477a1b86ef71', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_APPROVALS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('4fe017e8-2aa0-4aa2-b239-b250796318e7', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_SECURITY', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('48a63fbe-14d7-4d42-bd93-e8aa6162222b', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_BUDGET_IPC', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('8888adb7-a2b8-445a-9a18-cabceed71a9c', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_AI_INSIGHTS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('5cb84890-aa71-47fb-80c0-91fa57bbc4eb', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_AUDIT', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('69a58d18-c6fd-4f87-9be4-0afe454aff26', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'PROJECT_NOTIFICATIONS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('e9f70574-0ca8-40ad-a885-b44e3632d978', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'LEAD_INTELLIGENCE', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('a87ec7c1-888d-4f26-99bb-2b5db24401b7', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PLATFORM_INTEGRATIONS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('5f202a83-4295-4924-abad-2ed938b2edce', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'LEARNING_INSIGHTS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('aded4fbd-801c-4de1-89c0-22a99f8cdf4d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'CONTENT_APPROVALS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('276f397c-6c11-4632-895f-eb86fc0814f1', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SETTINGS_SOCIAL', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('ad857e94-37ff-4a5e-ba32-f62d8c6b9d12', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'SETTINGS_STORAGE', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('1c397a74-5cbd-40d6-95a6-3a38b491327f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_CONTROL_SUITE', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('1a8b0870-dda8-400b-8cd7-f5a0e1326e8d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_OVERVIEW', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('4aa6d8f7-76f3-4cdd-abc9-6885f7250667', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_DOCUMENTS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('fd67d46f-484d-443d-9efa-7f3d82ac1658', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_UPLOAD_LINKS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('92cce5e4-3675-4714-a4a3-664a39455834', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_CONTROLS_CORE', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('cb81dcf9-a034-4dcf-8664-a0c61ef8a8eb', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_RESOURCE_COST', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('d6f69609-d1ae-4723-b784-ef3addf7f512', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_COMMITMENTS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('df5a5df0-f320-42ea-ac67-a80c775b4ccf', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_FORECASTING', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('4c9304c6-245b-4a7f-8be3-1a1d48b24509', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_WORKFLOWS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('2e17bde3-659f-4995-a19b-afdf026ebb4d', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_TRANSMITTALS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('d1b992e3-8249-40b8-911e-07cc9c19250f', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_APPROVALS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('470dab0a-b498-4bcb-ba7b-1033a72da6ad', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_SECURITY', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('f66132fb-f8f7-46f4-8d1d-c701ab62c492', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_BUDGET_IPC', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('e6d55a06-8607-44c9-8aae-f7f7dd13c143', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_AI_INSIGHTS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('6ba40d72-1893-4930-8af3-fe23bd8a9352', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_AUDIT', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');
INSERT INTO public.tenant_features (id, tenant_id, feature_code, enabled, config, enabled_at, disabled_at, updated_at) VALUES ('9f064456-10bf-4a93-b433-39eb15ab4ff0', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'PROJECT_NOTIFICATIONS', true, NULL, '2026-08-08 22:17:04.246961', NULL, '2026-08-08 22:17:04.246961');


--
-- Data for Name: tenant_notification_contacts; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: tenant_saved_trends; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: tenant_subscriptions; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.tenant_subscriptions (id, tenant_id, plan_code, status, started_at, expires_at, trial_ends_at, cancelled_at, updated_at) VALUES ('26b83faf-4b91-4ba7-8833-c8a7c0b6b396', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'BUSINESS', 'TRIAL', '2026-07-08 21:13:36.117388', NULL, '2026-08-07 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');
INSERT INTO public.tenant_subscriptions (id, tenant_id, plan_code, status, started_at, expires_at, trial_ends_at, cancelled_at, updated_at) VALUES ('fdbeaebf-cff8-4a36-92f9-3f3eb5511f6a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'BUSINESS', 'TRIAL', '2026-07-08 21:13:36.117388', NULL, '2026-08-07 21:13:36.117388', NULL, '2026-07-08 21:13:36.117388');


--
-- Data for Name: tenant_usage_daily; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: timesheets; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: trend_sources; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: trend_signals; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: video_scripts; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: webhook_outbox; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.webhook_outbox (id, payload, status, retry_count, error_message, created_at, processed_at) VALUES ('9e404834-48c3-4bea-aadc-adeb23e7fee5', '{"entry": [{"id": "MOCK_WABA_ID", "changes": [{"field": "messages", "value": {"contacts": [{"wa_id": "971529999001", "profile": {"name": "E2E Test Customer"}}], "messages": [{"id": "wamid.E2E-TEXT-0001", "from": "971529999001", "text": {"body": "Hi, I would like to book an oil change for my car"}, "type": "text", "timestamp": "1751900000"}], "metadata": {"phone_number_id": "REPLACE_WITH_META_PHONE_NUMBER_ID", "display_phone_number": "971500000000"}, "messaging_product": "whatsapp"}}]}], "object": "whatsapp_business_account"}', 'FAILED', 3, 'No active tenant configured for phone_number_id=REPLACE_WITH_META_PHONE_NUMBER_ID', '2026-07-08 21:47:29.93702', '2026-07-08 21:47:32.122713');
INSERT INTO public.webhook_outbox (id, payload, status, retry_count, error_message, created_at, processed_at) VALUES ('8873055d-27a3-4c28-8060-ddd02cd3e457', '{"entry": [{"id": "MOCK_WABA_ID", "changes": [{"field": "messages", "value": {"contacts": [{"wa_id": "971529999001", "profile": {"name": "E2E Test Customer"}}], "messages": [{"id": "wamid.E2E-TEXT-0001", "from": "971529999001", "text": {"body": "Hi, I would like to book an oil change for my car"}, "type": "text", "timestamp": "1751900000"}], "metadata": {"phone_number_id": "104824432320753", "display_phone_number": "971500000000"}, "messaging_product": "whatsapp"}}]}], "object": "whatsapp_business_account"}', 'DONE', 0, NULL, '2026-07-08 21:52:14.710072', '2026-07-08 21:52:24.913275');
INSERT INTO public.webhook_outbox (id, payload, status, retry_count, error_message, created_at, processed_at) VALUES ('c778cfbe-afbd-4606-8d15-8f34dd1ba0df', '{"entry": [{"id": "MOCK_WABA_ID", "changes": [{"field": "messages", "value": {"contacts": [{"wa_id": "971529999001", "profile": {"name": "E2E Test Customer"}}], "messages": [{"id": "wamid.E2E-IMAGE-0002", "from": "971529999001", "type": "image", "image": {"id": "MOCK_MEDIA_ID", "mime_type": "image/jpeg"}, "timestamp": "1751900060"}], "metadata": {"phone_number_id": "104824432320753", "display_phone_number": "971500000000"}, "messaging_product": "whatsapp"}}]}], "object": "whatsapp_business_account"}', 'DONE', 0, NULL, '2026-07-08 21:53:27.608058', '2026-07-08 21:53:28.027205');


--
-- Data for Name: whatsapp_button_replies; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.whatsapp_button_replies (id, tenant_id, button_id, button_title, reply_kind, reply_text, tool_name, tool_arguments_json, sort_order, description, active, created_at, updated_at) VALUES ('03b55178-8a9f-4a39-a1f0-76e964f700fd', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'book_service', 'Book Service', 'TEXT', 'Sure — let''s get your service booked. Which service do you need (e.g. oil change, brake check, full inspection), and what date works best for you?', NULL, NULL, 1, 'Customer wants to book an automobile service appointment.', true, '2026-07-08 21:13:35.244569', '2026-07-08 21:13:35.244569');
INSERT INTO public.whatsapp_button_replies (id, tenant_id, button_id, button_title, reply_kind, reply_text, tool_name, tool_arguments_json, sort_order, description, active, created_at, updated_at) VALUES ('a38f4a62-cc2d-4e97-8a71-700c0ca6372a', 'c57b30d3-9cdb-49d6-b763-2e0b4e3cb04c', 'ask_question', 'Ask a Question', 'TEXT', 'What would you like to know? You can ask about hours, pricing, or services.', NULL, NULL, 2, 'Customer has a general question, not yet specified.', true, '2026-07-08 21:13:35.244569', '2026-07-08 21:13:35.244569');


--
-- Data for Name: whatsapp_flow_registry; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: whatsapp_flow_submissions; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: whatsapp_interactive_messages; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: whatsapp_message_templates; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.whatsapp_message_templates (id, tenant_id, template_code, meta_template_name, language_code, category, audience, description, body_preview, component_schema_json, default_components_json, enabled_for_ai, active, created_at, updated_at) VALUES ('e14831cd-dff9-48b0-999f-dc59e93cdbef', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'customer_welcome', 'customer_welcome', 'en', 'UTILITY', 'CUSTOMER', 'Welcome or re-engagement template for a customer.', 'Hi {{1}}, welcome to {{2}}. How can we help you today?', '{"body":["customer_name","business_name"],"buttons":[],"allowedUse":"customer welcome and re-engagement"}', NULL, true, true, '2026-07-08 21:13:34.694095', '2026-07-08 21:13:34.694095');
INSERT INTO public.whatsapp_message_templates (id, tenant_id, template_code, meta_template_name, language_code, category, audience, description, body_preview, component_schema_json, default_components_json, enabled_for_ai, active, created_at, updated_at) VALUES ('9d6407db-63d3-46b9-ac42-84ad8a598fa2', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'order_status_update', 'order_status_update', 'en', 'UTILITY', 'CUSTOMER', 'Order status update to a customer.', 'Hi {{1}}, your order {{2}} is now {{3}}.', '{"body":["customer_name","order_number","status"],"allowedUse":"order status updates only"}', NULL, true, true, '2026-07-08 21:13:34.694095', '2026-07-08 21:13:34.694095');
INSERT INTO public.whatsapp_message_templates (id, tenant_id, template_code, meta_template_name, language_code, category, audience, description, body_preview, component_schema_json, default_components_json, enabled_for_ai, active, created_at, updated_at) VALUES ('afb37b9a-6cff-46bb-98af-26fbbfa23253', '25d07c9c-c4b0-43b5-b6ce-de6945cc5fd3', 'business_new_lead_alert', 'business_new_lead_alert', 'en', 'UTILITY', 'BUSINESS_CONTACT', 'Internal business alert when a customer asks for human help or creates a lead.', 'New WhatsApp lead: {{1}} - {{2}}', '{"body":["customer_phone","summary"],"allowedUse":"notify business staff only"}', NULL, true, true, '2026-07-08 21:13:34.694095', '2026-07-08 21:13:34.694095');


--
-- Data for Name: whatsapp_orders; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: whatsapp_order_items; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: whatsapp_template_send_audit; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: workflow_notification_outbox; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: workflow_in_app_notifications; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: workflow_notification_deliveries; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- PostgreSQL database dump complete
--


SET session_replication_role = DEFAULT;
