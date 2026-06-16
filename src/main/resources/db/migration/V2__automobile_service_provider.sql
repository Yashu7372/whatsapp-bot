-- =========================================================
-- V2 - Automobile Service Provider Tenant
-- Adds SpeedWheels Auto Service domain data for WhatsApp AI.
-- =========================================================

-- Duplicate webhook protection. The service still checks early, but this makes it safe under concurrent redelivery.
create unique index if not exists ux_messages_wa_message_id
    on messages (wa_message_id)
    where wa_message_id is not null;

-- Avoid duplicate seed knowledge documents if this seed is re-applied manually.
create unique index if not exists ux_knowledge_documents_seed_title
    on knowledge_documents (tenant_id, title, source_type)
    where metadata ->> 'seed' = 'true';

-- =========================================================
-- Automobile domain tables
-- =========================================================

create table if not exists vehicles (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    contact_id uuid not null references contacts(id),
    make varchar(100) not null,
    model varchar(100) not null,
    model_year int,
    plate_number varchar(50) not null,
    vin varchar(100),
    color varchar(80),
    metadata jsonb,
    active boolean not null default true,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint uk_vehicles_tenant_plate unique (tenant_id, plate_number)
);

create index if not exists idx_vehicles_tenant_contact
    on vehicles(tenant_id, contact_id);

create index if not exists idx_vehicles_tenant_active
    on vehicles(tenant_id, active);

create table if not exists service_records (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    vehicle_id uuid not null references vehicles(id),
    contact_id uuid not null references contacts(id),
    service_type varchar(100) not null,
    description text not null,
    technician_name varchar(150),
    mileage_at_service int,
    cost numeric(12,2),
    currency varchar(10) default 'AED',
    notes text,
    service_date timestamp not null,
    next_service_date timestamp,
    status varchar(50) not null default 'COMPLETED',
    metadata jsonb,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

create index if not exists idx_service_records_vehicle_date
    on service_records(vehicle_id, service_date desc);

create index if not exists idx_service_records_contact_date
    on service_records(contact_id, service_date desc);

create index if not exists idx_service_records_tenant_type
    on service_records(tenant_id, service_type);

create table if not exists service_appointments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    contact_id uuid references contacts(id),
    vehicle_id uuid references vehicles(id),
    service_type varchar(100) not null default 'ANY',
    appointment_date date not null,
    time_slot varchar(30) not null,
    status varchar(50) not null default 'AVAILABLE',
    customer_phone varchar(50),
    customer_name varchar(200),
    notes text,
    metadata jsonb,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint uk_service_appointments_slot unique (tenant_id, appointment_date, time_slot),
    constraint chk_service_appointments_status check (status in ('AVAILABLE', 'BOOKED', 'COMPLETED', 'CANCELLED'))
);

create index if not exists idx_service_appointments_tenant_date_status
    on service_appointments(tenant_id, appointment_date, status);

create index if not exists idx_service_appointments_contact
    on service_appointments(contact_id);

create index if not exists idx_service_appointments_vehicle
    on service_appointments(vehicle_id);

-- =========================================================
-- Tenant setup
-- Keep one test Meta phone number active for SpeedWheels.
-- =========================================================

update tenants
set tenant_code = 'tastybites',
    business_name = 'Tasty Bites Restaurant',
    phone_number_id = 'INACTIVE_TASTYBITES',
    active = false,
    updated_at = now()
where tenant_code = 'localbites';

