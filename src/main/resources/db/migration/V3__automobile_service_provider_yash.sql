-- =========================================================
-- V3 - Add personal test customer to SpeedWheels (AUTOMOBILE tenant)
-- Adds Yaswanth (971521022707) as a real contact + vehicle so the
-- automobile tools (lookup, vehicle history, booking) can be exercised
-- end-to-end against a real WhatsApp number during local/dev testing.
--
-- This is a NEW migration, not an edit to V2 — V2 has already been
-- applied in every environment that ran it, and editing an applied
-- migration breaks Flyway's checksum validation (flyway_schema_history).
-- =========================================================

-- =========================================================
-- Customer
-- =========================================================

insert into contacts (tenant_id, wa_id, phone_number, display_name, last_seen_at)
select t.id, c.wa_id, c.phone_number, c.display_name, now() - (c.days_since_seen || ' days')::interval
from tenants t
         cross join (values
                         ('971521022707', '971521022707', 'Yaswanth', 0)
) as c(wa_id, phone_number, display_name, days_since_seen)
where t.tenant_code = 'speedwheels'
    on conflict (tenant_id, wa_id) do update
                                          set phone_number = excluded.phone_number,
                                          display_name = excluded.display_name,
                                          last_seen_at = excluded.last_seen_at,
                                          updated_at = now();

-- =========================================================
-- Vehicle
-- =========================================================

insert into vehicles (tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata)
select t.id, c.id, v.make, v.model, v.model_year, v.plate_number, v.vin, v.color, '{"seed": true}'::jsonb
from tenants t
         join contacts c on c.tenant_id = t.id
         join (values
                   ('971521022707', 'Toyota', 'Land Cruiser', 2022, 'DXB Y-90909', 'JTMHV05J504000009', 'White')
) as v(phone_number, make, model, model_year, plate_number, vin, color)
              on c.phone_number = v.phone_number
where t.tenant_code = 'speedwheels'
    on conflict (tenant_id, plate_number) do nothing;

-- =========================================================
-- Service history (one prior visit, so vehicle-history lookups have
-- something real to return)
-- =========================================================

insert into service_records (
    tenant_id, vehicle_id, contact_id, service_type, description, technician_name,
    mileage_at_service, cost, currency, notes, service_date, next_service_date, status, metadata
)
select t.id,
       v.id,
       c.id,
       s.service_type,
       s.description,
       s.technician_name,
       s.mileage_at_service,
       s.cost,
       'AED',
       s.notes,
       now() - (s.days_ago || ' days')::interval,
    case when s.next_due_days is null then null else now() + (s.next_due_days || ' days')::interval end,
       'COMPLETED',
       '{"seed": true}'::jsonb
from tenants t
    join contacts c on c.tenant_id = t.id
    join vehicles v on v.tenant_id = t.id and v.contact_id = c.id
    join (values
    ('DXB Y-90909', 'OIL_CHANGE', 'Full synthetic oil and filter replacement', 'Ravi Kumar', 18000, 249.00::numeric, 'No issues found. Cabin filter checked.', 90, 90)
    ) as s(plate_number, service_type, description, technician_name, mileage_at_service, cost, notes, days_ago, next_due_days)
    on v.plate_number = s.plate_number
where t.tenant_code = 'speedwheels'
  and c.phone_number = '971521022707';