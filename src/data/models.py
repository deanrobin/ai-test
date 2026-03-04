"""
数据观测层 - 标准化数据模型
所有采集器输出统一的数据结构，屏蔽上游差异
"""

from dataclasses import dataclass, field
from typing import Literal, Optional
from datetime import datetime
from enum import Enum


class MarketStatus(str, Enum):
    ACTIVE   = "ACTIVE"
    RESOLVED = "RESOLVED"
    PAUSED   = "PAUSED"
    CLOSED   = "CLOSED"


class OptionType(str, Enum):
    CALL = "C"
    PUT  = "P"


# ─── Polymarket ───────────────────────────────────────────────────

@dataclass
class PolyTicker:
    """Polymarket 单个 Outcome（YES 或 NO）的实时行情"""
    market_id:    str
    condition_id: str
    event_title:  str
    outcome:      Literal["YES", "NO"]

    best_bid:     float          # 买一价
    best_ask:     float          # 卖一价
    last_price:   float          # 最新成交价
    mid_price:    float          # 中间价 = (bid+ask)/2
    spread:       float          # 价差 = ask - bid

    volume_24h:   float          # 24h 成交量（USDC）
    liquidity:    float          # 当前盘口流动性（USDC）

    expire_time:  Optional[datetime]
    status:       MarketStatus
    ts:           datetime = field(default_factory=datetime.utcnow)

    @property
    def implied_prob(self) -> float:
        """Polymarket 价格即隐含概率"""
        return self.mid_price

    @property
    def is_liquid(self) -> bool:
        return self.spread < 0.05 and self.liquidity > 1000


@dataclass
class PolyOrderBook:
    """Polymarket 订单簿快照"""
    market_id: str
    outcome:   Literal["YES", "NO"]
    bids:      list[tuple[float, float]]   # [(price, size), ...]
    asks:      list[tuple[float, float]]
    ts:        datetime = field(default_factory=datetime.utcnow)

    def depth_at_price(self, side: str, price: float) -> float:
        book = self.bids if side == "BID" else self.asks
        return sum(size for p, size in book if abs(p - price) < 0.01)


# ─── OKX ──────────────────────────────────────────────────────────

@dataclass
class OKXOptionTicker:
    """OKX 单个期权合约的实时行情"""
    instrument_id: str             # e.g. BTC-20260328-100000-C
    underlying:    str             # BTC / ETH / SOL
    strike:        float
    expiry:        datetime
    option_type:   OptionType

    best_bid:      float
    best_ask:      float
    last_price:    float
    mid_price:     float

    iv:            float           # 隐含波动率（年化）
    mark_price:    float           # 标记价格

    # Greeks
    delta:         float
    gamma:         float
    vega:          float
    theta:         float

    spot_price:    float           # 标的现货价
    open_interest: float           # 未平仓量（张）
    volume_24h:    float

    ts: datetime = field(default_factory=datetime.utcnow)

    @property
    def dte(self) -> float:
        """距到期天数"""
        return (self.expiry - datetime.utcnow()).total_seconds() / 86400

    @property
    def ttm(self) -> float:
        """距到期年化时间"""
        return self.dte / 365


@dataclass
class OKXSpotTicker:
    """OKX 现货价格"""
    symbol:     str        # BTC-USDT
    price:      float
    change_24h: float      # 24h 涨跌幅
    volume_24h: float
    ts:         datetime = field(default_factory=datetime.utcnow)


# ─── 链上事件 ──────────────────────────────────────────────────────

@dataclass
class ChainEvent:
    event_type:  Literal["MARKET_CREATED", "CONDITION_RESOLVED", "REDEMPTION", "DISPUTE"]
    market_id:   str
    tx_hash:     str
    block_number: int
    payload:     dict
    ts:          datetime = field(default_factory=datetime.utcnow)


# ─── 统一行情事件（发布到消息总线）─────────────────────────────────

@dataclass
class MarketDataEvent:
    """
    标准化行情事件，数据观测层向下游推送的统一格式
    """
    source:  Literal["POLYMARKET", "OKX_OPTION", "OKX_SPOT", "CHAIN"]
    data:    PolyTicker | OKXOptionTicker | OKXSpotTicker | ChainEvent
    ts:      datetime = field(default_factory=datetime.utcnow)