insert into tenants (
    tenant_code,
    business_name,
    business_type,
    phone_number_id,
    waba_id,
    access_token_encrypted,
    system_prompt,
    default_language,
    timezone,
    active
) values (
    'speedwheels',
    'SpeedWheels Auto Service Center',
    'AUTOMOBILE',
    'REPLACE_WITH_META_PHONE_NUMBER_ID',
    'REPLACE_WITH_WABA_ID',
    null,
    'You are the WhatsApp assistant for SpeedWheels Auto Service Center in Dubai. Be proactive and conversational. First, identify returning customers using their phone number and mention their registered vehicles when available. Use automobile tools for customer lookup, vehicle history, service recommendations, appointment slots, booking, and cancellation. Ask clear follow-up questions about symptoms, recent services, mileage, vehicle plate number, preferred date, and preferred time. Do not invent prices, service history, warranty terms, or appointment availability. For booking, confirm service type, vehicle, date, and exact slot. If the customer asks for a human agent, reply exactly HUMAN_HANDOFF_REQUIRED.',
    'en',
    'Asia/Dubai',
    true
)
on conflict (tenant_code) do update
set business_name = excluded.business_name,
    business_type = excluded.business_type,
    phone_number_id = excluded.phone_number_id,
    waba_id = excluded.waba_id,
    system_prompt = excluded.system_prompt,
    default_language = excluded.default_language,
    timezone = excluded.timezone,
    active = true,
    updated_at = now();

-- =========================================================
-- Customers
-- =========================================================

insert into contacts (tenant_id, wa_id, phone_number, display_name, last_seen_at)
select t.id, c.wa_id, c.phone_number, c.display_name, now() - (c.days_since_seen || ' days')::interval
from tenants t
cross join (values
    ('971501234567', '971501234567', 'Ahmed Al Rashid', 3),
    ('971502345678', '971502345678', 'Fatima Hassan', 8),
    ('971503456789', '971503456789', 'John Smith', 15),
    ('971504567890', '971504567890', 'Priya Sharma', 1),
    ('971505678901', '971505678901', 'Mohammed Ali', 30),
    ('971506789012', '971506789012', 'Sarah Johnson', 5)
) as c(wa_id, phone_number, display_name, days_since_seen)
where t.tenant_code = 'speedwheels'
on conflict (tenant_id, wa_id) do update
set phone_number = excluded.phone_number,
    display_name = excluded.display_name,
    last_seen_at = excluded.last_seen_at,
    updated_at = now();

-- =========================================================
-- Vehicles
-- =========================================================

insert into vehicles (tenant_id, contact_id, make, model, model_year, plate_number, vin, color, metadata)
select t.id, c.id, v.make, v.model, v.model_year, v.plate_number, v.vin, v.color, '{"seed": true}'::jsonb
from tenants t
join contacts c on c.tenant_id = t.id
join (values
    ('971501234567', 'Toyota', 'Camry', 2021, 'DXB A-12345', 'JTNB11HK1M3000001', 'White'),
    ('971502345678', 'Honda', 'Civic', 2020, 'DXB B-23456', 'MRHFC1660LT000002', 'Silver'),
    ('971503456789', 'BMW', 'X5', 2022, 'DXB C-34567', 'WBACR6109N9000003', 'Black'),
    ('971504567890', 'Nissan', 'Patrol', 2019, 'DXB D-45678', 'JN1TANY62K0000004', 'Gold'),
    ('971504567890', 'Mercedes-Benz', 'C200', 2023, 'DXB E-56789', 'W1KAF4GB1PN000005', 'Blue'),
    ('971505678901', 'Hyundai', 'Tucson', 2021, 'DXB F-67890', 'KM8J33A45MU000006', 'Grey'),
    ('971506789012', 'Ford', 'Mustang', 2022, 'DXB G-78901', '1FA6P8TH5N5000007', 'Red'),
    ('971506789012', 'Kia', 'Sportage', 2020, 'DXB H-89012', 'KNDPMCAC5L7000008', 'White')
) as v(phone_number, make, model, model_year, plate_number, vin, color)
    on c.phone_number = v.phone_number
where t.tenant_code = 'speedwheels'
on conflict (tenant_id, plate_number) do nothing;

