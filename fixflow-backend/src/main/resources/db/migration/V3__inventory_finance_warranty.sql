CREATE TABLE supplier (
 id uuid PRIMARY KEY, name varchar(200) NOT NULL, contact_name varchar(150) NOT NULL DEFAULT '', phone varchar(32) NOT NULL DEFAULT '',
 email varchar(254) NOT NULL DEFAULT '', address text NOT NULL DEFAULT '', comment text NOT NULL DEFAULT '', active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE spare_part (
 id uuid PRIMARY KEY, name varchar(200) NOT NULL, sku varchar(80) NOT NULL UNIQUE,
 category varchar(60) NOT NULL, manufacturer varchar(150) NOT NULL DEFAULT '', supplier_id uuid REFERENCES supplier(id),
 purchase_price numeric(19,2) NOT NULL CHECK(purchase_price>=0), sale_price numeric(19,2) NOT NULL CHECK(sale_price>=0),
 quantity numeric(12,3) NOT NULL DEFAULT 0 CHECK(quantity>=0), stock_value numeric(19,2) NOT NULL DEFAULT 0 CHECK(stock_value>=0),
 minimum_quantity numeric(12,3) NOT NULL DEFAULT 0 CHECK(minimum_quantity>=0), storage_location varchar(150) NOT NULL DEFAULT '',
 active boolean NOT NULL DEFAULT true, version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX part_category_idx ON spare_part(category);
CREATE TABLE repair_part_usage (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair_order(id), part_id uuid NOT NULL REFERENCES spare_part(id),
 quantity numeric(12,3) NOT NULL CHECK(quantity>0), returned_quantity numeric(12,3) NOT NULL DEFAULT 0 CHECK(returned_quantity>=0 AND returned_quantity<=quantity),
 unit_sale_price numeric(19,2) NOT NULL CHECK(unit_sale_price>=0), unit_cost numeric(19,6) NOT NULL CHECK(unit_cost>=0), total_cost numeric(19,2) NOT NULL CHECK(total_cost>=0),
 actor_id uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL
);
CREATE INDEX usage_order_idx ON repair_part_usage(order_id);
CREATE TABLE inventory_transaction (
 id uuid PRIMARY KEY, part_id uuid NOT NULL REFERENCES spare_part(id), type varchar(40) NOT NULL,
 direction varchar(3) NOT NULL CHECK(direction IN ('IN','OUT')), quantity numeric(12,3) NOT NULL CHECK(quantity>0),
 unit_cost numeric(19,6) NOT NULL CHECK(unit_cost>=0), total_cost numeric(19,2) NOT NULL CHECK(total_cost>=0),
 after_quantity numeric(12,3) NOT NULL CHECK(after_quantity>=0), reason text NOT NULL,
 actor_id uuid NOT NULL REFERENCES app_user(id), order_id uuid REFERENCES repair_order(id),
 usage_id uuid REFERENCES repair_part_usage(id), created_at timestamptz NOT NULL
);
CREATE INDEX inventory_part_time_idx ON inventory_transaction(part_id,created_at);
CREATE INDEX inventory_order_idx ON inventory_transaction(order_id);
CREATE SEQUENCE purchase_number_seq;
CREATE TABLE purchase_order (
 id uuid PRIMARY KEY, number varchar(30) NOT NULL UNIQUE DEFAULT ('PO-'||lpad(nextval('purchase_number_seq')::text,7,'0')),
 supplier_id uuid NOT NULL REFERENCES supplier(id), status varchar(30) NOT NULL CHECK(status IN ('DRAFT','ORDERED','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')),
 expected_at timestamptz, comment text NOT NULL DEFAULT '', version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE purchase_order_item (
 id uuid PRIMARY KEY, purchase_order_id uuid NOT NULL REFERENCES purchase_order(id), part_id uuid NOT NULL REFERENCES spare_part(id),
 quantity numeric(12,3) NOT NULL CHECK(quantity>0), unit_price numeric(19,2) NOT NULL CHECK(unit_price>=0), UNIQUE(purchase_order_id,part_id)
);
CREATE TABLE purchase_receipt (
 id uuid PRIMARY KEY, purchase_order_id uuid NOT NULL REFERENCES purchase_order(id), actor_id uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL
);
CREATE TABLE purchase_receipt_item (
 id uuid PRIMARY KEY, receipt_id uuid NOT NULL REFERENCES purchase_receipt(id), purchase_order_item_id uuid NOT NULL REFERENCES purchase_order_item(id),
 quantity numeric(12,3) NOT NULL CHECK(quantity>0), transaction_id uuid NOT NULL UNIQUE REFERENCES inventory_transaction(id), UNIQUE(receipt_id,purchase_order_item_id)
);
CREATE TABLE payment (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair_order(id), amount numeric(19,2) NOT NULL CHECK(amount>0),
 method varchar(30) NOT NULL CHECK(method IN ('CASH','CARD','BANK_TRANSFER','OTHER')),
 status varchar(20) NOT NULL DEFAULT 'POSTED' CHECK(status='POSTED'), comment text NOT NULL DEFAULT '',
 actor_id uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL
);
CREATE INDEX payment_order_idx ON payment(order_id);
CREATE INDEX payment_date_idx ON payment(created_at);
CREATE TABLE refund (
 id uuid PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payment(id), amount numeric(19,2) NOT NULL CHECK(amount>0),
 reason text NOT NULL, actor_id uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL
);
CREATE INDEX refund_payment_idx ON refund(payment_id);
CREATE TABLE order_price_adjustment (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES repair_order(id), amount numeric(19,2) NOT NULL CHECK(amount<>0),
 reason text NOT NULL, actor_id uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL
);
CREATE TABLE expense (
 id uuid PRIMARY KEY, amount numeric(19,2) NOT NULL CHECK(amount>0), category varchar(50) NOT NULL,
 description text NOT NULL, order_id uuid REFERENCES repair_order(id), purchase_order_id uuid REFERENCES purchase_order(id),
 actor_id uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL,
 CHECK(order_id IS NULL OR purchase_order_id IS NULL)
);
CREATE INDEX expense_date_idx ON expense(created_at);
CREATE SEQUENCE warranty_number_seq;
CREATE TABLE warranty (
 id uuid PRIMARY KEY, number varchar(30) NOT NULL UNIQUE DEFAULT ('WG-'||lpad(nextval('warranty_number_seq')::text,7,'0')),
 order_id uuid NOT NULL UNIQUE REFERENCES repair_order(id), starts_on date NOT NULL, ends_on date NOT NULL,
 terms text NOT NULL, revoked_at timestamptz, comment text NOT NULL DEFAULT '', created_at timestamptz NOT NULL,
 CHECK(ends_on>=starts_on)
);
ALTER TABLE repair_order ADD COLUMN source_warranty_id uuid REFERENCES warranty(id);
ALTER TABLE repair_work ADD COLUMN price_confirmed boolean NOT NULL DEFAULT false;
CREATE TABLE warranty_claim (
 id uuid PRIMARY KEY, warranty_id uuid NOT NULL REFERENCES warranty(id), followup_order_id uuid NOT NULL UNIQUE REFERENCES repair_order(id),
 description text NOT NULL, created_at timestamptz NOT NULL
);
CREATE TABLE idempotency_record (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES app_user(id), operation varchar(150) NOT NULL,
 request_key varchar(100) NOT NULL, request_hash char(64) NOT NULL, response_json text NOT NULL,
 created_at timestamptz NOT NULL, UNIQUE(actor_id,operation,request_key)
);
