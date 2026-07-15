CREATE TABLE menus (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    price BIGINT NOT NULL,
    CONSTRAINT pk_menus PRIMARY KEY (id),
    CONSTRAINT chk_menus_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_menus_price_positive CHECK (price > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_bin;

CREATE TABLE point_accounts (
    user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_point_accounts PRIMARY KEY (user_id),
    CONSTRAINT chk_point_accounts_balance_non_negative CHECK (balance >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_bin;

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    menu_id BIGINT NOT NULL,
    paid_amount BIGINT NOT NULL,
    ordered_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT fk_orders_point_account
        FOREIGN KEY (user_id) REFERENCES point_accounts (user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_orders_menu
        FOREIGN KEY (menu_id) REFERENCES menus (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_orders_paid_amount_positive CHECK (paid_amount > 0),
    INDEX idx_orders_user_id (user_id),
    INDEX idx_orders_menu_id (menu_id),
    INDEX idx_orders_ordered_at_menu_id (ordered_at, menu_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_bin;

INSERT INTO menus (id, name, price)
VALUES
    (1, '아메리카노', 4500),
    (2, '카페라떼', 5000);
