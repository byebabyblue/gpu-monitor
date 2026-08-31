"""Early, local-only diagnostics for the windowed PyInstaller backend."""

import faulthandler
import logging
import os
from pathlib import Path
import sys
import tempfile
import threading


logger = logging.getLogger("xerver.backend")
logger.addHandler(logging.NullHandler())
_installed = False
_log_stream = None


def _config_path():
    args = sys.argv[1:]
    for index, arg in enumerate(args):
        if arg in ("--config", "-c") and index + 1 < len(args):
            return Path(args[index + 1]).absolute()
        if arg.startswith("--config="):
            return Path(arg.partition("=")[2]).absolute()
    return Path(os.environ.get("GPU_MONITOR_CONFIG_PATH") or "config.json").absolute()


def install_backend_diagnostics():
    """Run before third-party imports, including from the PyInstaller runtime hook."""
    global _installed, _log_stream
    if _installed:
        return
    _installed = True
    directories = []
    try:
        directories.append(_config_path().parent / "logs")
    except (OSError, ValueError):
        pass
    try:
        directories.append(Path(tempfile.gettempdir()) / "Xerver" / "logs")
    except (OSError, ValueError):
        pass
    for directory in directories:
        try:
            directory.mkdir(parents=True, exist_ok=True)
            _log_stream = (directory / "backend.log").open(
                "a", encoding="utf-8", buffering=1, errors="backslashreplace",
            )
            break
        except (OSError, ValueError):
            continue
    if _log_stream is None:
        return  # An unwritable log location must not prevent backend startup.

    handler = logging.StreamHandler(_log_stream)
    handler.setFormatter(logging.Formatter("%(asctime)s [backend] %(levelname)s %(message)s"))
    logger.handlers = [handler]
    logger.propagate = False
    logger.setLevel(logging.INFO)
    # --windowed executables have sys.stdout/stderr=None even when their parent
    # redirects OS handles. Give Python errors a real, line-buffered destination.
    if sys.stdout is None:
        sys.stdout = _log_stream
    if sys.stderr is None:
        sys.stderr = _log_stream

    def uncaught(exc_type, exc_value, traceback):
        logger.critical("Unhandled backend exception", exc_info=(exc_type, exc_value, traceback))

    def thread_uncaught(args):
        logger.error(
            "Unhandled backend thread exception thread=%s", args.thread.name,
            exc_info=(args.exc_type, args.exc_value, args.exc_traceback),
        )

    sys.excepthook = uncaught
    threading.excepthook = thread_uncaught
    try:
        faulthandler.enable(file=_log_stream, all_threads=True)
    except (OSError, RuntimeError, ValueError):
        pass
    logger.info(
        "Backend process started pid=%s python=%s frozen=%s executable=%s",
        os.getpid(), sys.version.split()[0], bool(getattr(sys, "frozen", False)), sys.executable,
    )
    logger.info("Backend runtime directory=%s", getattr(sys, "_MEIPASS", "source"))
