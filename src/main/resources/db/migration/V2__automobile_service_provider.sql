-- =========================================================
-- V2 - Automobile service provider tenant and domain data
-- =========================================================

create unique index if not exists uk_messages_tenant_wa_message_id_not_null
    on messages(tenant_id, wa_message_id)
    where wa_message_id is not null;

create table if not exists vehicles (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    contact_id uuid not null references contacts(id),
    make varchar(100) not null,
    model varchar(100) not null,
    vehicle_year int,
    plate_number varchar(50) not null,
    vin varchar(80),
    color varchar(50),
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
    service_type varchar(120) not null,
    description text,
    technician_name varchar(150),
    mileage_at_service int,
    cost numeric(12,2),
    currency varchar(10) default 'AED',
    notes text,
    service_date date not null,
    next_service_date date,
    status varchar(50) not null default 'COMPLETED',
    metadata jsonb,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

create index if not exists idx_service_records_vehicle_date
    on service_records(tenant_id, vehicle_id, service_date desc);

create index if not exists idx_service_records_contact_date
    on service_records(tenant_id, contact_id, service_date desc);

create table if not exists service_appointments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    contact_id uuid references contacts(id),
    vehicle_id uuid references vehicles(id),
    service_type varchar(120) not null,
    appointment_date date not null,
    time_slot varchar(30) not null,
    status varchar(50) not null default 'AVAILABLE',
    customer_phone varchar(50),
    customer_name varchar(200),
    notes text,
    metadata jsonb,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint uk_service_appointments_slot unique (tenant_id, appointment_date, time_slot)
);

create index if not exists idx_service_appointments_tenant_status
    on service_appointments(tenant_id, status, appointment_date);

create index if not exists idx_service_appointments_customer
    on service_appointments(tenant_id, customer_phone);

-- Keep the old seed restaurant inactive and free the test WhatsApp phone number for the automobile tenant.
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
    'SpeedWheels Auto Service',
    'AUTOMOBILE',
    '104824432320753',
    'REPLACE_WITH_WABA_ID',
    null,
    'You are SpeedWheels Auto Service WhatsApp assistant in Dubai. Be proactive and conversational. When a customer messages, first use automobile tools to identify the customer by phone, list registered vehicles, and check recent service history. If they are returning, greet them by name and mention their vehicle. Ask helpful follow-up questions about recent services, symptoms, noise, warning lights, mileage, AC cooling, brakes, tyres, or battery. Suggest due services only from actual service history, next_service_date, or knowledge base. Use appointment tools to show available slots and book service appointments. Do not invent prices, availability, vehicle records, service history, warranties, offers, or payment links. Keep replies short and suitable for WhatsApp. If a human agent is needed, reply exactly HUMAN_HANDOFF_REQUIRED.',
    'en',
    'Asia/Dubai',
    true
)
on conflict (tenant_code) do update
set business_name = excluded.business_name,
    business_type = excluded.business_type,
    phone_number_id = excluded.phone_number_id,
    system_prompt = excluded.system_prompt,
    active = true,
    updated_at = now();

insert into contacts (tenant_id, wa_id, phone_number, display_name)
select t.id, seed.wa_id, seed.phone_number, seed.display_name
from tenants t
cross join (values
    ('971501110001', '971501110001', 'Ahmed Al Rashid'),
    ('971501110002', '971501110002', 'Fatima Hassan'),
    ('971501110003', '971501110003', 'John Smith'),
    ('971501110004', '971501110004', 'Priya Sharma'),
    ('971501110005', '971501110005', 'Mohammed Ali'),
    ('971501110006', '971501110006', 'Sarah Johnson')
) as seed(wa_id, phone_number, display_name)
where t.tenant_code = 'speedwheels'
on conflict (tenant_id, wa_id) do update
set phone_number = excluded.phone_number,
    display_name = excluded.display_name,
    updated_at = now();

insert into vehicles (tenant_id, contact_id, make, model, vehicle_year, plate_number, vin, color, metadata, active)
select t.id, c.id, seed.make, seed.model, seed.vehicle_year, seed.plate_number, seed.vin, seed.color, seed.metadata::jsonb, true
from tenants t
join (values
    ('971501110001', 'Toyota', 'Camry', 2021, 'DXB-A-12345', 'JTDBR32E721000001', 'White', '{"seed":true,"fuel":"petrol"}'),
    ('971501110002', 'Honda', 'Civic', 2020, 'DXB-B-23456', '2HGFC2F59LH000002', 'Silver', '{"seed":true,"fuel":"petrol"}'),
    ('971501110003', 'BMW', 'X5', 2022, 'DXB-C-34567', '5UXCR6C05N9000003', 'Black', '{"seed":true,"fuel":"petrol"}'),
    ('971501110005', 'Nissan', 'Patrol', 2019, 'DXB-D-45678', 'JN8BY2NYXK9000004', 'Grey', '{"seed":true,"fuel":"petrol"}'),
    ('971501110006', 'Mercedes-Benz', 'C200', 2023, 'DXB-E-56789', 'W1KAF4GB0PR000005', 'Blue', '{"seed":true,"fuel":"petrol"}'),
    ('971501110004', 'Hyundai', 'Tucson', 2021, 'DXB-F-67890', 'KM8J3CAL0MU000006', 'Red', '{"seed":true,"fuel":"petrol"}'),
    ('971501110001', 'Ford', 'Mustang', 2022, 'DXB-G-78901', '1FA6P8TH5N5000007', 'Yellow', '{"seed":true,"fuel":"petrol"}'),
    ('971501110002', 'Kia', 'Sportage', 2020, 'DXB-H-89012', 'KNDPMCACXL7000008', 'White', '{"seed":true,"fuel":"petrol"}')
) as seed(customer_phone, make, model, vehicle_year, plate_number, vin, color, metadata)
    on true
