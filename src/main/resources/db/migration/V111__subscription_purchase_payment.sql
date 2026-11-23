ALTER TABLE subscriptions
    ADD purchase_payment_provider VARCHAR(32) NULL,
    ADD purchase_payment_order_id VARCHAR(128) NULL,
    ADD purchase_bnpl_term INT NULL;