-- ============================================================================
-- V52 - Project Control v2.1 generalized scope foundation
--
-- The existing Stage -> Work Package hierarchy remains intact for compatibility,
-- but Project Scope becomes the contextual project structure. The initial physical
-- model is deliberately relational: configurable scope types, an adjacency-list
-- scope tree, capability bindings and scope assignments. No generic graph/runtime.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.project_scope_types (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    code varchar(80) NOT NULL,
    name varchar(160) NOT NULL,
    category varchar(80) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,
    configuration_schema_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(40) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uk_project_scope_type_code UNIQUE(tenant_id, code)
);

CREATE TABLE IF NOT EXISTS public.project_scopes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    parent_scope_id uuid REFERENCES public.project_scopes(id) ON DELETE RESTRICT,
    scope_type_id uuid NOT NULL REFERENCES public.project_scope_types(id) ON DELETE RESTRICT,
    code varchar(100) NOT NULL,
    name varchar(300) NOT NULL,
    description text,
    owner_organization_id uuid REFERENCES public.organizations(id) ON DELETE SET NULL,
    status varchar(40) NOT NULL DEFAULT 'ACTIVE',
    planned_start date,
    planned_finish date,
    actual_start date,
    actual_finish date,
    sort_order integer NOT NULL DEFAULT 0,
    configuration_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_project_scopes_project
    ON public.project_scopes(tenant_id, project_id, parent_scope_id, sort_order, code);
CREATE INDEX IF NOT EXISTS idx_project_scopes_type
    ON public.project_scopes(tenant_id, scope_type_id, status);

CREATE TABLE IF NOT EXISTS public.scope_capability_bindings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    scope_id uuid NOT NULL REFERENCES public.project_scopes(id) ON DELETE CASCADE,
    capability_code varchar(100) NOT NULL,
    mode varchar(20) NOT NULL DEFAULT 'ENABLED',
    configuration_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(40) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uk_scope_capability UNIQUE(scope_id, capability_code),
    CONSTRAINT ck_scope_capability_mode CHECK(mode IN ('ENABLED','DISABLED','INHERIT'))
);
CREATE INDEX IF NOT EXISTS idx_scope_capabilities_project
    ON public.scope_capability_bindings(tenant_id, project_id, scope_id, status);

CREATE TABLE IF NOT EXISTS public.scope_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    scope_id uuid NOT NULL REFERENCES public.project_scopes(id) ON DELETE CASCADE,
    assignee_type varchar(30) NOT NULL,
    assignee_id uuid NOT NULL,
    responsibility_code varchar(100) NOT NULL,
    assignment_type varchar(80) NOT NULL DEFAULT 'RESPONSIBLE',
    allocation_percent numeric(6,2),
    valid_from date,
    valid_to date,
    status varchar(40) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT ck_scope_assignment_type CHECK(assignee_type IN ('PERSON','ORGANIZATION','TEAM')),
    CONSTRAINT ck_scope_assignment_allocation CHECK(allocation_percent IS NULL OR (allocation_percent >= 0 AND allocation_percent <= 100))
);
CREATE INDEX IF NOT EXISTS idx_scope_assignments_scope
    ON public.scope_assignments(tenant_id, project_id, scope_id, status);
CREATE INDEX IF NOT EXISTS idx_scope_assignments_assignee
    ON public.scope_assignments(tenant_id, assignee_type, assignee_id, status);

