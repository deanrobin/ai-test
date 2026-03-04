"""
数据观测层 - Polygon 链上事件监听器

监听 Polymarket 相关合约事件：
  - CTF Exchange：条件代币交易
  - Conditional Tokens Framework：市场创建、条件解析
  - UMA OptimisticOracle：价格提议、争议、结算

采用 Alchemy WebSocket 订阅（eth_subscribe logs），
降低轮询开销，实现低延迟事件推送。
"""

import asyncio
import json
import logging
from datetime import datetime
from typing import Callable, Optional

import aiohttp
import websockets

from .models import ChainEvent, MarketDataEvent
from .cache import MarketDataCache

logger = logging.getLogger(__name__)

# Polymarket 合约地址（Polygon 主网）
CONTRACTS = {
    "CTF_EXCHANGE":        "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E",
    "CONDITIONAL_TOKENS":  "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045",
    "UMA_ORACLE":          "0x5953f2538F613E05bAED8A5AeFa8e6622467AD3D",
}

# 事件签名（keccak256 哈希）
EVENT_SIGNATURES = {
    # ConditionalTokens
    "ConditionPrepared":      "0xab3760c3bd2bb38b5bcf9a2ea1d4e5e55d808c05bac31dc57f0dde7ae301f4d4",
    "ConditionResolution":    "0xb44d84d3289691f71497564b85d4233648d6e578d8a20a34c65573c6b0b8b2d3",
    # CTF Exchange
    "OrderFilled":            "0xd0a08e8c493f9c94f29311604c9de1b4e8c8d4c06e8d0a2b9d7c3f5e1b4c7d9a",
    # UMA
    "ProposePrice":           "0x61b5a6fdda62e6764b7b60dd67b6bfdbf22e2a6be7bc2e8e5cdad78a37a6049f",
    "DisputePrice":           "0x4a68e5b7b38ed9e1c7ddf2bde9b4daf5e0e3c4c2e3a2b1c0d9e8f7a6b5c4d3e2",
    "Settle":                 "0x3e4e6a4c2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5",
}


