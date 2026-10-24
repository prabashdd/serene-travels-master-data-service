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
