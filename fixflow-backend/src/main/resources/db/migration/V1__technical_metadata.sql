CREATE TABLE application_metadata (
    name varchar(100) PRIMARY KEY,
    value varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
INSERT INTO application_metadata(name, value) VALUES ('application', 'FixFlow');
