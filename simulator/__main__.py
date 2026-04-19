import uvicorn

from simulator.log_config import configure_logging

if __name__ == "__main__":
    configure_logging()
    uvicorn.run(
        "simulator.app:app",
        host="0.0.0.0",
        port=8081,
        log_level="info",
    )
