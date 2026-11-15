-- Fixes from the November 2026 audit.
--
-- Services only the system uses (the online shop's Delivery) stay off the till's sell screen, quick picks
-- and top sellers; checkout still takes them.
ALTER TABLE offerings ADD COLUMN till_hidden BOOLEAN NOT NULL DEFAULT FALSE AFTER sell_online;
UPDATE offerings SET till_hidden = TRUE WHERE id = (SELECT delivery_offering_id FROM shop_settings WHERE id = 1);

-- An online account is confirmed when its customer record was made by the account itself, or once staff
-- check it at the counter. Phones can't be verified yet, so an account that claimed a customer already on
-- file (from the till) neither earns that customer's points nor gets their member offers until confirmed;
-- otherwise anyone could take over a member's number.
ALTER TABLE customer_accounts ADD COLUMN verified BOOLEAN NOT NULL DEFAULT FALSE AFTER password_hash;
UPDATE customer_accounts a JOIN customers c ON c.id = a.customer_id JOIN users u ON u.id = c.created_by
SET a.verified = TRUE WHERE u.username = 'online-shop';
