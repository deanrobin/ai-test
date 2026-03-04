"""
数据观测层 - 本地缓存层
Redis 热数据缓存 + 内存快照，供计算引擎低延迟读取
"""

import json
import logging
from datetime import datetime
from typing import Optional

import redis.asyncio as aioredis

from .models import PolyTicker, OKXOptionTicker, OKXSpotTicker

logger = logging.getLogger(__name__)

# Redis key 前缀
POLY_TICKER_KEY  = "poly:ticker:{market_id}:{outcome}"
OKX_OPTION_KEY   = "okx:option:{instrument_id}"
OKX_SPOT_KEY     = "okx:spot:{symbol}"
POLY_MARKETS_KEY = "poly:markets:active"


class MarketDataCache:
    """
    双层缓存：
      L1 - 进程内 dict（零延迟，最新快照）
      L2 - Redis（跨进程共享，TTL 保护）
    """

    def __init__(self, redis_url: str = "redis://localhost:6379/0"):
        self._redis_url = redis_url
        self._redis: Optional[aioredis.Redis] = None

        # L1 内存缓存
        self._poly_tickers:  dict[str, PolyTicker]       = {}   # key: market_id:outcome
        self._okx_options:   dict[str, OKXOptionTicker]  = {}   # key: instrument_id
        self._okx_spots:     dict[str, OKXSpotTicker]    = {}   # key: symbol

    async def connect(self):
        self._redis = await aioredis.from_url(
            self._redis_url,
            encoding="utf-8",
            decode_responses=True
        )
        logger.info("Redis 缓存连接成功")

    async def close(self):
        if self._redis:
            await self._redis.aclose()

    # ─── Polymarket ───────────────────────────────────────────────

    async def set_poly_ticker(self, ticker: PolyTicker):
        key = f"{ticker.market_id}:{ticker.outcome}"
        self._poly_tickers[key] = ticker

        if self._redis:
            rkey = POLY_TICKER_KEY.format(
                market_id=ticker.market_id, outcome=ticker.outcome
            )
            await self._redis.setex(rkey, 60, self._serialize(ticker))

    def get_poly_ticker(
        self, market_id: str, outcome: str = "YES"
    ) -> Optional[PolyTicker]:
        return self._poly_tickers.get(f"{market_id}:{outcome}")

    def get_all_poly_tickers(self) -> list[PolyTicker]:
        return list(self._poly_tickers.values())

    def get_active_poly_markets(self) -> list[str]:
        """返回有行情的活跃 market_id 列表"""
        seen = set()
        result = []
        for key in self._poly_tickers:
            mid = key.split(":")[0]
            if mid not in seen:
                seen.add(mid)
                result.append(mid)
        return result

    # ─── OKX ──────────────────────────────────────────────────────

    async def set_okx_option(self, ticker: OKXOptionTicker):
        self._okx_options[ticker.instrument_id] = ticker

        if self._redis:
            rkey = OKX_OPTION_KEY.format(instrument_id=ticker.instrument_id)
            await self._redis.setex(rkey, 30, self._serialize(ticker))

    def get_okx_option(self, instrument_id: str) -> Optional[OKXOptionTicker]:
        return self._okx_options.get(instrument_id)

    def get_okx_options_by_underlying(
        self, underlying: str
    ) -> list[OKXOptionTicker]:
        return [
            t for t in self._okx_options.values()
            if t.underlying == underlying
        ]

    async def set_okx_spot(self, ticker: OKXSpotTicker):
        self._okx_spots[ticker.symbol] = ticker

        if self._redis:
            rkey = OKX_SPOT_KEY.format(symbol=ticker.symbol)
            await self._redis.setex(rkey, 10, self._serialize(ticker))

    def get_okx_spot(self, symbol: str) -> Optional[OKXSpotTicker]:
        return self._okx_spots.get(symbol)

    # ─── 健康检查 ──────────────────────────────────────────────────

    def snapshot_stats(self) -> dict:
        return {
            "poly_tickers":  len(self._poly_tickers),
            "okx_options":   len(self._okx_options),
            "okx_spots":     len(self._okx_spots),
            "poly_markets":  len(self.get_active_poly_markets()),
        }

    def get_stale_tickers(self, max_age_seconds: int = 30) -> list[str]:
        """返回超过 max_age_seconds 未更新的行情 key"""
        stale = []
        now = datetime.utcnow()
        for key, ticker in self._poly_tickers.items():
            if (now - ticker.ts).total_seconds() > max_age_seconds:
                stale.append(f"poly:{key}")
        for iid, ticker in self._okx_options.items():
            if (now - ticker.ts).total_seconds() > max_age_seconds:
                stale.append(f"okx:{iid}")
        return stale

    # ─── 工具方法 ──────────────────────────────────────────────────

    @staticmethod
    def _serialize(obj) -> str:
        def default(o):
            if isinstance(o, datetime):
                return o.isoformat()
            if hasattr(o, "__dict__"):
                return o.__dict__
            return str(o)
        return json.dumps(obj.__dict__, default=default)
