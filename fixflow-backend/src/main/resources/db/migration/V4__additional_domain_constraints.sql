ALTER TABLE inventory_transaction ADD CONSTRAINT inventory_type_check
 CHECK(type IN ('RECEIPT','ADJUSTMENT','REPAIR_USE','REPAIR_RETURN','PURCHASE_RECEIPT'));
ALTER TABLE inventory_transaction ADD CONSTRAINT inventory_direction_check
 CHECK((type IN ('RECEIPT','REPAIR_RETURN','PURCHASE_RECEIPT') AND direction='IN')
    OR (type='REPAIR_USE' AND direction='OUT') OR type='ADJUSTMENT');
ALTER TABLE inventory_transaction ADD CONSTRAINT repair_movement_reference_check
 CHECK(type NOT IN ('REPAIR_USE','REPAIR_RETURN') OR (order_id IS NOT NULL AND usage_id IS NOT NULL));
ALTER TABLE expense ADD CONSTRAINT expense_category_check
 CHECK(category IN ('PARTS_PURCHASE','RENT','UTILITIES','TOOLS','MAINTENANCE','OTHER'));
ALTER TABLE spare_part ADD CONSTRAINT zero_stock_value_check CHECK(quantity>0 OR stock_value=0);
ALTER TABLE spare_part ADD CONSTRAINT nonempty_sku_check CHECK(length(btrim(sku))>0);
ALTER TABLE shop_settings ADD CONSTRAINT warranty_duration_limit_check CHECK(warranty_days<=3650);
