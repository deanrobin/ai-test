"""
数据观测层 - 统一管理器

职责：
  - 启动/停止所有采集器
  - 管理共享缓存
  - 提供统一的查询接口给上层模块
  - 维护连接状态与健康监控
"""

import asyncio
import logging
from datetime import datetime
from typing import Callable, Optional

from .cache import MarketDataCache
from .models import MarketDataEvent, PolyTicker, OKXOptionTicker, OKXSpotTicker
from .polymarket import PolymarketCollector
from .okx import OKXCollector
from .chain_listener import ChainListener

logger = logging.getLogger(__name__)


class DataManager:
    """
    数据观测层统一入口
    
    用法：
        dm = DataManager(config)
        dm.on_event(my_callback)    # 注册下游回调
        await dm.start()
    """

    def __init__(self, config: dict):
        self.config = config

        # 共享缓存
        self.cache = MarketDataCache(
            redis_url=config.get("redis_url", "redis://localhost:6379/0")
        )

        # 采集器
        self.poly_collector  = PolymarketCollector(
            cache            = self.cache,
            event_callback   = self._on_event,
            market_filter    = config.get("poly_market_filter", []),
            keyword_filter   = config.get("poly_keyword_filter", []),
            max_markets      = config.get("poly_max_markets", 50),
        )
        self.okx_collector   = OKXCollector(
            cache            = self.cache,
            event_callback   = self._on_event,
            underlyings      = config.get("okx_underlyings", ["BTC", "ETH"]),
            max_dte          = config.get("okx_max_dte", 60),
        )
        self.chain_listener  = ChainListener(
            cache            = self.cache,
            event_callback   = self._on_event,
            polygon_ws_url   = config.get("polygon_ws_url", ""),
            polygon_rpc_url  = config.get("polygon_rpc_url", ""),
        )

        # 下游回调列表
        self._callbacks: list[Callable[[MarketDataEvent], None]] = []

        # 状态
        self._running:    bool = False
        self._start_time: Optional[datetime] = None
        self._event_count: dict[str, int] = {
            "POLYMARKET": 0,
            "OKX_OPTION": 0,
            "OKX_SPOT":   0,
            "CHAIN":      0,
        }

    # ─── 事件回调注册 ─────────────────────────────────────────────

    def on_event(self, callback: Callable[[MarketDataEvent], None]):
        """注册下游事件处理器（可注册多个）"""
        self._callbacks.append(callback)
        return self  # 支持链式调用

    def _on_event(self, event: MarketDataEvent):
        """内部事件分发"""
        self._event_count[event.source] = self._event_count.get(event.source, 0) + 1
        for cb in self._callbacks:
            try:
                cb(event)
            except Exception as e:
                logger.error(f"下游回调异常: {e}")

    # ─── 生命周期 ─────────────────────────────────────────────────

    async def start(self):
        logger.info("DataManager 启动中...")
        self._running    = True
        self._start_time = datetime.utcnow()

        # 连接缓存
        await self.cache.connect()

        # 并发启动所有采集器
        await asyncio.gather(
            self.poly_collector.start(),
            self.okx_collector.start(),
            self.chain_listener.start(),
            return_exceptions=True,
        )

    async def stop(self):
        logger.info("DataManager 停止中...")
        self._running = False

        await asyncio.gather(
            self.poly_collector.stop(),
            self.okx_collector.stop(),
            self.chain_listener.stop(),
            return_exceptions=True,
        )
        await self.cache.close()
        logger.info("DataManager 已停止")

    # ─── 统一查询接口 ─────────────────────────────────────────────

    def get_poly_ticker(
        self, market_id: str, outcome: str = "YES"
    ) -> Optional[PolyTicker]:
        return self.cache.get_poly_ticker(market_id, outcome)

    def get_poly_pair(self, market_id: str) -> tuple[Optional[PolyTicker], Optional[PolyTicker]]:
        """同时返回 YES 和 NO 行情"""
        return (
            self.cache.get_poly_ticker(market_id, "YES"),
            self.cache.get_poly_ticker(market_id, "NO"),
        )

    def get_active_poly_markets(self) -> list[str]:
        return self.cache.get_active_poly_markets()

    def get_okx_option(self, instrument_id: str) -> Optional[OKXOptionTicker]:
        return self.cache.get_okx_option(instrument_id)

    def get_okx_options_by_underlying(self, underlying: str) -> list[OKXOptionTicker]:
        return self.cache.get_okx_options_by_underlying(underlying)

    def get_spot_price(self, symbol: str) -> Optional[float]:
        ticker = self.cache.get_okx_spot(f"{symbol}-USDT")
        return ticker.price if ticker else None

    def find_matching_option(
        self,
        underlying: str,
        target_strike: float,
        target_expiry_days: int,
        option_type: str = "C",
        max_strike_diff_pct: float = 0.05,
        max_dte_diff: int = 7,
    ) -> Optional[OKXOptionTicker]:
        """
        为 Polymarket 市场找最匹配的 OKX 期权
        
        matching 规则：
          - underlying 一致
          - strike 偏差 < max_strike_diff_pct
          - 到期日偏差 < max_dte_diff 天
          - 优先流动性好（bid-ask 价差最小）的合约
        """
        candidates = self.cache.get_okx_options_by_underlying(underlying)
        best = None
        best_score = float("inf")

        for opt in candidates:
            # Strike 偏差
            strike_diff = abs(opt.strike - target_strike) / target_strike
            if strike_diff > max_strike_diff_pct:
                continue

            # DTE 偏差
            dte_diff = abs(opt.dte - target_expiry_days)
            if dte_diff > max_dte_diff:
                continue

            # 期权类型
            if opt.option_type.value != option_type:
                continue

            # 流动性评分（越小越好）
            spread = opt.best_ask - opt.best_bid if opt.best_ask and opt.best_bid else 999
            score  = strike_diff * 10 + dte_diff * 0.1 + spread

            if score < best_score:
                best_score = score
                best = opt

        return best

    # ─── 健康监控 ─────────────────────────────────────────────────

    def health(self) -> dict:
        """返回系统健康状态"""
        cache_stats = self.cache.snapshot_stats()
        stale       = self.cache.get_stale_tickers(max_age_seconds=30)
        uptime_sec  = (
            (datetime.utcnow() - self._start_time).total_seconds()
            if self._start_time else 0
        )

        return {
            "status":      "RUNNING" if self._running else "STOPPED",
            "uptime_sec":  round(uptime_sec),
            "event_count": self._event_count,
            "cache":       cache_stats,
            "stale_feeds": stale,
            "connections": {
                "poly_ws":  self.poly_collector._ws is not None,
                "okx_ws":   self.okx_collector._ws is not None,
                "chain_ws": self.chain_listener._ws is not None,
            },
        }

    def print_health(self):
        h = self.health()
        print("\n── DataManager 健康状态 ──────────────────────")
        print(f"  状态:      {h['status']}")
        print(f"  运行时长:  {h['uptime_sec']}s")
        print(f"  事件总数:  {h['event_count']}")
        print(f"  缓存:      {h['cache']}")
        print(f"  连接:      {h['connections']}")
        if h["stale_feeds"]:
            print(f"  ⚠️  过期行情: {h['stale_feeds']}")
        print("─────────────────────────────────────────────\n")
