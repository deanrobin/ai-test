-- 初始化数据库（JPA ddl-auto=update 会自动建表，此脚本仅供参考/手动初始化）

CREATE DATABASE IF NOT EXISTS arb_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE arb_db;

-- Polymarket 行情表（高写入频率，建议按月分区）
CREATE TABLE IF NOT EXISTS poly_ticker (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    market_id    VARCHAR(100)  NOT NULL COMMENT 'condition_id',
    event_title  VARCHAR(500)  COMMENT '事件标题',
    outcome      VARCHAR(10)   NOT NULL COMMENT 'YES / NO',
    best_bid     DECIMAL(10,6) COMMENT '买一价',
    best_ask     DECIMAL(10,6) COMMENT '卖一价',
    last_price   DECIMAL(10,6) COMMENT '最新成交价',
    mid_price    DECIMAL(10,6) COMMENT '中间价',
    spread       DECIMAL(10,6) COMMENT '价差',
    volume_24h   DECIMAL(20,4) COMMENT '24h 成交量(USDC)',
    liquidity    DECIMAL(20,4) COMMENT '盘口流动性(USDC)',
    expire_time  DATETIME      COMMENT '到期时间',
    status       VARCHAR(20)   COMMENT 'ACTIVE/RESOLVED/PAUSED',
    ts           DATETIME      NOT NULL COMMENT '行情时间戳',
    PRIMARY KEY (id),
    INDEX idx_market_outcome_ts (market_id, outcome, ts),
    INDEX idx_ts                (ts),
    INDEX idx_status            (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Polymarket 行情快照';

-- OKX 期权行情表
CREATE TABLE IF NOT EXISTS okx_option_ticker (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    instrument_id VARCHAR(60)   NOT NULL COMMENT '如 BTC-20260328-100000-C',
    underlying    VARCHAR(20)   NOT NULL COMMENT 'BTC/ETH/SOL',
    strike        DECIMAL(20,2) COMMENT '行权价',
    expiry        DATE          COMMENT '到期日',
    option_type   VARCHAR(1)    COMMENT 'C=Call P=Put',
    best_bid      DECIMAL(15,8),
    best_ask      DECIMAL(15,8),
    last_price    DECIMAL(15,8),
    mid_price     DECIMAL(15,8),
    iv            DECIMAL(10,6) COMMENT '隐含波动率(小数)',
    mark_price    DECIMAL(15,8),
    delta         DECIMAL(10,6),
    gamma         DECIMAL(15,10),
    vega          DECIMAL(10,6),
    theta         DECIMAL(10,6),
    spot_price    DECIMAL(20,4) COMMENT '标的现货价',
    open_interest DECIMAL(20,4) COMMENT '未平仓量(张)',
    volume_24h    DECIMAL(20,4),
    ts            DATETIME      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_instrument_ts     (instrument_id, ts),
    INDEX idx_underlying_expiry (underlying, expiry, strike),
    INDEX idx_ts                (ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='OKX 期权行情快照';

-- OKX 现货行情表
CREATE TABLE IF NOT EXISTS okx_spot_ticker (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    symbol     VARCHAR(20)   NOT NULL COMMENT '如 BTC-USDT',
    price      DECIMAL(20,4),
    change_24h DECIMAL(10,6),
    volume_24h DECIMAL(20,4),
    ts         DATETIME      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_symbol_ts (symbol, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='OKX 现货行情';

-- 链上事件表
CREATE TABLE IF NOT EXISTS chain_event (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    event_type   VARCHAR(40)   NOT NULL,
    market_id    VARCHAR(100),
    tx_hash      VARCHAR(100),
    block_number BIGINT,
    payload      TEXT          COMMENT 'JSON',
    ts           DATETIME      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_market_id  (market_id),
    INDEX idx_event_type (event_type),
    INDEX idx_ts         (ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Polygon 链上事件';
