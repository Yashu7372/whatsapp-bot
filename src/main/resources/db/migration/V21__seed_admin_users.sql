-- V21: Seed default admin users for all active tenants
-- Password: admin123 (bcrypt $2b$12$)
-- Each tenant gets an admin@{tenant_code}.com account so local dev works out of the box.

INSERT INTO tenant_users (tenant_id, email, password_hash, full_name, role)
SELECT id,
       'admin@' || tenant_code || '.com',
       '$2b$12$iCcSmLWyQVNu2yHQqTc/wOPP.3drhg5/Dj4SvR/JnWY7OlQBZBQm2',
       business_name || ' Admin',
       'ADMIN'
FROM tenants
ON CONFLICT DO NOTHING;