-- =========================================================
-- Service history
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
    ('DXB A-12345', 'OIL_CHANGE', 'Full synthetic oil and filter replacement', 'Ravi Kumar', 42000, 189.00::numeric, 'Cabin filter checked. Engine running smooth.', 165, 15),
    ('DXB A-12345', 'TIRE_ROTATION_BALANCING', 'Tire rotation and wheel balancing', 'Hassan Noor', 39000, 130.00::numeric, 'Front tires moved to rear. No abnormal wear.', 230, null::int),
    ('DXB A-12345', 'BRAKE_SERVICE', 'Front brake pad replacement', 'Omar Khalid', 35000, 520.00::numeric, 'Customer reported squeaking. Pads replaced and road tested.', 320, 220),
    ('DXB B-23456', 'AC_SERVICE_RECHARGE', 'AC gas recharge and cabin filter replacement', 'Anil Thomas', 61000, 380.00::numeric, 'Cooling performance restored.', 45, 320),
    ('DXB B-23456', 'OIL_CHANGE', 'Semi-synthetic oil and filter replacement', 'Ravi Kumar', 57000, 149.00::numeric, 'Next oil change due soon.', 175, 5),
    ('DXB C-34567', 'ENGINE_DIAGNOSTICS', 'Computer diagnostics and fault scan', 'Mark Wilson', 28000, 150.00::numeric, 'Minor oxygen sensor warning cleared after inspection.', 20, null::int),
    ('DXB C-34567', 'TRANSMISSION_SERVICE', 'Transmission fluid service for BMW X5', 'Sameer Khan', 25000, 700.00::numeric, 'Used European-spec fluid.', 150, 215),
    ('DXB C-34567', 'OIL_CHANGE', 'European full synthetic oil service', 'Ravi Kumar', 21500, 350.00::numeric, 'BMW-approved oil grade used.', 270, 95),
    ('DXB D-45678', 'BATTERY_REPLACEMENT', 'Battery test and replacement', 'Hassan Noor', 88000, 420.00::numeric, 'Old battery failed load test.', 70, null::int),
    ('DXB D-45678', 'WHEEL_ALIGNMENT', '4-wheel computerized alignment', 'Omar Khalid', 83500, 200.00::numeric, 'Steering pull corrected.', 190, null::int),
    ('DXB D-45678', 'OIL_CHANGE', 'Full synthetic oil and filter replacement', 'Ravi Kumar', 80000, 249.00::numeric, 'Large engine oil capacity.', 260, 100),
    ('DXB E-56789', 'FULL_VEHICLE_INSPECTION', 'Pre-warranty full vehicle inspection', 'Mark Wilson', 12000, 250.00::numeric, 'No major defects. Monitor brake wear.', 12, null::int),
    ('DXB E-56789', 'CAR_WASH', 'Premium wash and interior vacuum', 'Imran Ali', 11800, 150.00::numeric, 'Interior cleaned and dashboard dressed.', 12, null::int),
    ('DXB F-67890', 'BRAKE_SERVICE', 'Brake pad and rotor resurfacing', 'Omar Khalid', 51000, 850.00::numeric, 'Brake vibration resolved.', 85, 280),
    ('DXB F-67890', 'OIL_CHANGE', 'Full synthetic oil and filter replacement', 'Anil Thomas', 47000, 189.00::numeric, 'Recommended tire rotation next visit.', 185, 25),
    ('DXB G-78901', 'WHEEL_ALIGNMENT', '4-wheel alignment after tire replacement', 'Hassan Noor', 18000, 200.00::numeric, 'Alignment set to factory spec.', 35, null::int),
    ('DXB G-78901', 'OIL_CHANGE', 'Full synthetic oil service', 'Ravi Kumar', 15500, 249.00::numeric, 'Performance engine oil grade used.', 155, 25),
    ('DXB H-89012', 'AC_SERVICE_RECHARGE', 'AC service and leak check', 'Anil Thomas', 64000, 320.00::numeric, 'No leak found. Cooling improved.', 95, 265)
) as s(plate_number, service_type, description, technician_name, mileage_at_service, cost, notes, days_ago, next_due_days)
    on s.plate_number = v.plate_number
where t.tenant_code = 'speedwheels'
  and not exists (
      select 1
      from service_records existing
      where existing.tenant_id = t.id
        and existing.vehicle_id = v.id
        and existing.service_type = s.service_type
        and existing.description = s.description
  );

-- =========================================================
-- Appointment slots: Saturday-Thursday, 09:00-17:00, no 13:00 lunch slot.
-- Friday is closed in UAE policy for this tenant.
-- =========================================================