-- Existing hierarchy rows receive direct typed links to their generalized scope.
ALTER TABLE public.project_stages ADD COLUMN IF NOT EXISTS scope_id uuid REFERENCES public.project_scopes(id) ON DELETE SET NULL;
ALTER TABLE public.work_packages ADD COLUMN IF NOT EXISTS scope_id uuid REFERENCES public.project_scopes(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_stages_scope ON public.project_stages(scope_id) WHERE scope_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_work_packages_scope ON public.work_packages(scope_id) WHERE scope_id IS NOT NULL;

-- Initial platform-supported categories are data, not a fixed project lifecycle.
INSERT INTO public.project_scope_types(tenant_id, code, name, category)
SELECT t.id, v.code, v.name, v.category
FROM public.tenants t
CROSS JOIN (VALUES
    ('PHASE','Phase','PHASE'),
    ('STAGE','Stage','STAGE'),
    ('WORKSTREAM','Workstream','WORKSTREAM'),
    ('PACKAGE','Package','PACKAGE'),
    ('CONTRACT_SCOPE','Contract Scope','CONTRACT_SCOPE'),
    ('DISCIPLINE','Discipline','DISCIPLINE'),
    ('ZONE','Zone','ZONE'),
    ('DELIVERABLE_GROUP','Deliverable Group','DELIVERABLE_GROUP'),
    ('ACTIVITY_GROUP','Activity Group','ACTIVITY_GROUP'),
    ('CUSTOM','Custom','CUSTOM')
) AS v(code,name,category)
ON CONFLICT(tenant_id, code) DO NOTHING;

-- Map current Stage rows into root scopes. Legacy source identity is retained only
-- as bounded migration metadata so no business capability depends on it.
INSERT INTO public.project_scopes(
    tenant_id, project_id, scope_type_id, code, name, status,
    planned_start, planned_finish, actual_start, actual_finish, sort_order,
    configuration_json)
SELECT s.tenant_id, s.project_id, st.id, s.stage_code, s.name, s.status,
       s.planned_start, s.planned_end, s.actual_start, s.actual_end, s.sequence_no,
       jsonb_build_object('legacySource','PROJECT_STAGE','legacyId',s.id::text)
FROM public.project_stages s
JOIN public.project_scope_types st ON st.tenant_id=s.tenant_id AND st.code='STAGE'
WHERE s.scope_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM public.project_scopes ps
      WHERE ps.tenant_id=s.tenant_id
        AND ps.project_id=s.project_id
        AND ps.configuration_json->>'legacySource'='PROJECT_STAGE'
        AND ps.configuration_json->>'legacyId'=s.id::text
  );

UPDATE public.project_stages s
SET scope_id=ps.id
FROM public.project_scopes ps
WHERE s.scope_id IS NULL
  AND ps.tenant_id=s.tenant_id
  AND ps.project_id=s.project_id
  AND ps.configuration_json->>'legacySource'='PROJECT_STAGE'
  AND ps.configuration_json->>'legacyId'=s.id::text;

-- Map Work Packages beneath their Stage scope.
INSERT INTO public.project_scopes(
    tenant_id, project_id, parent_scope_id, scope_type_id, code, name, status,
    sort_order, configuration_json)
SELECT w.tenant_id, w.project_id, s.scope_id, st.id, w.package_code, w.name, w.status,
       w.sort_order,
       jsonb_build_object('legacySource','WORK_PACKAGE','legacyId',w.id::text,'discipline',w.discipline)
FROM public.work_packages w
JOIN public.project_stages s ON s.id=w.stage_id AND s.tenant_id=w.tenant_id AND s.project_id=w.project_id
JOIN public.project_scope_types st ON st.tenant_id=w.tenant_id AND st.code='PACKAGE'
WHERE w.scope_id IS NULL
  AND s.scope_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM public.project_scopes ps
      WHERE ps.tenant_id=w.tenant_id
        AND ps.project_id=w.project_id
        AND ps.configuration_json->>'legacySource'='WORK_PACKAGE'
        AND ps.configuration_json->>'legacyId'=w.id::text
  );

UPDATE public.work_packages w
SET scope_id=ps.id
FROM public.project_scopes ps
WHERE w.scope_id IS NULL
  AND ps.tenant_id=w.tenant_id
  AND ps.project_id=w.project_id
  AND ps.configuration_json->>'legacySource'='WORK_PACKAGE'
  AND ps.configuration_json->>'legacyId'=w.id::text;

-- Keep the new resource-oriented endpoints under the existing Project Delivery feature gate.
INSERT INTO public.feature_api_path(feature_code, path_pattern)
VALUES
  ('PROJECT_DELIVERY','^/api/v1/projects/[0-9a-fA-F-]+/scopes'),
  ('PROJECT_DELIVERY','^/api/v1/project-scope-types')
ON CONFLICT(path_pattern) DO UPDATE SET feature_code=excluded.feature_code;
