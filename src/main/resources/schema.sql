-- =============================================================================
-- pay-recon 建表脚本（严格对应《支付对账闭环_AI实现任务清单.md》§2）
-- 字段可加、不可减。
--
-- 执行方式：离带外执行（mysql.exe），不要交给 Spring 的 spring.sql.init 自动跑。
--   spring.sql.init.mode=never 已写死在 application.yml。
-- 原因：pay_flow 是「只追加，永不修改、永不删除」的审计表，
--       任何"启动即重放 DDL"的做法都可能把 DROP/TRUNCATE/ALTER 混进来。
--
-- 本脚本全部 IF NOT EXISTS，重复执行安全。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- §2.1 pay_order — 支付订单
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pay_order (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  merchant_order_no VARCHAR(64)   NOT NULL COMMENT '商户订单号，由发起方生成',
  amount            DECIMAL(18,2) NOT NULL COMMENT '金额，单位元；禁止 float/double',
  status            VARCHAR(16)   NOT NULL COMMENT 'CREATED/PAYING/SUCCESS/FAILED/CLOSED',
  channel_trade_no  VARCHAR(64)   DEFAULT NULL COMMENT '渠道流水号',
  created_at        DATETIME(3)   NOT NULL,
  paid_at           DATETIME(3)   DEFAULT NULL,
  updated_at        DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_order_no (merchant_order_no)   -- 幂等的唯一来源，不得缺失
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- §2.2 pay_flow — 流水/事件表（只追加，永不修改、永不删除）
--   `source` 是 MySQL 保留字，必须反引号。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pay_flow (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  order_id    BIGINT      NOT NULL,
  event_type  VARCHAR(24) NOT NULL COMMENT 'CREATE/CALLBACK/QUERY/RECONCILE',
  `source`    VARCHAR(16) NOT NULL COMMENT 'CALLBACK/QUERY/RECONCILE',
  from_status VARCHAR(16) DEFAULT NULL,
  to_status   VARCHAR(16) DEFAULT NULL,
  raw_payload TEXT        DEFAULT NULL COMMENT '渠道原始报文，原文保存',
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- §2.3 reconcile_detail — 对账差异
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reconcile_detail (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  order_id          BIGINT      DEFAULT NULL,
  merchant_order_no VARCHAR(64) DEFAULT NULL,
  bill_date         DATE        NOT NULL,
  local_status      VARCHAR(16) DEFAULT NULL,
  channel_status    VARCHAR(16) DEFAULT NULL,
  diff_type         VARCHAR(24) NOT NULL COMMENT 'LOCAL_OK_CHANNEL_FAIL/CHANNEL_OK_LOCAL_FAIL/AMOUNT_MISMATCH/ONE_SIDE_ONLY',
  resolved          TINYINT     NOT NULL DEFAULT 0,
  created_at        DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_bill_date (bill_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 以下两张表不属于 §2 的原始清单，是为了让"入账动作只执行 1 次"变成
-- 可用 COUNT(*) 硬验证的断言而新增的（规格 §0.9 允许加表；§2 的字段未减）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 我方账户（单账户模型，id 固定为 1）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pay_account (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  account_no VARCHAR(64)   NOT NULL,
  balance    DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '账户余额，单位元',
  updated_at DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_no (account_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- 入账流水：uk_order_id 是「同一订单只能入账一次」的数据库级保证。
-- 场景 2/3 的"入账只执行 1 次"以此为硬断言：
--   SELECT COUNT(*) FROM account_entry WHERE order_id = ?
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_entry (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  order_id    BIGINT        NOT NULL,
  amount      DECIMAL(18,2) NOT NULL COMMENT '入账金额，单位元',
  entry_type  VARCHAR(24)   NOT NULL COMMENT 'CREDIT 入账',
  created_at  DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_id (order_id)   -- 幂等入账的唯一来源
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
