-- Gives PROJECT_TIME_LOG (added in V40) a nav route now that the frontend page exists, so it
-- shows up in /me/nav (and therefore the sidebar) for every role automatically.
UPDATE public.feature_catalog
SET route = '/control/time-log'
WHERE feature_code = 'PROJECT_TIME_LOG';
