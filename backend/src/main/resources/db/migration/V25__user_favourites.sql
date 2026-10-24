-- Each person's favourite catalog entries, shown first on the sell screen.
CREATE TABLE user_favourites (
    user_id     BIGINT      NOT NULL,
    offering_id BIGINT      NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, offering_id),
    CONSTRAINT fk_favourite_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_favourite_offering FOREIGN KEY (offering_id) REFERENCES offerings (id) ON DELETE CASCADE
);
