CREATE INDEX customer_normalized_phone_idx ON customer(regexp_replace(phone,'[^0-9]','','g'));
CREATE INDEX customer_email_search_idx ON customer(lower(email)) WHERE email IS NOT NULL AND email<>'';
