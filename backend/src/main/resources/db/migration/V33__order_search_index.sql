-- Order search matches any part of the order number or the customer's name on the order (the
-- cashier's typed name for walk-ins), e.g. "275" or "keo". LIKE '%…%' reads every order, which gets
-- slow as orders pile up; an ngram full-text index finds the pieces directly (OrderSearch).
-- Stopwords are off for this index: with ngram, any two-letter piece containing one (such as "a")
-- would otherwise never be found.
SET SESSION innodb_ft_enable_stopword = OFF;
ALTER TABLE orders ADD FULLTEXT INDEX ft_order_search (order_number, customer_name) WITH PARSER ngram;
SET SESSION innodb_ft_enable_stopword = ON;
