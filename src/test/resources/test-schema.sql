-- Minimal H2 (PostgreSQL mode) copy of the tour-management tables the service touches. Column names
-- and types mirror the real schema; the partial unique indexes ("one open row per key") are omitted
-- because H2 has no partial indexes - the service enforces that rule itself and the tests assert it.

create table season (
    id integer generated always as identity primary key,
    name varchar(100) not null,
    start_date date not null,
    end_date date not null
);
create table nationality (
    id integer generated always as identity primary key,
    name varchar(255) not null,
    iso_code varchar(3) not null unique
);
create table destination (
    id integer generated always as identity primary key,
    name varchar(255) not null,
    region varchar(255),
    country varchar(255) not null
);
create table language (id integer generated always as identity primary key, name varchar(255) not null);
create table vehicle_type (
    id integer generated always as identity primary key,
    name varchar(255) not null,
    min_capacity smallint not null,
    max_capacity smallint not null
);
create table dietary_category (id integer generated always as identity primary key, name varchar(255) not null);
create table meal_plan_type (
    id integer generated always as identity primary key,
    code varchar(10) not null,
    name varchar(255) not null
);
create table hotel_inclusion (
    id integer generated always as identity primary key,
    name varchar(255) not null,
    description varchar(1000)
);
create table misc_cost_type (id integer generated always as identity primary key, name varchar(255) not null);
create table tax_rate (id integer generated always as identity primary key, name varchar(255) not null);
create table technical_visit_category (
    id integer generated always as identity primary key,
    name varchar(255) not null,
    active boolean default true not null
);

-- NOTE: the real vehicle.method and driver.role have CHECK constraints (INTERNAL/CONTRACTED, DRIVER/ASSISTANT).
-- They are left out here for the same H2 reason as above; the service validates the same lists itself.
create table driver (
    id integer generated always as identity primary key,
    name varchar(255) not null,
    contact varchar(255),
    license_no varchar(255),
    active boolean default true not null,
    title varchar(50),
    name_with_initials varchar(255),
    shortname varchar(255),
    gender varchar(20),
    nic varchar(50) not null unique,
    date_of_birth date,
    address varchar(1000),
    mobile_2 varchar(255),
    role varchar(20) default 'DRIVER' not null,
    is_chauffeur boolean default false not null
);
create table vehicle (
    id integer generated always as identity primary key,
    registration_no varchar(50) not null unique,
    vehicle_type_id integer not null references vehicle_type (id),
    seating_capacity smallint not null,
    method varchar(20) not null,
    owner_name varchar(255),
    insurance_company varchar(255),
    policy_number varchar(255),
    insurance_expiry_date date,
    revenue_license_expiry_date date,
    driver_id integer references driver (id),
    assistant_id integer references driver (id),
    active boolean default true not null
);

-- NOTE: the real guide.guide_type has a CHECK (DRIVER / CHAUFFEUR_GUIDE / NATIONAL_GUIDE), left out here for the
-- same H2 reason as above; the service validates the same list itself.
create table guide (
    id integer generated always as identity primary key,
    name varchar(255) not null,
    guide_type varchar(30) not null,
    contact varchar(255),
    active boolean default true not null,
    guide_no varchar(50) not null unique,
    nickname varchar(255),
    nic varchar(50) unique,
    address varchar(1000),
    town varchar(255),
    email varchar(255)
);
create table guide_language (
    guide_id integer not null references guide (id),
    language_id integer not null references language (id),
    primary key (guide_id, language_id)
);