insert into service_appointments (tenant_id, service_type, appointment_date, time_slot, status, metadata)
select t.id,
       'ANY',
       d::date,
       slot.time_slot,
       'AVAILABLE',
       '{"seed": true}'::jsonb
from tenants t
cross join generate_series(current_date, current_date + interval '13 days', interval '1 day') as d
cross join (values
    ('09:00-10:00'),
    ('10:00-11:00'),
    ('11:00-12:00'),
    ('12:00-13:00'),
    ('14:00-15:00'),
    ('15:00-16:00'),
    ('16:00-17:00')
) as slot(time_slot)
where t.tenant_code = 'speedwheels'
  and extract(dow from d) <> 5
on conflict (tenant_id, appointment_date, time_slot) do nothing;

-- Seed a few existing bookings from the generated available slots.
with open_slots as (
    select a.id,
           row_number() over (order by a.appointment_date, a.time_slot) as rn
    from service_appointments a
    join tenants t on t.id = a.tenant_id
    where t.tenant_code = 'speedwheels'
      and a.status = 'AVAILABLE'
), booking_seed as (
    select * from (values
        (2, '971501234567', 'DXB A-12345', 'OIL_CHANGE', 'Ahmed Al Rashid'),
        (5, '971503456789', 'DXB C-34567', 'ENGINE_DIAGNOSTICS', 'John Smith'),
        (9, '971504567890', 'DXB E-56789', 'FULL_VEHICLE_INSPECTION', 'Priya Sharma')
    ) as b(rn, phone_number, plate_number, service_type, customer_name)
)
update service_appointments a
set contact_id = c.id,
    vehicle_id = v.id,
    service_type = b.service_type,
    status = 'BOOKED',
    customer_phone = c.phone_number,
    customer_name = b.customer_name,
    notes = 'Seed booking',
    updated_at = now()
from open_slots os
join booking_seed b on b.rn = os.rn
join tenants t on t.tenant_code = 'speedwheels'
join contacts c on c.tenant_id = t.id and c.phone_number = b.phone_number
join vehicles v on v.tenant_id = t.id and v.plate_number = b.plate_number
where a.id = os.id;

-- =========================================================
-- Knowledge documents
-- =========================================================

insert into knowledge_documents (tenant_id, title, document_type, source_type, content, metadata)
select t.id,
       'SpeedWheels Service Catalog',
       'POLICY',
       'SEED',
       'SpeedWheels Auto Service Center - Service Catalog:
1. Oil Change & Filter (OIL_CHANGE) - Semi-synthetic or full synthetic oil, filter replacement, fluid top-up, basic inspection. Duration: 30-45 minutes.
2. Brake Service (BRAKE_SERVICE) - Brake pad inspection/replacement, rotor inspection/resurfacing, brake fluid flush. Duration: 1-3 hours.
3. Tire Rotation & Balancing (TIRE_ROTATION_BALANCING) - Rotate tires, balance wheels, inspect tire wear and pressure. Duration: 45-60 minutes.
4. AC Service & Recharge (AC_SERVICE_RECHARGE) - AC performance check, gas recharge, cabin filter replacement if needed. Duration: 1-2 hours.
5. Battery Check & Replacement (BATTERY_REPLACEMENT) - Battery health test, alternator charging check, replacement if needed. Duration: 30-45 minutes.
6. Engine Diagnostics (ENGINE_DIAGNOSTICS) - Computer fault scan, warning light diagnosis, report with recommended repairs. Duration: 45-90 minutes.
7. Transmission Service (TRANSMISSION_SERVICE) - Transmission fluid and filter service depending on vehicle type. Duration: 2-3 hours.
8. Wheel Alignment (WHEEL_ALIGNMENT) - 4-wheel computerized alignment. Duration: 45-60 minutes.
9. Full Vehicle Inspection (FULL_VEHICLE_INSPECTION) - 60-point inspection for safety, leaks, brakes, tires, battery, fluids, AC, and diagnostics. Duration: 1-2 hours.
10. Car Wash & Detailing (CAR_WASH) - Exterior hand wash, interior vacuum and wipe, full detail packages available. Duration: 1-3 hours.',
       '{"seed": true}'::jsonb
