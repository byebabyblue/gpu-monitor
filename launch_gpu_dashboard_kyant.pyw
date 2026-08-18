"""Launch the GPU dashboard without a console window on Windows."""

import os
import runpy
import sys
from pathlib import Path


APP_DIR = Path(__file__).resolve().parent
os.chdir(APP_DIR)
sys.argv = [
    str(APP_DIR / "gpu_dashboard_kyant.py"),
    "--backend-only",
    "--api-port",
    "8766",
]
runpy.run_path(str(APP_DIR / "gpu_dashboard_kyant.py"), run_name="__main__")