join contacts c on c.tenant_id = t.id and c.phone_number = seed.customer_phone
where t.tenant_code = 'speedwheels'
on conflict (tenant_id, plate_number) do update
set contact_id = excluded.contact_id,
    make = excluded.make,
    model = excluded.model,
    vehicle_year = excluded.vehicle_year,
    vin = excluded.vin,
    color = excluded.color,
    metadata = excluded.metadata,
    active = true,
    updated_at = now();

insert into service_records (
    tenant_id,
    vehicle_id,
    contact_id,
    service_type,
    description,
    technician_name,
    mileage_at_service,
    cost,
    currency,
    notes,
    service_date,
    next_service_date,
    status,
    metadata
)
select t.id, v.id, v.contact_id, seed.service_type, seed.description, seed.technician_name,
       seed.mileage_at_service, seed.cost, 'AED', seed.notes,
       (current_date - seed.days_ago)::date,
       case when seed.next_days is null then null else (current_date + seed.next_days)::date end,
       'COMPLETED', '{"seed":true}'::jsonb
from tenants t
join vehicles v on v.tenant_id = t.id
join (values
    ('DXB-A-12345', 'OIL_CHANGE', 'Engine oil and filter replacement', 'Ravi Kumar', 42000, 220.00, 40, 80, 'Use synthetic oil next visit'),
    ('DXB-A-12345', 'BRAKE_SERVICE', 'Front brake pads inspection and cleaning', 'Omar Nasser', 38500, 180.00, 190, 20, 'Monitor brake pad thickness'),
    ('DXB-A-12345', 'AC_SERVICE', 'AC cooling performance check and gas top-up', 'Ali Khan', 36000, 250.00, 300, 90, 'Cabin filter replaced'),
    ('DXB-B-23456', 'TIRE_ROTATION', 'Tyre rotation and pressure adjustment', 'Ravi Kumar', 52000, 120.00, 55, 125, 'Rear tyres wearing evenly'),
    ('DXB-B-23456', 'BATTERY_REPLACEMENT', 'Battery replaced with 18-month warranty', 'Samir Patel', 49000, 420.00, 220, null, 'Warranty card issued'),
    ('DXB-C-34567', 'ENGINE_DIAGNOSTICS', 'Computer diagnostics for check engine light', 'Omar Nasser', 28000, 300.00, 25, 10, 'O2 sensor warning needs follow-up'),
    ('DXB-C-34567', 'OIL_CHANGE', 'Premium oil service', 'Ravi Kumar', 26000, 380.00, 130, 50, 'Next oil service recommended soon'),
    ('DXB-D-45678', 'WHEEL_ALIGNMENT', 'Four-wheel alignment', 'Ali Khan', 76000, 180.00, 18, 180, 'Steering pull corrected'),
    ('DXB-D-45678', 'TRANSMISSION_SERVICE', 'Transmission oil inspection and service', 'Samir Patel', 73500, 650.00, 270, 95, 'Smooth shifting after service'),
    ('DXB-E-56789', 'OIL_CHANGE', 'Factory-grade oil and filter service', 'Ravi Kumar', 12000, 360.00, 35, 85, 'Vehicle under manufacturer warranty'),
    ('DXB-E-56789', 'BRAKE_SERVICE', 'Brake noise inspection', 'Omar Nasser', 9800, 160.00, 160, 30, 'No replacement required'),
    ('DXB-F-67890', 'AC_REPAIR', 'AC blower cleaning and filter replacement', 'Ali Khan', 33000, 310.00, 12, 170, 'Cooling improved'),
    ('DXB-F-67890', 'TIRE_ROTATION', 'Tyre rotation and balancing', 'Samir Patel', 31000, 150.00, 150, 35, 'Recommend alignment check'),
    ('DXB-G-78901', 'ENGINE_DIAGNOSTICS', 'Performance diagnostic scan', 'Omar Nasser', 18000, 320.00, 60, 120, 'No active fault codes'),
    ('DXB-G-78901', 'OIL_CHANGE', 'Synthetic oil and filter', 'Ravi Kumar', 16000, 300.00, 210, 15, 'Due for oil service soon'),
    ('DXB-H-89012', 'BATTERY_CHECK', 'Battery and alternator test', 'Samir Patel', 47000, 90.00, 45, 100, 'Battery health fair'),
    ('DXB-H-89012', 'BRAKE_SERVICE', 'Rear brake pad replacement', 'Omar Nasser', 45000, 520.00, 240, 60, 'Brake pads replaced'),
    ('DXB-H-89012', 'WHEEL_ALIGNMENT', 'Alignment after tyre wear complaint', 'Ali Khan', 42000, 180.00, 330, 150, 'Recheck after 5000 km')
) as seed(plate_number, service_type, description, technician_name, mileage_at_service, cost, days_ago, next_days, notes)
    on v.plate_number = seed.plate_number