from tenants t where t.tenant_code = 'speedwheels'
on conflict do nothing;

insert into knowledge_documents (tenant_id, title, document_type, source_type, content, metadata)
select t.id,
       'SpeedWheels Pricing Guide',
       'POLICY',
       'SEED',
       'SpeedWheels Auto Service Center - Pricing Guide (AED):
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
Prices are base prices and may vary depending on make, model, and specific requirements. European luxury vehicles such as BMW, Mercedes, and Audi may have higher prices due to specialized parts and fluids. All prices include labor.',
       '{"seed": true}'::jsonb
from tenants t where t.tenant_code = 'speedwheels'
on conflict do nothing;

insert into knowledge_documents (tenant_id, title, document_type, source_type, content, metadata)
select t.id,
       'SpeedWheels Business Hours and Policies',
       'BOOKING_RULES',
       'SEED',
       'SpeedWheels Auto Service Center - Hours & Policies:
Business Hours: Saturday to Thursday 8:00 AM to 6:00 PM. Friday closed. Public holidays closed and announced in advance.
Appointment Booking: Appointments can be booked up to 2 weeks in advance. Same-day appointments are subject to availability. Service slots are 1 hour each from 9:00 AM to 5:00 PM. Lunch break is 1:00 PM to 2:00 PM with no appointments.
Cancellation Policy: Free cancellation up to 4 hours before appointment. Late cancellations may affect future priority booking. If a customer misses 3 appointments, future bookings require confirmation call.
Drop-off Service: Customers can drop off vehicles outside business hours using the key drop box. A service advisor will contact the customer when the vehicle is assessed.
Pickup & Delivery: Available within 15 km radius for services over 500 AED. Pickup/delivery fee is 50 AED, waived for services over 1000 AED.
Warranty: 6-month warranty on labor. Parts warranty varies by manufacturer, typically 12-24 months. Warranty void if vehicle is serviced by unauthorized third party for the same component.
Payment Methods: Cash, Visa, Mastercard, Apple Pay, Samsung Pay. Corporate accounts are available for fleet customers.',
       '{"seed": true}'::jsonb
from tenants t where t.tenant_code = 'speedwheels'
on conflict do nothing;

insert into knowledge_documents (tenant_id, title, document_type, source_type, content, metadata)
select t.id,
       'SpeedWheels FAQ',
       'FAQ',
       'SEED',
       'SpeedWheels Auto Service Center - FAQ:
Q: How often should I change my oil? A: Every 5,000-10,000 km or every 6 months, whichever comes first. European cars may have longer intervals of 10,000-15,000 km with full synthetic oil.
Q: How long does an oil change take? A: 30-45 minutes for most vehicles.
Q: Do you use genuine parts? A: We use OEM-equivalent or genuine parts depending on customer preference. Genuine parts are available at a premium.
Q: Can I wait while my car is serviced? A: Yes, we have a waiting area with WiFi, coffee, and TV. For longer services, we can arrange a courtesy vehicle or taxi.
Q: Do you service all car brands? A: Yes, we service Toyota, Honda, Nissan, BMW, Mercedes-Benz, Audi, Ford, Hyundai, Kia, and more. We have specialized tools for European vehicles.
Q: What if additional repairs are needed? A: We always call before performing additional work. No surprises on the bill.
Q: Do you provide a courtesy car? A: Courtesy vehicles are available for services expected to take more than 4 hours, subject to availability and refundable deposit.
Q: How do I know when my next service is due? A: We track service history and can send WhatsApp reminders when the next service is approaching.
Q: Is there a loyalty program? A: Every 5th oil change is 50% off. Fleet customers get special corporate rates.
Q: Can I see my service history? A: Yes, customers can ask on WhatsApp and we can pull complete service history for all registered vehicles.',
       '{"seed": true}'::jsonb
from tenants t where t.tenant_code = 'speedwheels'
on conflict do nothing;