create table restaurant (
    id integer generated always as identity primary key,
    destination_id integer not null references destination (id),
    name varchar(255) not null
);
-- day_of_week: 0 = Sunday .. 6 = Saturday (the real column's documented convention)
create table restaurant_closed_day (
    restaurant_id integer not null references restaurant (id),
    day_of_week smallint not null check (day_of_week >= 0 and day_of_week <= 6),
    primary key (restaurant_id, day_of_week)
);
create table restaurant_dietary_capability (
    restaurant_id integer not null references restaurant (id),
    dietary_category_id integer not null references dietary_category (id),
    primary key (restaurant_id, dietary_category_id)
);

create table property (
    id integer generated always as identity primary key,
    destination_id integer not null references destination (id),
    name varchar(255) not null,
    address varchar(1000),
    star_rating smallint check (star_rating >= 1 and star_rating <= 5),
    active boolean default true not null,
    code varchar(100) not null unique,
    website varchar(255),
    email varchar(255),
    phone varchar(255)
);
create table room_type (
    id integer generated always as identity primary key,
    property_id integer not null references property (id),
    name varchar(255) not null,
    base_capacity smallint,
    extra_bed_allowed boolean default false not null
);
create table property_child_rule (
    id integer generated always as identity primary key,
    property_id integer not null references property (id),
    min_age smallint not null,
    max_age smallint not null,
    requires_bed boolean not null,
    cost numeric default 0 not null,
    check (max_age >= min_age)
);
create table property_inclusion (
    property_id integer not null references property (id),
    inclusion_id integer not null references hotel_inclusion (id),
    cost numeric,
    primary key (property_id, inclusion_id)
);
create table baby_cot_option (
    property_id integer primary key references property (id),
    available boolean default false not null,
    cost numeric
);
create table property_dietary_capability (
    property_id integer not null references property (id),
    dietary_category_id integer not null references dietary_category (id),
    supported boolean not null,
    primary key (property_id, dietary_category_id)
);
-- Referenced by a lookup in the "still in use" test.
create table property_dest_ref (
    id integer generated always as identity primary key,
    destination_id integer not null references destination (id)
);

-- NOTE: the real attraction.pricing_method and attraction_rate.rate_type have CHECK constraints on a fixed list
-- of values. They are left out here because H2 fails to evaluate string IN/OR checks from a second connection;
-- the service validates the same lists itself, and LivePostgresSmokeTest exercises the real constraints.
create table attraction (
    id integer generated always as identity primary key,
    destination_id integer not null references destination (id),
    name varchar(255) not null,
    pricing_method varchar(50) not null,
    is_safari boolean default false not null,
    jeep_capacity smallint,
    fixed_group_size smallint
);
create table attraction_rate (
    id integer generated always as identity primary key,
    attraction_id integer not null references attraction (id),
    rate_type varchar(50) not null,
    rate numeric(10, 2) not null,
    effective_from date not null,
    effective_to date,
    created_at timestamp with time zone default current_timestamp not null,
    created_by varchar(255) not null,
    check (effective_to is null or effective_to > effective_from)
);
create table tuk_tuk_location (
    id integer generated always as identity primary key,
    attraction_id integer not null unique references attraction (id)
);
create table tuk_tuk_fare_rate (
    id integer generated always as identity primary key,
    tuk_tuk_location_id integer not null references tuk_tuk_location (id),
    fare numeric(10, 2) not null,
    effective_from date not null,
    effective_to date,
    created_at timestamp with time zone default current_timestamp not null,
    created_by varchar(255) not null,
    check (effective_to is null or effective_to > effective_from)
);

create table site_tour_package (
    id integer generated always as identity primary key,
    name varchar(255) not null unique
);
create table site_tour_package_site (
    package_id integer not null references site_tour_package (id),
    attraction_id integer not null references attraction (id),
    primary key (package_id, attraction_id)
);
create table site_tour_package_rate (
    id integer generated always as identity primary key,
    package_id integer not null references site_tour_package (id),
    rate numeric(10, 2) not null,
    effective_from date not null,
    effective_to date,
    created_at timestamp with time zone default current_timestamp not null,
    created_by varchar(255) not null,
    check (effective_to is null or effective_to > effective_from)
);

-- NOTE: the real promotion.type has a CHECK on PERCENTAGE / FIXED_AMOUNT / COMPLIMENTARY, left out here for the same
-- H2 reason as the attraction lists; the service validates it and LivePostgresSmokeTest covers the real constraint.
create table promotion (
    id integer generated always as identity primary key,
    name varchar(255) not null,
    type varchar(50) not null,
    valid_from date not null,
    valid_to date not null,
    min_group_size smallint,
    destination_id integer references destination (id),
    season_id integer references season (id),
    active boolean default true not null,
    check (valid_to > valid_from)
);
create table promotion_value (
    id integer generated always as identity primary key,
    promotion_id integer not null references promotion (id),
    "value" numeric(10, 2) not null,
    effective_from date not null,
    effective_to date,
    created_at timestamp with time zone default current_timestamp not null,
    created_by varchar(255) not null,
    check (effective_to is null or effective_to > effective_from)
);

create table property_rate (
    id integer generated always as identity primary key,
    property_id integer not null,
    room_type_id integer not null references room_type (id),
    season_id integer not null,
    nationality_id integer,
    rate numeric(10, 2) not null,
    effective_from date not null,
    effective_to date,
    created_at timestamp with time zone default current_timestamp not null,
    created_by varchar(255) not null,
    occupancy smallint not null,
    check (effective_to is null or effective_to > effective_from)
);
create table vehicle_rate (
    id integer generated always as identity primary key,
    vehicle_type_id integer not null,
    season_id integer not null,
    rate numeric(10, 2) not null,
    effective_from date not null,
    effective_to date,
    created_at timestamp with time zone default current_timestamp not null,
    created_by varchar(255) not null,
    check (effective_to is null or effective_to > effective_from)
);
create table transport_rate_config (
    id integer generated always as identity primary key,
    per_km_rate numeric(10, 2) not null,
    effective_from date not null,
    effective_to date,
    created_at timestamp with time zone default current_timestamp not null,
    created_by varchar(255) not null,
    check (effective_to is null or effective_to > effective_from)
);
create table tax_rate_value (
    id integer generated always as identity primary key,
    tax_rate_id integer not null,
    percentage numeric(5, 2) not null,
    effective_from date not null,
    effective_to date,
    created_at timestamp with time zone default current_timestamp not null,
    created_by varchar(255) not null,
    check (effective_to is null or effective_to > effective_from)
);