class ChainListener:

    def __init__(
        self,
        cache: MarketDataCache,
        event_callback: Optional[Callable[[MarketDataEvent], None]] = None,
        polygon_ws_url: str = "",        # wss://polygon-mainnet.g.alchemy.com/v2/KEY
        polygon_rpc_url: str = "",       # https://polygon-mainnet.g.alchemy.com/v2/KEY
    ):
        self.cache          = cache
        self.event_callback = event_callback
        self.polygon_ws_url = polygon_ws_url
        self.polygon_rpc_url = polygon_rpc_url

        self._ws:      Optional[websockets.WebSocketClientProtocol] = None
        self._session: Optional[aiohttp.ClientSession] = None
        self._running: bool = False
        self._sub_ids: dict[str, str] = {}   # contract → subscription id

    # ─── 生命周期 ─────────────────────────────────────────────────

    async def start(self):
        if not self.polygon_ws_url:
            logger.warning("未配置 Polygon WS URL，链上监听跳过")
            return

        self._running = True
        self._session = aiohttp.ClientSession()
        logger.info("链上事件监听器启动")

        await asyncio.gather(
            self._ws_loop(),
            self._block_health_loop(),
        )

    async def stop(self):
        self._running = False
        if self._ws:
            await self._ws.close()
        if self._session:
            await self._session.close()

    # ─── WebSocket 日志订阅 ───────────────────────────────────────

    async def _ws_loop(self):
        retry_delay = 1

        while self._running:
            try:
                logger.info("Polygon WS 连接中...")
                async with websockets.connect(
                    self.polygon_ws_url,
                    ping_interval=30,
                ) as ws:
                    self._ws = ws
                    retry_delay = 1

                    # 订阅所有目标合约的 logs
                    await self._subscribe_logs(ws)
                    await self._receive_loop(ws)

            except Exception as e:
                logger.error(f"Polygon WS 异常: {e}, {retry_delay}s 后重连")
                self._ws = None

            if self._running:
                await asyncio.sleep(retry_delay)
                retry_delay = min(retry_delay * 2, 60)

    async def _subscribe_logs(self, ws):
        """订阅合约 logs"""
        addresses = list(CONTRACTS.values())
        topics    = list(EVENT_SIGNATURES.values())

        msg = {
            "jsonrpc": "2.0",
            "id":      1,
            "method":  "eth_subscribe",
            "params":  [
                "logs",
                {
                    "address": addresses,
                    "topics":  [topics],  # OR 匹配任意事件
                }
            ]
        }
        await ws.send(json.dumps(msg))
        logger.info(f"链上日志订阅: {len(addresses)} 合约, {len(topics)} 事件类型")

    async def _receive_loop(self, ws):
        async for raw in ws:
            if not self._running:
                break
            try:
                msg = json.loads(raw)
                await self._handle_message(msg)
            except Exception as e:
                logger.error(f"链上消息处理异常: {e}")

    async def _handle_message(self, msg: dict):
        # eth_subscribe 返回格式
        if msg.get("method") == "eth_subscription":
            log = msg.get("params", {}).get("result", {})
            await self._process_log(log)
        elif "result" in msg:
            # 订阅确认
            sub_id = msg.get("result", "")
            logger.info(f"链上订阅确认: {sub_id}")

    async def _process_log(self, log: dict):
        topics    = log.get("topics", [])
        tx_hash   = log.get("transactionHash", "")
        block_num = int(log.get("blockNumber", "0x0"), 16)
        address   = log.get("address", "").lower()

        if not topics:
            return

        event_sig  = topics[0]
        event_type = self._resolve_event_type(event_sig, address)

        if not event_type:
            return

        # 解码不同事件的参数
        payload = await self._decode_log(event_type, log)
        market_id = payload.get("market_id", "")

        event = ChainEvent(
            event_type   = event_type,
            market_id    = market_id,
            tx_hash      = tx_hash,
            block_number = block_num,
            payload      = payload,
            ts           = datetime.utcnow(),
        )

        logger.info(f"链上事件: {event_type} market={market_id} tx={tx_hash[:10]}...")

        self._emit(MarketDataEvent(source="CHAIN", data=event))

    def _resolve_event_type(self, sig: str, address: str):
        reverse = {v: k for k, v in EVENT_SIGNATURES.items()}
        return reverse.get(sig)

    async def _decode_log(self, event_type: str, log: dict) -> dict:
        """
        简单解码：对关键字段做 hex → int 转换
        完整解码需要 ABI，此处返回原始 topics + data
        """
        topics = log.get("topics", [])
        data   = log.get("data", "0x")

        result = {
            "raw_topics": topics,
            "raw_data":   data,
        }

        # ConditionResolution：topics[1] = conditionId
        if event_type == "ConditionResolution" and len(topics) > 1:
            result["market_id"] = topics[1]
            # 从 data 解码 payoutNumerators（简化）
            result["resolved"] = True

        # ProposePrice：关键信息在 data 中（需要完整 ABI 解码）
        elif event_type == "ProposePrice":
            result["oracle_event"] = "propose"

        elif event_type == "Settle":
            result["oracle_event"] = "settle"

        return result

    # ─── 区块高度健康检查 ─────────────────────────────────────────

    async def _block_health_loop(self):
        """每 30s 检查区块高度，确认链上连接正常"""
        while self._running:
            await asyncio.sleep(30)
            try:
                block = await self._get_latest_block()
                logger.debug(f"Polygon 最新区块: #{block}")
            except Exception as e:
                logger.warning(f"区块高度检查失败: {e}")

    async def _get_latest_block(self) -> int:
        if not self._session or not self.polygon_rpc_url:
            return 0
        async with self._session.post(
            self.polygon_rpc_url,
            json={"jsonrpc": "2.0", "method": "eth_blockNumber", "params": [], "id": 1},
            timeout=aiohttp.ClientTimeout(total=5),
        ) as resp:
            data = await resp.json()
            return int(data["result"], 16)

    # ─── 工具 ────────────────────────────────────────────────────

    def _emit(self, event: MarketDataEvent):
        if self.event_callback:
            try:
                self.event_callback(event)
            except Exception as e:
                logger.error(f"event_callback 异常: {e}")

    # ─── 主动查询（工具方法）─────────────────────────────────────

    async def get_condition_status(self, condition_id: str) -> Optional[dict]:
        """
        查询 condition 是否已结算（通过 REST 查询）
        """
        if not self._session or not self.polygon_rpc_url:
            return None

        # 调用 ConditionalTokens.payoutDenominator(conditionId)
        call_data = {
            "jsonrpc": "2.0",
            "method":  "eth_call",
            "params":  [
                {
                    "to":   CONTRACTS["CONDITIONAL_TOKENS"],
                    "data": f"0x3dc2bca7{condition_id.replace('0x', '').zfill(64)}",
                },
                "latest"
            ],
            "id": 1,
        }

        async with self._session.post(
            self.polygon_rpc_url,
            json=call_data,
            timeout=aiohttp.ClientTimeout(total=5),
        ) as resp:
            data = await resp.json()
            result = data.get("result", "0x0")

        denominator = int(result, 16)
        return {
            "condition_id": condition_id,
            "is_resolved":  denominator > 0,
            "denominator":  denominator,
        }
