"""
入口文件 - 启动数据观测层

用法：
  python main.py                    # 使用默认配置
  python main.py --config my.yaml   # 指定配置文件
  python main.py --dry-run          # 仅打印健康状态，不持续运行
"""

import asyncio
import argparse
import logging
import signal
from datetime import datetime

from src.config import load_config, DEFAULT_CONFIG
from src.data.manager import DataManager
from src.data.models import MarketDataEvent

# 日志配置
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)


def on_market_event(event: MarketDataEvent):
    """示例：下游事件处理（后续替换为信号计算引擎）"""
    if event.source == "POLYMARKET":
        ticker = event.data
        if ticker.is_liquid:
            logger.info(
                f"[POLY] {ticker.event_title[:40]:<40} "
                f"{ticker.outcome} | "
                f"bid={ticker.best_bid:.3f} ask={ticker.best_ask:.3f} "
                f"prob={ticker.implied_prob:.1%}"
            )
    elif event.source == "OKX_SPOT":
        spot = event.data
        logger.debug(f"[SPOT] {spot.symbol}: ${spot.price:,.0f}")
    elif event.source == "CHAIN":
        logger.info(f"[CHAIN] {event.data.event_type} market={event.data.market_id[:10]}...")


async def main(config: dict, dry_run: bool = False):
    dm = DataManager(config)
    dm.on_event(on_market_event)

    # 优雅退出
    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    def _handle_signal():
        logger.info("收到退出信号，停止中...")
        stop_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, _handle_signal)

    if dry_run:
        logger.info("Dry-run 模式：仅检查配置，不启动采集")
        dm.print_health()
        return

    # 启动
    task = asyncio.create_task(dm.start())

    # 定期打印健康状态
    async def health_loop():
        while not stop_event.is_set():
            await asyncio.sleep(60)
            dm.print_health()

    health_task = asyncio.create_task(health_loop())

    # 等待退出信号
    await stop_event.wait()
    health_task.cancel()
    await dm.stop()
    task.cancel()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Polymarket × OKX 数据观测层")
    parser.add_argument("--config", default="config.yaml", help="配置文件路径")
    parser.add_argument("--dry-run", action="store_true", help="仅检查配置")
    args = parser.parse_args()

    config = {**DEFAULT_CONFIG, **load_config(args.config)}
    asyncio.run(main(config, dry_run=args.dry_run))
