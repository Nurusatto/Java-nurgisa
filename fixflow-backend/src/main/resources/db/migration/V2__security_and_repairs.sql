CREATE TABLE app_user (
 id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE CHECK (email=lower(email)),
 full_name varchar(150) NOT NULL, password_hash varchar(100) NOT NULL,
 role varchar(20) NOT NULL CHECK(role IN ('OWNER','MANAGER','TECHNICIAN')),
 active boolean NOT NULL DEFAULT true, version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE spring_session (
 primary_id char(36) PRIMARY KEY, session_id char(36) NOT NULL UNIQUE,
 creation_time bigint NOT NULL, last_access_time bigint NOT NULL,
 max_inactive_interval integer NOT NULL, expiry_time bigint NOT NULL, principal_name varchar(100)
);
CREATE INDEX spring_session_expiry_idx ON spring_session(expiry_time);
CREATE INDEX spring_session_principal_idx ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
 session_primary_id char(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
 attribute_name varchar(200) NOT NULL, attribute_bytes bytea NOT NULL,
 PRIMARY KEY(session_primary_id,attribute_name)
);
CREATE TABLE customer (
 id uuid PRIMARY KEY, full_name varchar(150) NOT NULL, phone varchar(32) NOT NULL,
 email varchar(254), comment text NOT NULL DEFAULT '', active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX customer_phone_idx ON customer(phone);
CREATE INDEX customer_name_idx ON customer(lower(full_name));
CREATE TABLE device (
 id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customer(id),
 type varchar(30) NOT NULL CHECK(type IN ('LAPTOP','DESKTOP','SMARTPHONE','TABLET','MONITOR','PRINTER','CONSOLE','OTHER')),
 manufacturer varchar(100) NOT NULL, model varchar(150) NOT NULL, serial_number varchar(150), imei varchar(30),
 comment text NOT NULL DEFAULT '', version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(id,customer_id)
);
CREATE INDEX device_owner_idx ON device(customer_id);
CREATE INDEX device_serial_idx ON device(serial_number);
CREATE SEQUENCE repair_order_number_seq;
CREATE TABLE repair_order (
 id uuid PRIMARY KEY, order_number varchar(30) NOT NULL UNIQUE DEFAULT ('FF-'||lpad(nextval('repair_order_number_seq')::text,8,'0')),
 customer_id uuid NOT NULL REFERENCES customer(id), device_id uuid NOT NULL,
 FOREIGN KEY(device_id,customer_id) REFERENCES device(id,customer_id),
 technician_id uuid REFERENCES app_user(id), accepted_by uuid NOT NULL REFERENCES app_user(id),
 problem text NOT NULL, intake_condition text NOT NULL DEFAULT '', accessories text NOT NULL DEFAULT '',
 status varchar(30) NOT NULL CHECK(status IN ('RECEIVED','DIAGNOSIS','WAITING_FOR_APPROVAL','WAITING_FOR_PARTS','IN_REPAIR','TESTING','COMPLETED','READY_FOR_PICKUP','DELIVERED','CANCELLED')),
 priority varchar(10) NOT NULL CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
 estimated_total numeric(19,2) NOT NULL DEFAULT 0 CHECK(estimated_total>=0),
 final_total numeric(19,2) CHECK(final_total>=0),
 due_at timestamptz, completed_at timestamptz, delivered_at timestamptz,
 comment text NOT NULL DEFAULT '', version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX repair_order_status_due_idx ON repair_order(status,due_at);
CREATE INDEX repair_order_technician_idx ON repair_order(technician_id,created_at);
CREATE INDEX repair_order_customer_idx ON repair_order(customer_id,created_at);
CREATE INDEX repair_order_device_idx ON repair_order(device_id);
CREATE TABLE repair_status_history (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair_order(id),
 from_status varchar(30), to_status varchar(30) NOT NULL,
 actor_id uuid NOT NULL REFERENCES app_user(id), reason text NOT NULL DEFAULT '', occurred_at timestamptz NOT NULL
);
CREATE INDEX repair_history_order_idx ON repair_status_history(order_id,occurred_at);
CREATE TABLE diagnostic_record (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair_order(id), author_id uuid NOT NULL REFERENCES app_user(id),
 description text NOT NULL, result text NOT NULL DEFAULT '', recommendation text NOT NULL DEFAULT '',
 estimated_cost numeric(19,2) NOT NULL DEFAULT 0 CHECK(estimated_cost>=0), created_at timestamptz NOT NULL
);
CREATE TABLE repair_work (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair_order(id), technician_id uuid NOT NULL REFERENCES app_user(id),
 name varchar(200) NOT NULL, description text NOT NULL DEFAULT '', quantity numeric(12,3) NOT NULL CHECK(quantity>0),
 unit_price numeric(19,2) NOT NULL DEFAULT 0 CHECK(unit_price>=0), created_at timestamptz NOT NULL
);
CREATE INDEX diagnostic_order_idx ON diagnostic_record(order_id);
CREATE INDEX repair_work_order_idx ON repair_work(order_id);
CREATE TABLE repair_approval (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair_order(id),
 amount numeric(19,2) NOT NULL CHECK(amount>=0), actor_id uuid NOT NULL REFERENCES app_user(id),
 comment text NOT NULL, created_at timestamptz NOT NULL
);
CREATE TABLE audit_log (
 id uuid PRIMARY KEY, actor_id uuid REFERENCES app_user(id), action varchar(80) NOT NULL,
 entity_type varchar(80) NOT NULL, entity_id uuid, description text NOT NULL, occurred_at timestamptz NOT NULL
);
CREATE INDEX audit_object_idx ON audit_log(entity_type,entity_id,occurred_at);
CREATE INDEX audit_time_idx ON audit_log(occurred_at);
CREATE TABLE notification (
 id uuid PRIMARY KEY, recipient_id uuid NOT NULL REFERENCES app_user(id), type varchar(60) NOT NULL,
 order_id uuid REFERENCES repair_order(id), message text NOT NULL, read_at timestamptz,
 dedup_key varchar(150) UNIQUE, created_at timestamptz NOT NULL
);
CREATE INDEX notification_recipient_idx ON notification(recipient_id,read_at,created_at);
CREATE TABLE public_tracking_token (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair_order(id), token_hash char(64) NOT NULL UNIQUE,
 revoked_at timestamptz, created_at timestamptz NOT NULL
);
CREATE TABLE shop_settings (
 id uuid PRIMARY KEY, name varchar(150) NOT NULL, phone varchar(32) NOT NULL DEFAULT '',
 address text NOT NULL DEFAULT '', timezone varchar(100) NOT NULL, currency char(3) NOT NULL,
 warranty_days integer NOT NULL DEFAULT 90 CHECK(warranty_days>=0), version bigint NOT NULL DEFAULT 0
);
INSERT INTO shop_settings VALUES ('00000000-0000-0000-0000-000000000001','FixFlow','','','Asia/Almaty','KZT',90,0);
CREATE TABLE seed_run (dataset_version varchar(80) PRIMARY KEY, base_time timestamptz NOT NULL, created_at timestamptz NOT NULL);