where t.tenant_code = 'speedwheels'
  and not exists (
      select 1
      from service_records sr
      where sr.tenant_id = t.id
        and sr.vehicle_id = v.id
        and sr.service_type = seed.service_type
        and sr.service_date = (current_date - seed.days_ago)::date
  );

insert into service_appointments (tenant_id, service_type, appointment_date, time_slot, status, metadata)
select t.id,
       'GENERAL_SERVICE',
       slot_day::date,
       to_char(slot_time, 'HH24:MI') || '-' || to_char(slot_time + interval '1 hour', 'HH24:MI'),
       'AVAILABLE',
       '{"seed":true}'::jsonb
from tenants t
cross join generate_series(current_date, current_date + interval '13 days', interval '1 day') as slot_day
cross join generate_series(timestamp '2000-01-01 09:00:00', timestamp '2000-01-01 16:00:00', interval '1 hour') as slot_time
where t.tenant_code = 'speedwheels'
  and extract(isodow from slot_day) < 7
on conflict (tenant_id, appointment_date, time_slot) do nothing;

with candidate_slots as (
    select a.id, row_number() over (order by a.appointment_date, a.time_slot) as rn
    from service_appointments a
    join tenants t on t.id = a.tenant_id
    where t.tenant_code = 'speedwheels'
      and a.status = 'AVAILABLE'
    order by a.appointment_date, a.time_slot
    limit 4
), booked_seed(rn, customer_phone, plate_number, service_type, notes) as (
    values
        (1, '971501110001', 'DXB-A-12345', 'OIL_CHANGE', 'Seed booking for Ahmed'),
        (2, '971501110002', 'DXB-B-23456', 'TIRE_ROTATION', 'Seed booking for Fatima'),
        (3, '971501110003', 'DXB-C-34567', 'ENGINE_DIAGNOSTICS', 'Seed booking for John'),
        (4, '971501110004', 'DXB-F-67890', 'AC_REPAIR', 'Seed booking for Priya')
)
update service_appointments a
set status = 'BOOKED',
    service_type = b.service_type,
    contact_id = c.id,
    vehicle_id = v.id,
    customer_phone = c.phone_number,
    customer_name = c.display_name,
    notes = b.notes,
    updated_at = now()
from candidate_slots s
join booked_seed b on b.rn = s.rn
join tenants t on t.tenant_code = 'speedwheels'
join contacts c on c.tenant_id = t.id and c.phone_number = b.customer_phone
join vehicles v on v.tenant_id = t.id and v.plate_number = b.plate_number
where a.id = s.id;

insert into knowledge_documents (tenant_id, title, document_type, source_type, content, metadata)
select t.id, seed.title, seed.document_type, 'SEED', seed.content, seed.metadata::jsonb
from tenants t
cross join (values
    ('SpeedWheels service catalog', 'VEHICLE_INFO', 'SpeedWheels provides oil change, brake service, tyre rotation, wheel alignment, AC service, battery replacement, engine diagnostics, transmission service, and pre-purchase vehicle inspection. Standard service durations range from 45 minutes to 3 hours depending on inspection and parts availability.', '{"seed":true,"category":"service_catalog"}'),
    ('SpeedWheels base pricing AED', 'VEHICLE_INFO', 'Base prices in AED: oil change from 220, tyre rotation from 120, wheel alignment from 180, AC service from 250, battery check from 90, battery replacement from 420, engine diagnostics from 300, brake inspection from 160, brake pad replacement from 520, transmission service from 650. Final price depends on vehicle model and parts.', '{"seed":true,"category":"pricing"}'),
    ('SpeedWheels booking rules and business hours', 'BOOKING_RULES', 'SpeedWheels is open Monday to Saturday, 9 AM to 6 PM. Appointments are booked in one-hour slots. Customers can cancel or reschedule up to 4 hours before the appointment. Emergency diagnostics are subject to available slots. Customers should bring registration card and previous service notes if available.', '{"seed":true,"category":"booking_rules"}'),
    ('SpeedWheels FAQ', 'FAQ', 'Warranty: workmanship warranty is 7 days unless otherwise stated. Parts warranty follows supplier terms. Payment methods: cash, card, and bank transfer. Courtesy car is subject to availability and must be confirmed by staff. Genuine, OEM, and aftermarket parts can be sourced depending on customer preference and availability.', '{"seed":true,"category":"faq"}')
) as seed(title, document_type, content, metadata)
where t.tenant_code = 'speedwheels'
  and not exists (
      select 1
      from knowledge_documents kd
      where kd.tenant_id = t.id
        and kd.title = seed.title
  );
