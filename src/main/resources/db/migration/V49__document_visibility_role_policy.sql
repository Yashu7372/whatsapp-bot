-- ============================================================================
-- V49 - Business-document visibility and approval-decision role policy
--
-- System ADMIN manages the application/tenant; it is not a business-document
-- reader or approver. MANAGER retains document visibility/management. REVIEWER
-- may view document content selected by the document authorization policy but
-- does not receive general document-management permission.
--
-- Approval decisions are split from the broad DOCUMENT_CONTROL MANAGE action.
-- This allows an assigned REVIEWER to decide the current workflow step without
-- also granting POST/PATCH/DELETE authority across /api/v1/documents.
-- ============================================================================

INSERT INTO public.role_permissions(role,feature_code,action,allowed) VALUES
 ('ADMIN','DOCUMENT_CONTROL','VIEW',false),
 ('ADMIN','DOCUMENT_CONTROL','MANAGE',false),
 ('ADMIN','PROJECT_DOCUMENTS','VIEW',false),
 ('ADMIN','PROJECT_DOCUMENTS','MANAGE',false),
 ('ADMIN','PROJECT_APPROVALS','VIEW',false),
 ('ADMIN','PROJECT_APPROVALS','MANAGE',false),

 ('MANAGER','DOCUMENT_CONTROL','VIEW',true),
 ('MANAGER','DOCUMENT_CONTROL','MANAGE',true),
 ('MANAGER','PROJECT_DOCUMENTS','VIEW',true),
 ('MANAGER','PROJECT_DOCUMENTS','MANAGE',true),
 ('MANAGER','PROJECT_APPROVALS','VIEW',true),

 ('REVIEWER','DOCUMENT_CONTROL','VIEW',true),
 ('REVIEWER','DOCUMENT_CONTROL','MANAGE',false),
 ('REVIEWER','PROJECT_DOCUMENTS','VIEW',true),
 ('REVIEWER','PROJECT_DOCUMENTS','MANAGE',false),
 ('REVIEWER','PROJECT_APPROVALS','VIEW',true)
ON CONFLICT(role,feature_code,action)
DO UPDATE SET allowed=excluded.allowed;

-- No navigation route: this is an action-level permission used only for the
-- approval decision endpoint. Service-level authorization still verifies that
-- the caller is the currently assigned USER / ORGANIZATION / PARTY_ROLE.
INSERT INTO public.feature_catalog(
    feature_code,module,nav_section,nav_label,nav_icon,route,min_role,is_core,sort_order,created_at
)
VALUES(
    'PROJECT_APPROVAL_DECISION','PROJECT_CONTROL',NULL,'Approval Decision',NULL,NULL,'REVIEWER',true,999,now()
)
ON CONFLICT(feature_code) DO UPDATE SET
    module=excluded.module,
    nav_section=excluded.nav_section,
    nav_label=excluded.nav_label,
    nav_icon=excluded.nav_icon,
    route=excluded.route,
    min_role=excluded.min_role,
    is_core=excluded.is_core,
    sort_order=excluded.sort_order;

INSERT INTO public.feature_api_path(feature_code,path_pattern)
VALUES('PROJECT_APPROVAL_DECISION','^/api/v1/documents/approvals/[^/]+/decide$')
ON CONFLICT(path_pattern) DO UPDATE SET feature_code=excluded.feature_code;

INSERT INTO public.role_permissions(role,feature_code,action,allowed) VALUES
 ('ADMIN','PROJECT_APPROVAL_DECISION','VIEW',false),
 ('ADMIN','PROJECT_APPROVAL_DECISION','MANAGE',false),
 ('MANAGER','PROJECT_APPROVAL_DECISION','VIEW',true),
 ('MANAGER','PROJECT_APPROVAL_DECISION','MANAGE',true),
 ('REVIEWER','PROJECT_APPROVAL_DECISION','VIEW',true),
 ('REVIEWER','PROJECT_APPROVAL_DECISION','MANAGE',true),
 ('VIEWER','PROJECT_APPROVAL_DECISION','VIEW',false),
 ('VIEWER','PROJECT_APPROVAL_DECISION','MANAGE',false)
ON CONFLICT(role,feature_code,action)
DO UPDATE SET allowed=excluded.allowed;
